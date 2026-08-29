// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.engine

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

enum class ImageFormat { JPEG, PNG, WEBP, MP4, HEIF, UNSUPPORTED }

/**
 * Rewrites image containers while dropping every metadata-carrying segment.
 * Works on a copy-through basis: only whitelisted structures are written to
 * the output, so unknown or novel metadata blocks are removed by default.
 */
object MetadataStripper {

    fun detectFormat(file: File): ImageFormat {
        val header = ByteArray(12)
        file.inputStream().use { ins -> if (!tryReadFully(ins, header)) return ImageFormat.UNSUPPORTED }
        return when {
            header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() -> ImageFormat.JPEG
            header[0] == 0x89.toByte() && header[1] == 'P'.code.toByte() &&
                header[2] == 'N'.code.toByte() && header[3] == 'G'.code.toByte() -> ImageFormat.PNG
            header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
                header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
                header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() &&
                header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte() -> ImageFormat.WEBP
            String(header, 4, 4, Charsets.US_ASCII) in MP4_FIRST_BOXES ->
                if (isHeif(file)) ImageFormat.HEIF else ImageFormat.MP4
            else -> ImageFormat.UNSUPPORTED
        }
    }

    private val MP4_FIRST_BOXES = setOf("ftyp", "moov", "mdat", "free", "skip", "wide")

    /**
     * HEIC/AVIF share the ISO BMFF container with MP4, so the magic bytes
     * alone cannot tell them apart. A still-image HEIF advertises an image
     * brand in `ftyp` and stores its metadata in a top-level `meta` box
     * instead of a `moov` — nothing the video scrubber knows how to touch,
     * which is why these must never be mistaken for videos.
     */
    private val HEIF_BRANDS = setOf(
        "heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs",
        "mif1", "mif2", "msf1", "miaf", "avif", "avis", "MiHE", "MiHB",
    )

    private fun isHeif(file: File): Boolean = try {
        var heifBrand = false
        var hasMoov = false
        var hasMeta = false
        RandomAccessFile(file, "r").use { raf ->
            Mp4Boxes.forEachBox(raf, 0, raf.length()) { box ->
                when (box.type) {
                    "ftyp" -> {
                        val brands = ByteArray(minOf(box.end - box.payloadStart, 64L).toInt())
                        raf.seek(box.payloadStart)
                        raf.readFully(brands)
                        // major brand, 4 bytes of minor version, then compatible brands
                        for (offset in brands.indices step 4) {
                            if (offset == 4 || offset + 4 > brands.size) continue
                            if (String(brands, offset, 4, Charsets.ISO_8859_1) in HEIF_BRANDS) {
                                heifBrand = true
                            }
                        }
                    }
                    "moov" -> hasMoov = true
                    "meta" -> hasMeta = true
                    else -> Unit
                }
            }
        }
        heifBrand && hasMeta && !hasMoov
    } catch (e: Exception) {
        false
    }

    /**
     * Rewrites [source] into [dest] without metadata. With [keepExif] the
     * EXIF block survives (for surgical per-tag editing afterwards) while
     * XMP, IPTC, comments and other metadata are still dropped.
     */
    fun strip(
        format: ImageFormat,
        source: File,
        dest: File,
        keepExif: Boolean = false,
        log: StripLog? = null,
    ) {
        when (format) {
            ImageFormat.JPEG -> source.inputStream().buffered().use { ins ->
                dest.outputStream().buffered().use { outs -> stripJpeg(ins, outs, keepExif, log) }
            }
            ImageFormat.PNG -> source.inputStream().buffered().use { ins ->
                dest.outputStream().buffered().use { outs -> stripPng(ins, outs, keepExif, log) }
            }
            ImageFormat.WEBP -> stripWebp(source, dest, keepExif, log)
            ImageFormat.MP4 -> throw IOException("Videos are handled by Mp4Scrubber")
            ImageFormat.HEIF -> throw IOException("HEIF is re-encoded, not stripped in place")
            ImageFormat.UNSUPPORTED -> throw IOException("Unsupported format")
        }
    }

