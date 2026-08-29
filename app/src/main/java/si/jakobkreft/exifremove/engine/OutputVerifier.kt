// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.engine

import java.io.File
import java.io.IOException
import java.util.zip.CRC32

/** Thrown when a cleaned file cannot be proven free of metadata. */
class VerificationException(message: String) : IOException(message)

/**
 * Post-condition guard: re-parses what the stripper produced and refuses to
 * hand it back unless it is provably image-only.
 *
 * The strippers trust the sizes the *input* declares. A segment whose declared
 * length engulfs the bytes after it, or data appended past the end of the
 * image, can therefore reach the output without any exception being raised —
 * and the app would report success on a file that still carries everything.
 * This pass re-derives the structure from the produced bytes instead, trusting
 * nothing, and fails closed on anything it cannot account for.
 *
 * A content scan runs alongside it for metadata that survives *inside* an
 * otherwise well-formed segment, which a structural check cannot see.
 */
object OutputVerifier {

    /** Signatures that must never appear in output; ICC is legitimately kept. */
    private val FORBIDDEN_SIGNATURES = listOf(
        "http://ns.adobe.com/" to "XMP",
        "Photoshop 3.0" to "Photoshop/IPTC",
        "http://ns.google.com/photos/" to "Google camera XMP",
        "GCamera" to "Google camera metadata",
    )

    private const val EXIF_SIGNATURE = "Exif\u0000\u0000"

    /**
     * Verifies [file]. With [keepExif] an EXIF block is expected and allowed;
     * otherwise its presence anywhere is itself a failure.
     *
     * @throws VerificationException when the file is not provably clean.
     */
    fun verify(format: ImageFormat, file: File, keepExif: Boolean) {
        val bytes = file.readBytes()
        when (format) {
            ImageFormat.JPEG -> jpegProblem(bytes, keepExif)?.let { fail(it) }
            ImageFormat.PNG -> pngProblem(bytes, keepExif)?.let { fail(it) }
            ImageFormat.WEBP -> webpProblem(bytes, keepExif)?.let { fail(it) }
            // Videos are scrubbed in place rather than rebuilt, and HEIF is
            // re-encoded from decoded pixels; neither is verified here.
            ImageFormat.MP4, ImageFormat.HEIF, ImageFormat.UNSUPPORTED -> return
        }
        signatureProblem(bytes, keepExif)?.let { fail(it) }
    }

