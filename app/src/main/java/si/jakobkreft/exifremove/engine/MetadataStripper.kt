package si.jakobkreft.exifremove.engine

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

enum class ImageFormat { JPEG, PNG, WEBP, UNSUPPORTED }

/**
 * Rewrites image containers while dropping every metadata-carrying segment.
 * Works on a copy-through basis: only whitelisted structures are written to
 * the output, so unknown or novel metadata blocks are removed by default.
 */
object MetadataStripper {

    fun detectFormat(file: File): ImageFormat {
        val header = ByteArray(12)
        file.inputStream().use { ins ->
            val read = ins.read(header)
            if (read < 12) return ImageFormat.UNSUPPORTED
        }
        return when {
            header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() -> ImageFormat.JPEG
            header[0] == 0x89.toByte() && header[1] == 'P'.code.toByte() &&
                header[2] == 'N'.code.toByte() && header[3] == 'G'.code.toByte() -> ImageFormat.PNG
            header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
                header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
                header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() &&
                header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte() -> ImageFormat.WEBP
            else -> ImageFormat.UNSUPPORTED
        }
    }

    fun strip(format: ImageFormat, source: File, dest: File) {
        when (format) {
            ImageFormat.JPEG -> source.inputStream().buffered().use { ins ->
                dest.outputStream().buffered().use { outs -> stripJpeg(ins, outs) }
            }
            ImageFormat.PNG -> source.inputStream().buffered().use { ins ->
                dest.outputStream().buffered().use { outs -> stripPng(ins, outs) }
            }
            ImageFormat.WEBP -> stripWebp(source, dest)
            ImageFormat.UNSUPPORTED -> throw IOException("Unsupported format")
        }
    }

    // ---------------------------------------------------------------- JPEG

    private const val MARKER_SOI = 0xD8
    private const val MARKER_EOI = 0xD9
    private const val MARKER_SOS = 0xDA
    private const val MARKER_APP0 = 0xE0
    private const val MARKER_APP2 = 0xE2
    private const val MARKER_APP14 = 0xEE
    private const val MARKER_COM = 0xFE

    private fun stripJpeg(ins: InputStream, outs: OutputStream) {
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
                    // Scan header, then entropy-coded data until EOF: copy verbatim.
                    outs.write(0xFF); outs.write(code)
                    ins.copyTo(outs)
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
                    if (keepJpegSegment(code, payload)) {
                        outs.write(0xFF); outs.write(code)
                        outs.write(lenHi); outs.write(lenLo)
                        outs.write(payload)
                    }
                }
            }
        }
    }

    private fun keepJpegSegment(code: Int, payload: ByteArray): Boolean = when {
        // APP0 (JFIF) — structural, no private data
        code == MARKER_APP0 -> true
        // APP2 — keep only ICC color profiles
        code == MARKER_APP2 -> payload.startsWithAscii("ICC_PROFILE\u0000")
        // APP14 — Adobe transform info, needed to decode some JPEGs correctly
        code == MARKER_APP14 -> payload.startsWithAscii("Adobe")
        // All other APPn (Exif, XMP, IPTC, maker data, …) and comments: drop
        code in 0xE1..0xEF || code == MARKER_COM -> false
        // Everything else (quantization/huffman tables, frame headers, …): keep
        else -> true
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

    private fun stripPng(ins: InputStream, outs: OutputStream) {
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

            if (type in PNG_KEEP) {
                outs.write(lenBytes); outs.write(typeBytes); outs.write(data); outs.write(crc)
            }
            if (type == "IEND") return
        }
    }

    // ---------------------------------------------------------------- WebP

    private fun stripWebp(source: File, dest: File) {
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
                    if (type == "EXIF" || type == "XMP ") {
                        skipFully(ins, padded.toLong())
                    } else {
                        val data = ByteArray(padded)
                        readFully(ins, data)
                        if (type == "VP8X" && size >= 1) {
                            // Clear the EXIF (0x08) and XMP (0x04) flag bits
                            data[0] = (data[0].toInt() and 0x08.inv() and 0x04.inv()).toByte()
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