    // ---------------------------------------------------------------- JPEG

    private const val MARKER_SOI = 0xD8
    private const val MARKER_EOI = 0xD9
    private const val MARKER_SOS = 0xDA
    private const val MARKER_APP0 = 0xE0
    private const val MARKER_APP1 = 0xE1
    private const val MARKER_APP2 = 0xE2
    private const val MARKER_APP14 = 0xEE
    private const val MARKER_COM = 0xFE

    private fun stripJpeg(
        ins: InputStream,
        outs: OutputStream,
        keepExif: Boolean,
        log: StripLog?,
    ) {
        if (readByte(ins) != 0xFF || readByte(ins) != MARKER_SOI) {
            throw IOException("Not a JPEG file")
        }
        outs.write(0xFF)
        outs.write(MARKER_SOI)

        while (true) {
            var b = readByte(ins)
            if (b != 0xFF) throw IOException("Corrupt JPEG: expected marker")
            var code = readByte(ins)
            while (code == 0xFF) code = readByte(ins) // fill bytes

            when {
                code == MARKER_EOI -> {
                    outs.write(0xFF); outs.write(code)
                    return
                }
                code == MARKER_SOS -> {
                    // Scan header + entropy-coded data, copied verbatim but only
                    // as far as the end-of-image marker. Anything appended after
                    // it — a motion photo's MP4, an Ultra HDR gain map, a vendor
                    // debug trailer — is not image data and carries a full copy
                    // of the original metadata, so it must not be carried over.
                    outs.write(0xFF); outs.write(code)
                    val trailing = copyScanUntilEoi(ins, outs, log)
                    log?.droppedTrailing(trailing)
                    return
                }
                code == 0x01 || code in 0xD0..0xD7 -> {
                    // Standalone markers, no payload
                    outs.write(0xFF); outs.write(code)
                }
                else -> {
                    val lenHi = readByte(ins)
                    val lenLo = readByte(ins)
                    val length = (lenHi shl 8) or lenLo
                    if (length < 2) throw IOException("Corrupt JPEG: bad segment length")
                    val payload = ByteArray(length - 2)
                    readFully(ins, payload)
                    val kept = sanitizeJpegSegment(code, payload, keepExif)
                    if (kept == null) {
                        log?.droppedSegment(jpegSegmentName(code), payload.size.toLong())
                    } else if (kept.size < payload.size) {
                        // Only JFIF shrinks, and only by losing its thumbnail.
                        log?.droppedThumbnail((payload.size - kept.size).toLong())
                    }
                    if (kept != null) {
                        val keptLength = kept.size + 2
                        outs.write(0xFF); outs.write(code)
                        outs.write((keptLength shr 8) and 0xFF); outs.write(keptLength and 0xFF)
                        outs.write(kept)
                    }
                }
            }
        }
    }

    /** JFIF APP0 without a thumbnail: identifier, version, density, 0x0 thumbnail. */
    private const val JFIF_HEADER_SIZE = 14

    /**
     * Returns the payload to write for a segment, or null to drop it. Most
     * segments pass through untouched; JFIF is rebuilt without its thumbnail,
     * which would otherwise smuggle a visual copy of the original image.
     */
    private fun sanitizeJpegSegment(code: Int, payload: ByteArray, keepExif: Boolean): ByteArray? = when {
        // APP0 - plain JFIF is structural, but both JFIF and its JFXX
        // extension can embed a thumbnail. Keep the JFIF header with the
        // thumbnail fields zeroed; drop JFXX and anything else outright.
        code == MARKER_APP0 ->
            if (payload.startsWithAscii("JFIF\u0000") && payload.size >= JFIF_HEADER_SIZE) {
                payload.copyOf(JFIF_HEADER_SIZE).also {
                    it[12] = 0 // thumbnail width
                    it[13] = 0 // thumbnail height
                }
            } else {
                null
            }
        // APP1 - the EXIF block itself (never XMP, which is also APP1)
        code == MARKER_APP1 ->
            payload.takeIf { keepExif && it.startsWithAscii("Exif\u0000\u0000") }
        // APP2 - keep only ICC color profiles, with identifying text scrubbed
        code == MARKER_APP2 ->
            if (payload.startsWithAscii("ICC_PROFILE\u0000")) IccSanitizer.sanitize(payload) else null
        // APP14 - Adobe transform info, needed to decode some JPEGs correctly
        code == MARKER_APP14 -> payload.takeIf { it.startsWithAscii("Adobe") }
        // All other APPn (XMP, IPTC, maker data, ...) and comments: drop
        code in 0xE1..0xEF || code == MARKER_COM -> null
        // Everything else (quantization/huffman tables, frame headers, ...): keep
        else -> payload
    }