    private fun fail(problem: String): Nothing = throw VerificationException(problem)

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)

    // -------------------------------------------------------------- content

    private fun signatureProblem(bytes: ByteArray, keepExif: Boolean): String? {
        val signatures = FORBIDDEN_SIGNATURES.toMutableList()
        if (!keepExif) signatures += EXIF_SIGNATURE to "EXIF"
        for ((signature, name) in signatures) {
            if (indexOf(bytes, signature.toByteArray(Charsets.ISO_8859_1)) >= 0) {
                return "$name survived stripping"
            }
        }
        return null
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > haystack.size) return -1
        outer@ for (start in 0..haystack.size - needle.size) {
            for (i in needle.indices) {
                if (haystack[start + i] != needle[i]) continue@outer
            }
            return start
        }
        return -1
    }

    // ----------------------------------------------------------------- JPEG

    private fun jpegProblem(b: ByteArray, keepExif: Boolean): String? {
        if (b.size < 2 || u8(b, 0) != 0xFF || u8(b, 1) != 0xD8) {
            return "output does not start with a start-of-image marker"
        }
        var pos = 2
        while (true) {
            while (pos + 1 < b.size && u8(b, pos) == 0xFF && u8(b, pos + 1) == 0xFF) pos++
            if (pos + 1 >= b.size) return "output is truncated before the end-of-image marker"
            if (u8(b, pos) != 0xFF) return "expected a marker at offset $pos (a segment size overruns)"
            val marker = u8(b, pos + 1)
            when {
                marker == 0xD9 ->
                    return if (pos + 2 == b.size) null
                    else "${b.size - pos - 2} bytes of trailing data after the end-of-image marker"

                marker == 0xDA -> return jpegScanProblem(b, pos + 2)

                marker in 0xD0..0xD7 || marker == 0x01 -> pos += 2

                else -> {
                    if (pos + 3 >= b.size) return "truncated segment header for ${markerName(marker)}"
                    val size = u16(b, pos + 2)
                    if (size < 2) return "segment ${markerName(marker)} declares an invalid size"
                    if (pos + 2 + size > b.size) return "segment ${markerName(marker)} overruns the end of the file"
                    jpegSegmentProblem(marker, b, pos + 4, size - 2, keepExif)?.let { return it }
                    pos += 2 + size
                }
            }
        }
    }

    private fun jpegScanProblem(b: ByteArray, start: Int): String? {
        var i = start
        while (i + 1 < b.size) {
            if (u8(b, i) == 0xFF && u8(b, i + 1) == 0xD9) {
                return if (i + 2 == b.size) null
                else "${b.size - i - 2} bytes of trailing data after the end-of-image marker"
            }
            i++
        }
        return "scan data is not terminated by an end-of-image marker"
    }

    private fun jpegSegmentProblem(
        marker: Int,
        b: ByteArray,
        start: Int,
        length: Int,
        keepExif: Boolean,
    ): String? = when {
        marker == 0xE0 ->
            if (length == 14 && u8(b, start + 12) == 0 && u8(b, start + 13) == 0) null
            else "APP0 is not a thumbnail-free JFIF header"
        marker == 0xE1 ->
            if (keepExif && startsWith(b, start, length, EXIF_SIGNATURE)) null
            else "APP1 survived stripping"
        marker == 0xE2 ->
            if (startsWith(b, start, length, "ICC_PROFILE\u0000")) null
            else "a non-ICC APP2 segment survived stripping"
        marker == 0xEE ->
            if (startsWith(b, start, length, "Adobe")) null
            else "a non-Adobe APP14 segment survived stripping"
        marker == 0xFE || marker in 0xE0..0xEF ->
            "metadata segment ${markerName(marker)} survived stripping"
        // Tables whose declared size must tile exactly into their entries;
        // a mismatch means the segment is carrying extra hidden bytes.
        marker == 0xDB -> dqtProblem(b, start, length)
        marker == 0xC4 -> dhtProblem(b, start, length)
        else -> null
    }

    private fun startsWith(b: ByteArray, start: Int, length: Int, prefix: String): Boolean {
        if (length < prefix.length) return false
        for (i in prefix.indices) {
            if (b[start + i] != prefix[i].code.toByte()) return false
        }
        return true
    }

    private fun dqtProblem(b: ByteArray, start: Int, length: Int): String? {
        var p = start
        val end = start + length
        while (p < end) {
            val precision = u8(b, p) shr 4
            if (precision > 1) return "DQT holds an invalid quantization table (possible hidden data)"
            p += 1 + 64 * (precision + 1)
        }
        return if (p == end) null else "DQT size does not match its tables (possible hidden data)"
    }

    private fun dhtProblem(b: ByteArray, start: Int, length: Int): String? {
        var p = start
        val end = start + length
        while (p < end) {
            if (p + 17 > end) return "DHT size does not match its tables (possible hidden data)"
            var symbols = 0
            for (k in 0 until 16) symbols += u8(b, p + 1 + k)
            p += 17 + symbols
        }
        return if (p == end) null else "DHT size does not match its tables (possible hidden data)"
    }

    private fun markerName(marker: Int) = when {
        marker == 0xFE -> "COM"
        marker in 0xE0..0xEF -> "APP${marker - 0xE0}"
        else -> "0x%02X".format(marker)
    }

    // ------------------------------------------------------------------ PNG

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    private val PNG_ALLOWED = setOf(
        "IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB",
        "iCCP", "sBIT", "bKGD", "pHYs", "acTL", "fcTL", "fdAT",
    )

    private fun pngProblem(b: ByteArray, keepExif: Boolean): String? {
        if (b.size < 8 || !b.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) {
            return "output does not start with a PNG signature"
        }
        var pos = 8
        while (pos < b.size) {
            if (pos + 8 > b.size) return "truncated PNG chunk header"
            val length = ((u8(b, pos).toLong() shl 24) or (u8(b, pos + 1).toLong() shl 16) or
                (u8(b, pos + 2).toLong() shl 8) or u8(b, pos + 3).toLong())
            val name = String(b, pos + 4, 4, Charsets.US_ASCII)
            val crcPos = pos + 8 + length
            if (crcPos + 4 > b.size.toLong()) return "chunk $name overruns the end of the file"
            if (name !in PNG_ALLOWED && !(keepExif && name == "eXIf")) {
                return "chunk $name survived stripping"
            }
            val crc = CRC32().apply { update(b, pos + 4, 4 + length.toInt()) }.value
            val stored = ((u8(b, crcPos.toInt()).toLong() shl 24) or
                (u8(b, crcPos.toInt() + 1).toLong() shl 16) or
                (u8(b, crcPos.toInt() + 2).toLong() shl 8) or u8(b, crcPos.toInt() + 3).toLong())
            if (crc != stored) return "chunk $name has a bad CRC (possible hidden data)"
            pos = crcPos.toInt() + 4
            if (name == "IEND") {
                return if (pos == b.size) null else "${b.size - pos} bytes of trailing data after IEND"
            }
        }
        return "output has no IEND chunk"
    }

    // ----------------------------------------------------------------- WebP

    private val WEBP_ALLOWED = setOf("VP8 ", "VP8L", "VP8X", "ALPH", "ANIM", "ANMF", "ICCP")

    private fun webpProblem(b: ByteArray, keepExif: Boolean): String? {
        if (b.size < 12 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(b, 8, 4, Charsets.US_ASCII) != "WEBP"
        ) {
            return "output is not a RIFF/WebP container"
        }
        val riffSize = (u8(b, 4).toLong() or (u8(b, 5).toLong() shl 8) or
            (u8(b, 6).toLong() shl 16) or (u8(b, 7).toLong() shl 24))
        if (riffSize != b.size - 8L) return "RIFF size does not match the file length"
        var pos = 12
        while (pos < b.size) {
            if (pos + 8 > b.size) return "truncated WebP chunk header"
            val type = String(b, pos, 4, Charsets.US_ASCII)
            val size = (u8(b, pos + 4).toLong() or (u8(b, pos + 5).toLong() shl 8) or
                (u8(b, pos + 6).toLong() shl 16) or (u8(b, pos + 7).toLong() shl 24))
            val padded = size + (size and 1L)
            if (pos + 8 + padded > b.size) return "chunk $type overruns the end of the file"
            if (type !in WEBP_ALLOWED && !(keepExif && type == "EXIF")) {
                return "chunk $type survived stripping"
            }
            if (type == "VP8X" && size >= 1) {
                val flags = u8(b, pos + 8)
                if (flags and 0x04 != 0) return "VP8X still advertises an XMP chunk"
                if (!keepExif && flags and 0x08 != 0) return "VP8X still advertises an EXIF chunk"
            }
            pos += (8 + padded).toInt()
        }
        return null
    }
}
