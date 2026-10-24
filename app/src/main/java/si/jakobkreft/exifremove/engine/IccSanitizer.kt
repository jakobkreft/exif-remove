// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.engine

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater

/**
 * ICC colour profiles are kept — dropping them shifts the colours of every
 * wide-gamut photo — but their 128-byte header carries fields that identify
 * the device that wrote the profile rather than describing the colour space:
 * the device manufacturer and model signatures, the profile creator, and the
 * profile's MD5 identifier. Those are zeroed here.
 *
 * The header is not the only place a vendor shows up: the tag table carries
 * text tags too, and a stock Display P3 profile ships a copyright reading
 * "Copyright (c) 2023 Google Inc." — stored as UTF-16, so a plain byte search
 * for "Google" never sees it. Those text tags are blanked in place.
 *
 * Nothing is resized or removed: tag offsets stay valid, the colour transform
 * is untouched, and the profile renders identically. The profile description
 * ("Display P3") is deliberately left alone — it names a colour space, not a
 * manufacturer, and some pipelines key off it.
 *
 * PNG is the odd one out: it stores the profile zlib-compressed, where no
 * byte-level edit or search can reach it, so it is inflated, cleaned and
 * compressed again.
 */
internal object IccSanitizer {

    private const val ICC_HEADER_SIZE = 128

    /** JPEG APP2: "ICC_PROFILE\u0000" + chunk number + chunk count, then profile bytes. */
    private const val JPEG_ICC_PREFIX = 14

    /** Header byte ranges that name the device rather than the colour space. */
    private val IDENTIFYING_FIELDS = listOf(
        4 until 8,    // preferred CMM signature
        48 until 56,  // device manufacturer + device model
        80 until 84,  // profile creator
        84 until 100, // profile ID (an MD5 of the profile, a stable fingerprint)
    )

    /**
     * Text tags that name a vendor. `desc` is excluded on purpose: it holds
     * the colour space name, which is generic.
     */
    private val IDENTIFYING_TAGS = setOf("cprt", "dmnd", "dmdd")

    /**
     * Written over the free-text name a PNG encoder gives its profile, which
     * can name the software or device ("Skia", a phone model) as easily as
     * the colour space.
     */
    const val PNG_PROFILE_NAME = "ICC profile"

    /** Real profiles are kilobytes; anything far larger is a decompression bomb. */
    private const val MAX_PROFILE_SIZE = 16 shl 20

    /** Sanitizes a JPEG APP2 payload in place, returning it for convenience. */
    fun sanitize(payload: ByteArray): ByteArray {
        // Chunk numbering is 1-based; the header only exists in the first chunk.
        val chunkNumber = if (payload.size > 12) payload[12].toInt() and 0xFF else 1
        if (chunkNumber <= 1) {
            sanitizeProfileInPlace(payload, payload.size, offset = JPEG_ICC_PREFIX)
        }
        return payload
    }

    /**
     * Rebuilds the data of a PNG iCCP chunk with the profile cleaned and the
     * name replaced. Null when the chunk cannot be read, so the caller drops
     * it rather than passing on a profile it never looked inside.
     */
    fun sanitizePngChunk(data: ByteArray): ByteArray? {
        val profile = pngProfile(data) ?: return null
        sanitizeProfileInPlace(profile, profile.size)
        val out = ByteArrayOutputStream()
        out.write(PNG_PROFILE_NAME.toByteArray(Charsets.ISO_8859_1))
        out.write(0) // end of the name
        out.write(0) // compression method: zlib, the only one PNG defines
        DeflaterOutputStream(out).use { it.write(profile) }
        return out.toByteArray()
    }

    /**
     * The uncompressed profile in a PNG iCCP chunk: a 1–79 byte name, a NUL,
     * compression method 0, then exactly one zlib stream. Null for anything
     * else, including bytes left over after the stream.
     */
    fun pngProfile(data: ByteArray): ByteArray? {
        val nameEnd = (1 until minOf(data.size, 80)).firstOrNull { data[it] == 0.toByte() }
            ?: return null
        val streamStart = nameEnd + 2
        if (streamStart > data.size || data[nameEnd + 1] != 0.toByte()) return null
        val inflater = Inflater()
        return try {
            inflater.setInput(data, streamStart, data.size - streamStart)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer)
                // Nothing out and not finished: truncated, or wants a preset
                // dictionary PNG does not allow. Either way, unreadable.
                if (read == 0 && !inflater.finished()) return null
                out.write(buffer, 0, read)
                if (out.size() > MAX_PROFILE_SIZE) return null
            }
            if (inflater.remaining != 0) null else out.toByteArray()
        } catch (e: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    /** Zeroes the identifying header fields and vendor text of an ICC profile. */
    fun sanitizeProfileInPlace(profile: ByteArray, size: Int, offset: Int = 0) {
        if (size - offset < ICC_HEADER_SIZE) return
        for (field in IDENTIFYING_FIELDS) {
            for (index in field) profile[offset + index] = 0
        }
        blankVendorTags(profile, offset, size)
    }

    /**
     * Walks the tag table and blanks the vendor-naming text tags. Every bound
     * is checked: a profile split across APP2 chunks will have tag data beyond
     * this buffer, and a malformed one must not take the clean down with it.
     */
    private fun blankVendorTags(profile: ByteArray, base: Int, size: Int) {
        val tableStart = base + ICC_HEADER_SIZE
        if (tableStart + 4 > size) return
        val count = readU32(profile, tableStart)
        if (count !in 1..1024) return
        for (index in 0 until count) {
            val record = tableStart + 4 + index * 12
            if (record + 12 > size) return
            val signature = String(profile, record, 4, Charsets.ISO_8859_1)
            if (signature !in IDENTIFYING_TAGS) continue
            val dataStart = base + readU32(profile, record + 4)
            val dataSize = readU32(profile, record + 8)
            if (dataStart < base || dataSize < 8 || dataStart + dataSize > size) continue
            blankTextTag(profile, dataStart, dataSize)
        }
    }

    /** Overwrites a text tag's characters, keeping its type and length intact. */
    private fun blankTextTag(profile: ByteArray, start: Int, size: Int) {
        when (String(profile, start, 4, Charsets.ISO_8859_1)) {
            // ICC v4: a count of records, each pointing at UTF-16BE text.
            "mluc" -> {
                val records = readU32(profile, start + 8)
                val recordSize = readU32(profile, start + 12)
                if (recordSize < 12 || records !in 1..256) return
                for (index in 0 until records) {
                    val record = start + 16 + index * recordSize
                    if (record + 12 > start + size) return
                    val length = readU32(profile, record + 4)
                    val offset = start + readU32(profile, record + 8)
                    if (offset < start || offset + length > start + size) continue
                    for (i in 0 until length) profile[offset + i] = 0
                }
            }
            // ICC v2 textDescription: ASCII count, then the ASCII text.
            "desc" -> {
                val asciiLength = readU32(profile, start + 8)
                val asciiStart = start + 12
                if (asciiLength < 1 || asciiStart + asciiLength > start + size) return
                for (i in 0 until asciiLength) profile[asciiStart + i] = 0
            }
            // Plain null-terminated ASCII.
            else -> for (i in (start + 8) until (start + size)) profile[i] = 0
        }
    }

    private fun readU32(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or
            ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
}