    /**
     * Copies entropy-coded scan data up to and including the end-of-image
     * marker, then stops. Every 0xFF inside scan data is byte-stuffed as
     * FF00 or is a restart marker (FFD0-FFD7), so the first FFD9 found is
     * the real end of the image.
     */
    private fun copyScanUntilEoi(ins: InputStream, outs: OutputStream, log: StripLog?): Long {
        val buffer = ByteArray(8192)
        var pendingFf = false
        while (true) {
            val read = ins.read(buffer)
            if (read <= 0) return 0 // truncated file: nothing more to copy
            var index = 0
            while (index < read) {
                val byte = buffer[index].toInt() and 0xFF
                if (pendingFf && byte == MARKER_EOI) {
                    outs.write(buffer, 0, index + 1)
                    val trailerStart = index + 1
                    // Naming what the trailer was makes the removal legible:
                    // "a hidden video" lands where "1.4 MB of data" does not.
                    log?.trailerKind(classifyTrailer(buffer, trailerStart, read))
                    return (read - trailerStart).toLong() + drain(ins)
                }
                pendingFf = byte == 0xFF
                index++
            }
            outs.write(buffer, 0, read)
        }
    }

    /** Identifies an appended payload from its own magic bytes. */
    private fun classifyTrailer(buffer: ByteArray, start: Int, end: Int): TrailerKind {
        val available = end - start
        if (available >= 2 &&
            buffer[start] == 0xFF.toByte() && buffer[start + 1] == 0xD8.toByte()
        ) {
            return TrailerKind.IMAGE
        }
        if (available >= 8 &&
            String(buffer, start + 4, 4, Charsets.US_ASCII) in MP4_FIRST_BOXES
        ) {
            return TrailerKind.VIDEO
        }
        return TrailerKind.UNKNOWN
    }

    /** Counts (and discards) whatever is left in the stream. */
    private fun drain(ins: InputStream): Long {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = ins.read(buffer)
            if (read <= 0) return total
            total += read
        }
    }

    private fun jpegSegmentName(code: Int): String = when {
        code == MARKER_COM -> "COM"
        code in 0xE0..0xEF -> "APP${code - 0xE0}"
        else -> "0x%02X".format(code)
    }

    // ----------------------------------------------------------------- PNG

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    // Critical chunks + color handling + animation. Everything else
    // (tEXt, zTXt, iTXt, eXIf, tIME, private chunks) is dropped.
    private val PNG_KEEP = setOf(
        "IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB",
        "iCCP", "sBIT", "bKGD", "pHYs", "acTL", "fcTL", "fdAT"
    )

    private fun stripPng(
        ins: InputStream,
        outs: OutputStream,
        keepExif: Boolean,
        log: StripLog?,
    ) {
        val sig = ByteArray(8)
        readFully(ins, sig)
        if (!sig.contentEquals(PNG_SIGNATURE)) throw IOException("Not a PNG file")
        outs.write(sig)

        while (true) {
            val lenBytes = ByteArray(4)
            readFully(ins, lenBytes)
            val length = beInt(lenBytes)
            if (length < 0) throw IOException("Corrupt PNG: bad chunk length")
            val typeBytes = ByteArray(4)
            readFully(ins, typeBytes)
            val type = String(typeBytes, Charsets.US_ASCII)
            val data = ByteArray(length)
            readFully(ins, data)
            val crc = ByteArray(4)
            readFully(ins, crc)

            if (type in PNG_KEEP || (keepExif && type == "eXIf")) {
                outs.write(lenBytes); outs.write(typeBytes); outs.write(data); outs.write(crc)
            } else {
                log?.droppedSegment(type, length.toLong())
            }
            if (type == "IEND") {
                val trailing = drain(ins)
                log?.droppedTrailing(trailing)
                return
            }
        }
    }

    // ---------------------------------------------------------------- WebP

    // Structural + colour chunks only. Everything else (EXIF, "XMP ", and any
    // unknown or vendor chunk) is dropped: an allow-list, so a chunk type this
    // build has never heard of cannot carry metadata through.
    private val WEBP_KEEP = setOf(
        "VP8 ", "VP8L", "VP8X", "ALPH", "ANIM", "ANMF", "ICCP",
    )

    private fun stripWebp(source: File, dest: File, keepExif: Boolean, log: StripLog?) {
        source.inputStream().buffered().use { ins ->
            val header = ByteArray(12)
            readFully(ins, header)
            dest.outputStream().buffered().use { outs ->
                outs.write(header)
                while (true) {
                    val fourcc = ByteArray(4)
                    val first = ins.read(fourcc)
                    if (first == -1) break
                    if (first < 4) throw IOException("Corrupt WebP chunk")
                    val sizeBytes = ByteArray(4)
                    readFully(ins, sizeBytes)
                    val size = leInt(sizeBytes)
                    if (size < 0) throw IOException("Corrupt WebP: bad chunk size")
                    val padded = size + (size and 1)
                    val type = String(fourcc, Charsets.US_ASCII)
                    val keep = type in WEBP_KEEP || (keepExif && type == "EXIF")
                    if (!keep) {
                        log?.droppedSegment(type.trim(), size.toLong())
                        skipFully(ins, padded.toLong())
                    } else {
                        val data = ByteArray(padded)
                        readFully(ins, data)
                        if (type == "ICCP") {
                            IccSanitizer.sanitizeProfileInPlace(data, size)
                        }
                        if (type == "VP8X" && size >= 1) {
                            // Clear the XMP flag bit, and EXIF unless kept
                            var flags = data[0].toInt() and 0x04.inv()
                            if (!keepExif) flags = flags and 0x08.inv()
                            data[0] = flags.toByte()
                        }
                        outs.write(fourcc); outs.write(sizeBytes); outs.write(data)
                    }
                }
            }
        }
        // Patch the RIFF size to the actual bytes written
        RandomAccessFile(dest, "rw").use { raf ->
            val riffSize = (raf.length() - 8).toInt()
            raf.seek(4)
            raf.write(riffSize and 0xFF)
            raf.write((riffSize shr 8) and 0xFF)
            raf.write((riffSize shr 16) and 0xFF)
            raf.write((riffSize shr 24) and 0xFF)
        }
    }

    // ------------------------------------------------------------- helpers

    private fun readByte(ins: InputStream): Int {
        val b = ins.read()
        if (b == -1) throw EOFException("Unexpected end of file")
        return b
    }

    /** Fills [buffer] completely; false when the stream ends first. */
    private fun tryReadFully(ins: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = ins.read(buffer, offset, buffer.size - offset)
            if (read == -1) return false
            offset += read
        }
        return true
    }

    private fun readFully(ins: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = ins.read(buffer, offset, buffer.size - offset)
            if (read == -1) throw EOFException("Unexpected end of file")
            offset += read
        }
    }

    private fun skipFully(ins: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = ins.skip(remaining)
            if (skipped <= 0) {
                if (ins.read() == -1) throw EOFException("Unexpected end of file")
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun beInt(b: ByteArray): Int =
        ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)

    private fun leInt(b: ByteArray): Int =
        ((b[3].toInt() and 0xFF) shl 24) or ((b[2].toInt() and 0xFF) shl 16) or
            ((b[1].toInt() and 0xFF) shl 8) or (b[0].toInt() and 0xFF)

    private fun ByteArray.startsWithAscii(prefix: String): Boolean {
        if (size < prefix.length) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i].code.toByte()) return false
        }
        return true
    }
}
