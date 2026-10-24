// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

class MetadataStripperTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------- fixtures

    private fun jpegSegment(marker: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xFF)
        out.write(marker)
        val length = payload.size + 2
        out.write((length shr 8) and 0xFF)
        out.write(length and 0xFF)
        out.write(payload)
        return out.toByteArray()
    }

    /** A JFIF APP0 payload; [thumbnail] adds an embedded 2x2 RGB thumbnail. */
    private fun jfifPayload(thumbnail: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("JFIF".toByteArray())
        out.write(0)                       // identifier terminator
        out.write(byteArrayOf(1, 2))       // version
        out.write(0)                       // density units
        out.write(byteArrayOf(0, 1, 0, 1)) // x/y density
        if (thumbnail) {
            out.write(2); out.write(2)     // 2x2 thumbnail follows
            out.write(ByteArray(2 * 2 * 3) { 0x5A })
        } else {
            out.write(0); out.write(0)
        }
        return out.toByteArray()
    }

    private fun buildJpeg(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) // SOI
        out.write(jpegSegment(0xE0, jfifPayload())) // APP0
        out.write(jpegSegment(0xE1, "Exif\u0000\u0000".toByteArray() + ByteArray(10) { 0x42 })) // APP1 Exif
        out.write(jpegSegment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000".toByteArray())) // APP1 XMP
        out.write(jpegSegment(0xE2, "ICC_PROFILE\u0000".toByteArray() + ByteArray(4) { 7 })) // APP2 ICC
        out.write(jpegSegment(0xED, "Photoshop 3.0\u0000".toByteArray())) // APP13 IPTC
        out.write(jpegSegment(0xFE, "a comment".toByteArray())) // COM
        out.write(jpegSegment(0xDB, byteArrayOf(0) + ByteArray(64) { 1 })) // DQT
        // SOS + fake entropy data + EOI
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte()))
        out.write(byteArrayOf(0x11, 0x22, 0x33, 0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val len = data.size
        out.write(byteArrayOf(
            ((len shr 24) and 0xFF).toByte(), ((len shr 16) and 0xFF).toByte(),
            ((len shr 8) and 0xFF).toByte(), (len and 0xFF).toByte(),
        ))
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply { update(typeBytes); update(data) }.value
        out.write(byteArrayOf(
            ((crc shr 24) and 0xFF).toByte(), ((crc shr 16) and 0xFF).toByte(),
            ((crc shr 8) and 0xFF).toByte(), (crc and 0xFF).toByte(),
        ))
        return out.toByteArray()
    }

    /**
     * A 132-byte ICC profile whose header names the device, as a phone's
     * does: maker and model at bytes 48-55, the profile creator at 80-83.
     */
    private fun vendorProfile(): ByteArray = ByteArray(132).also {
        "GOOGPix9".toByteArray().copyInto(it, 48)
        "GOOG".toByteArray().copyInto(it, 80)
    }

    /** PNG iCCP data: profile name, NUL, compression method 0, zlib stream. */
    private fun iccpData(name: String, profile: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(name.toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(0, 0))
        DeflaterOutputStream(out).use { it.write(profile) }
        return out.toByteArray()
    }

    /** A PNG holding nothing but [extra] between its header and image data. */
    private fun minimalPng(vararg extra: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        out.write(pngChunk("IHDR", ByteArray(13)))
        extra.forEach { out.write(it) }
        out.write(pngChunk("IDAT", ByteArray(4) { 9 }))
        out.write(pngChunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun buildPng(iccp: ByteArray = iccpData("Pixel 9 Pro", vendorProfile())): ByteArray =
        minimalPng(
            pngChunk("tEXt", "Author\u0000secret".toByteArray()),
            pngChunk("eXIf", ByteArray(8) { 0x42 }),
            pngChunk("iCCP", iccp),
        )

    /** The data of the first [type] chunk in [png], or null when there is none. */
    private fun pngChunkData(png: ByteArray, type: String): ByteArray? {
        var pos = 8
        while (pos + 8 <= png.size) {
            val length = ((png[pos].toInt() and 0xFF) shl 24) or
                ((png[pos + 1].toInt() and 0xFF) shl 16) or
                ((png[pos + 2].toInt() and 0xFF) shl 8) or (png[pos + 3].toInt() and 0xFF)
            if (String(png, pos + 4, 4, Charsets.US_ASCII) == type) {
                return png.copyOfRange(pos + 8, pos + 8 + length)
            }
            pos += 12 + length
        }
        return null
    }

    private fun webpChunk(fourcc: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(fourcc.toByteArray(Charsets.US_ASCII))
        val len = data.size
        out.write(byteArrayOf(
            (len and 0xFF).toByte(), ((len shr 8) and 0xFF).toByte(),
            ((len shr 16) and 0xFF).toByte(), ((len shr 24) and 0xFF).toByte(),
        ))
        out.write(data)
        if (len % 2 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun buildWebp(): ByteArray = webpFile(
        // VP8X flags: ICC (0x20) + EXIF (0x08) + XMP (0x04)
        webpChunk("VP8X", byteArrayOf(0x2C, 0, 0, 0, 9, 0, 0, 9, 0, 0)),
        webpChunk("ICCP", ByteArray(6) { 7 }),
        webpChunk("VP8 ", ByteArray(11) { 3 }),
        webpChunk("EXIF", ByteArray(10) { 0x42 }),
        webpChunk("XMP ", "meta".toByteArray()),
    )

    private fun webpFile(vararg chunks: ByteArray): ByteArray {
        val bodyBytes = chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        val riffLen = bodyBytes.size + 4
        out.write(byteArrayOf(
            (riffLen and 0xFF).toByte(), ((riffLen shr 8) and 0xFF).toByte(),
            ((riffLen shr 16) and 0xFF).toByte(), ((riffLen shr 24) and 0xFF).toByte(),
        ))
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        out.write(bodyBytes)
        return out.toByteArray()
    }

    private fun stripToBytes(input: ByteArray): ByteArray {
        val src = tmp.newFile().apply { writeBytes(input) }
        val dst = tmp.newFile()
        val format = MetadataStripper.detectFormat(src)
        MetadataStripper.strip(format, src, dst)
        return dst.readBytes()
    }

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    // ---------------------------------------------------------------- tests

    @Test
    fun `detects formats from magic bytes`() {
        assertEquals(ImageFormat.JPEG,
            MetadataStripper.detectFormat(tmp.newFile().apply { writeBytes(buildJpeg()) }))
        assertEquals(ImageFormat.PNG,
            MetadataStripper.detectFormat(tmp.newFile().apply { writeBytes(buildPng()) }))
        assertEquals(ImageFormat.WEBP,
            MetadataStripper.detectFormat(tmp.newFile().apply { writeBytes(buildWebp()) }))
        assertEquals(ImageFormat.UNSUPPORTED,
            MetadataStripper.detectFormat(tmp.newFile().apply { writeBytes(ByteArray(20) { 1 }) }))
    }

    @Test
    fun `jpeg loses exif xmp iptc and comments but keeps icc and image data`() {
        val stripped = stripToBytes(buildJpeg())
        assertFalse(stripped.containsSequence("Exif".toByteArray()))
        assertFalse(stripped.containsSequence("adobe.com/xap".toByteArray()))
        assertFalse(stripped.containsSequence("Photoshop".toByteArray()))
        assertFalse(stripped.containsSequence("a comment".toByteArray()))
        assertTrue(stripped.containsSequence("ICC_PROFILE".toByteArray()))
        assertTrue(stripped.containsSequence("JFIF".toByteArray()))
        // entropy data + EOI intact
        assertTrue(stripped.containsSequence(byteArrayOf(0x11, 0x22, 0x33, 0xFF.toByte(), 0xD9.toByte())))
    }

    @Test
    fun `png loses text and exif chunks but keeps critical and color chunks`() {
        val stripped = stripToBytes(buildPng())
        assertFalse(stripped.containsSequence("tEXt".toByteArray()))
        assertFalse(stripped.containsSequence("secret".toByteArray()))
        assertFalse(stripped.containsSequence("eXIf".toByteArray()))
        assertTrue(stripped.containsSequence("IHDR".toByteArray()))
        assertTrue(stripped.containsSequence("iCCP".toByteArray()))
        assertTrue(stripped.containsSequence("IDAT".toByteArray()))
        assertTrue(stripped.containsSequence("IEND".toByteArray()))
    }

    @Test
    fun `webp loses exif and xmp chunks and clears vp8x flags`() {
        val stripped = stripToBytes(buildWebp())
        assertFalse(stripped.containsSequence("EXIF".toByteArray()))
        assertFalse(stripped.containsSequence("XMP ".toByteArray()))
        assertTrue(stripped.containsSequence("ICCP".toByteArray()))
        assertTrue(stripped.containsSequence("VP8 ".toByteArray()))
        // VP8X flag byte: EXIF+XMP cleared, ICC still set
        val vp8xIndex = stripped.indices.first {
            it + 4 <= stripped.size &&
                stripped.sliceArray(it until it + 4).contentEquals("VP8X".toByteArray())
        }
        assertEquals(0x20, stripped[vp8xIndex + 8].toInt() and 0xFF)
        // RIFF size patched to actual length
        val riffSize = (stripped[4].toInt() and 0xFF) or
            ((stripped[5].toInt() and 0xFF) shl 8) or
            ((stripped[6].toInt() and 0xFF) shl 16) or
            ((stripped[7].toInt() and 0xFF) shl 24)
        assertEquals(stripped.size - 8, riffSize)
    }

    @Test
    fun `jpeg drops everything appended after the end-of-image marker`() {
        // A Pixel motion photo / Ultra HDR file: a whole second file, carrying
        // its own EXIF, appended after the JPEG's end-of-image marker.
        val trailer = "ftypisom".toByteArray() + "Exif".toByteArray() +
            byteArrayOf(0, 0) + "Make=Google".toByteArray()
        val stripped = stripToBytes(buildJpeg() + trailer)

        assertFalse(stripped.containsSequence("Google".toByteArray()))
        assertFalse(stripped.containsSequence("ftypisom".toByteArray()))
        // The image itself is intact and ends exactly at its EOI marker.
        assertEquals(0xFF, stripped[stripped.size - 2].toInt() and 0xFF)
        assertEquals(0xD9, stripped[stripped.size - 1].toInt() and 0xFF)
    }

    @Test
    fun `jpeg drops the jfif thumbnail but keeps the jfif header`() {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        out.write(jpegSegment(0xE0, jfifPayload(thumbnail = true)))
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte()))
        out.write(byteArrayOf(0x11, 0x22, 0xFF.toByte(), 0xD9.toByte()))

        val stripped = stripToBytes(out.toByteArray())

        assertTrue(stripped.containsSequence("JFIF".toByteArray()))
        assertFalse(stripped.containsSequence(ByteArray(6) { 0x5A }))
        // Thumbnail dimensions in the surviving header are zeroed.
        val jfif = stripped.indexOfSequence("JFIF".toByteArray())
        assertEquals(0, stripped[jfif + 12].toInt())
        assertEquals(0, stripped[jfif + 13].toInt())
    }

    @Test
    fun `jpeg drops a jfxx extension segment`() {
        val jfxx = "JFXX".toByteArray() + byteArrayOf(0, 0x10) +
            "thumbnailbytes".toByteArray()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        out.write(jpegSegment(0xE0, jfxx))
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte()))
        out.write(byteArrayOf(0x11, 0xFF.toByte(), 0xD9.toByte()))

        val stripped = stripToBytes(out.toByteArray())

        assertFalse(stripped.containsSequence("JFXX".toByteArray()))
        assertFalse(stripped.containsSequence("thumbnailbytes".toByteArray()))
    }

    @Test
    fun `heic is detected as heif rather than as a video`() {
        val heic = tmp.newFile().apply { writeBytes(buildHeic()) }
        assertEquals(ImageFormat.HEIF, MetadataStripper.detectFormat(heic))
    }

    @Test
    fun `an mp4 is still detected as a video`() {
        val mp4 = tmp.newFile().apply { writeBytes(buildMp4()) }
        assertEquals(ImageFormat.MP4, MetadataStripper.detectFormat(mp4))
    }

    @Test
    fun `webp drops chunks that are not on the allow-list`() {
        val stripped = stripToBytes(buildWebpWithUnknownChunk())
        assertFalse(stripped.containsSequence("XTRA".toByteArray()))
        assertFalse(stripped.containsSequence("vendorsecret".toByteArray()))
        assertTrue(stripped.containsSequence("VP8 ".toByteArray()))
    }

    @Test
    fun `icc profile keeps its colour data but loses its device fields`() {
        val profile = ByteArray(128) { 0x11 }
        val payload = "ICC_PROFILE".toByteArray() + byteArrayOf(0, 1, 1) + profile
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        out.write(jpegSegment(0xE0, jfifPayload()))
        out.write(jpegSegment(0xE2, payload))
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte()))
        out.write(byteArrayOf(0x11, 0xFF.toByte(), 0xD9.toByte()))

        val stripped = stripToBytes(out.toByteArray())
        val icc = stripped.indexOfSequence("ICC_PROFILE".toByteArray()) + 14

        assertTrue(stripped.containsSequence("ICC_PROFILE".toByteArray()))
        // device manufacturer/model and profile creator zeroed, rest untouched
        assertEquals(0, stripped[icc + 48].toInt())
        assertEquals(0, stripped[icc + 52].toInt())
        assertEquals(0, stripped[icc + 80].toInt())
        assertEquals(0x11, stripped[icc + 16].toInt())
    }

    // ------------------------------------------------------- verifier tests

    @Test
    fun `verifier rejects data appended after the end-of-image marker`() {
        val clean = stripToBytes(buildJpeg())
        val file = tmp.newFile().apply { writeBytes(clean + "appended".toByteArray()) }
        val problem = verificationProblem(ImageFormat.JPEG, file)
        assertNotNull(problem)
        assertTrue(problem!!.contains("trailing data"))
    }

    @Test
    fun `verifier rejects a surviving metadata segment`() {
        val file = tmp.newFile().apply { writeBytes(buildJpeg()) }
        val problem = verificationProblem(ImageFormat.JPEG, file)
        assertNotNull(problem)
    }

    @Test
    fun `verifier accepts what the stripper produces`() {
        val file = tmp.newFile().apply { writeBytes(stripToBytes(buildJpeg())) }
        assertNull(verificationProblem(ImageFormat.JPEG, file))
        val png = tmp.newFile().apply { writeBytes(stripToBytes(buildPng())) }
        assertNull(verificationProblem(ImageFormat.PNG, png))
        val webp = tmp.newFile().apply { writeBytes(stripToBytes(buildWebp())) }
        assertNull(verificationProblem(ImageFormat.WEBP, webp))
    }

    private fun verificationProblem(format: ImageFormat, file: File): String? = try {
        OutputVerifier.verify(format, file, keepExif = false)
        null
    } catch (e: VerificationException) {
        e.message
    }

    private fun ByteArray.indexOfSequence(needle: ByteArray): Int {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun isoBox(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val size = payload.size + 8
        out.write(byteArrayOf(
            ((size shr 24) and 0xFF).toByte(), ((size shr 16) and 0xFF).toByte(),
            ((size shr 8) and 0xFF).toByte(), (size and 0xFF).toByte(),
        ))
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(payload)
        return out.toByteArray()
    }

    /** ftyp with an image brand + a top-level meta box, and no moov. */
    private fun buildHeic(): ByteArray =
        isoBox("ftyp", "heic".toByteArray() + ByteArray(4) + "mif1heic".toByteArray()) +
            isoBox("meta", ByteArray(4) + isoBox("hdlr", ByteArray(8) + "pict".toByteArray())) +
            isoBox("mdat", "Make=Google".toByteArray())

    private fun buildMp4(): ByteArray =
        isoBox("ftyp", "isom".toByteArray() + ByteArray(4) + "isomavc1".toByteArray()) +
            isoBox("moov", isoBox("mvhd", ByteArray(100))) +
            isoBox("mdat", ByteArray(16))

    private fun buildWebpWithUnknownChunk(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(webpChunk("VP8X", byteArrayOf(0x20, 0, 0, 0, 9, 0, 0, 9, 0, 0)))
        body.write(webpChunk("VP8 ", ByteArray(11) { 3 }))
        body.write(webpChunk("XTRA", "vendorsecret".toByteArray()))
        val bodyBytes = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        val riffLen = bodyBytes.size + 4
        out.write(byteArrayOf(
            (riffLen and 0xFF).toByte(), ((riffLen shr 8) and 0xFF).toByte(),
            ((riffLen shr 16) and 0xFF).toByte(), ((riffLen shr 24) and 0xFF).toByte(),
        ))
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        out.write(bodyBytes)
        return out.toByteArray()
    }

    // ------------------------------------------------------- report findings

    private fun stripWithLog(input: ByteArray): Pair<ByteArray, List<Finding>> {
        val src = tmp.newFile().apply { writeBytes(input) }
        val dst = tmp.newFile()
        val log = StripLog()
        MetadataStripper.strip(MetadataStripper.detectFormat(src), src, dst, log = log)
        return dst.readBytes() to log.findings()
    }

    @Test
    fun `a log never changes what the stripper writes`() {
        // The findings are reported through a nullable log; a safe call skips
        // its own arguments, so the work must not live inside one.
        val input = buildJpeg() + "ftypisom".toByteArray()
        val withoutLog = stripToBytes(input)
        val (withLog, _) = stripWithLog(input)
        assertArrayEquals(withLog, withoutLog)
    }

    @Test
    fun `an appended video is reported as a hidden video`() {
        val trailer = byteArrayOf(0, 0, 0, 0x20) + "ftypisom".toByteArray() + ByteArray(500)
        val (_, findings) = stripWithLog(buildJpeg() + trailer)

        val trailing = findings.single { it.kind == FindingKind.TRAILING_DATA }
        assertEquals(TrailerKind.VIDEO, trailing.trailer)
        assertEquals(trailer.size.toLong(), trailing.bytes)
    }

    @Test
    fun `an appended second image is reported as such`() {
        val trailer = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(300)
        val (_, findings) = stripWithLog(buildJpeg() + trailer)

        val trailing = findings.single { it.kind == FindingKind.TRAILING_DATA }
        assertEquals(TrailerKind.IMAGE, trailing.trailer)
        assertEquals(trailer.size.toLong(), trailing.bytes)
    }

    @Test
    fun `a jfif thumbnail is reported by the bytes it cost`() {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        out.write(jpegSegment(0xE0, jfifPayload(thumbnail = true)))
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte()))
        out.write(byteArrayOf(0x11, 0xFF.toByte(), 0xD9.toByte()))

        val (_, findings) = stripWithLog(out.toByteArray())

        val thumbnail = findings.single { it.kind == FindingKind.EMBEDDED_THUMBNAIL }
        assertEquals(2L * 2 * 3, thumbnail.bytes) // the 2x2 RGB thumbnail
    }

    @Test
    fun `dropped metadata segments are named`() {
        val (_, findings) = stripWithLog(buildJpeg())

        val segments = findings.single { it.kind == FindingKind.METADATA_SEGMENT }
        assertTrue(segments.names.contains("APP1"))  // EXIF and XMP
        assertTrue(segments.names.contains("APP13")) // Photoshop/IPTC
        assertTrue(segments.names.contains("COM"))   // comment
        assertFalse(segments.names.contains("APP2")) // ICC is kept
    }

    @Test
    fun `dropped png chunks are named and nothing is reported for a clean file`() {
        val (_, findings) = stripWithLog(buildPng())
        val segments = findings.single { it.kind == FindingKind.METADATA_SEGMENT }
        assertTrue(segments.names.containsAll(listOf("tEXt", "eXIf")))

        val (_, none) = stripWithLog(minimalPng())
        assertTrue(none.isEmpty())
    }

    // ------------------------------------------------------ colour profiles

    @Test
    fun `a png colour profile is cleaned inside its compression`() {
        val stripped = stripToBytes(buildPng())
        val chunk = pngChunkData(stripped, "iCCP")
        assertNotNull("the profile itself is kept", chunk)
        // The encoder's free-text name is replaced, not just the profile.
        val name = "${IccSanitizer.PNG_PROFILE_NAME}\u0000".toByteArray(Charsets.ISO_8859_1)
        assertArrayEquals(name, chunk!!.copyOf(name.size))
        assertFalse(stripped.containsSequence("Pixel".toByteArray()))

        val profile = InflaterInputStream(chunk.inputStream(name.size + 1, chunk.size - name.size - 1))
            .readBytes()
        assertEquals(132, profile.size)
        assertArrayEquals(ByteArray(8), profile.copyOfRange(48, 56)) // maker + model
        assertArrayEquals(ByteArray(4), profile.copyOfRange(80, 84)) // creator
    }

    @Test
    fun `an unreadable png colour profile is dropped rather than passed on`() {
        val (stripped, findings) = stripWithLog(buildPng(iccp = "not zlib".toByteArray()))
        assertNull(pngChunkData(stripped, "iCCP"))
        val segments = findings.single { it.kind == FindingKind.METADATA_SEGMENT }
        assertTrue(segments.names.contains("iCCP"))
        val file = tmp.newFile().apply { writeBytes(stripped) }
        assertNull(verificationProblem(ImageFormat.PNG, file))
    }

    @Test
    fun `verifier looks inside a png colour profile`() {
        // Well-formed and correctly named, but the profile was never cleaned.
        val uncleaned = minimalPng(
            pngChunk("iCCP", iccpData(IccSanitizer.PNG_PROFILE_NAME, vendorProfile()))
        )
        val problem = verificationProblem(ImageFormat.PNG, tmp.newFile().apply { writeBytes(uncleaned) })
        assertTrue(problem, problem!!.contains("colour profile"))

        // A clean profile still under the encoder's own name is caught too.
        val clean = vendorProfile().also { IccSanitizer.sanitizeProfileInPlace(it, it.size) }
        val named = minimalPng(pngChunk("iCCP", iccpData("Pixel 9 Pro", clean)))
        assertNotNull(verificationProblem(ImageFormat.PNG, tmp.newFile().apply { writeBytes(named) }))
    }

    @Test
    fun `verifier rejects an uncleaned colour profile in jpeg and webp`() {
        val jpeg = ByteArrayOutputStream().apply {
            write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
            write(jpegSegment(0xE2, "ICC_PROFILE\u0000".toByteArray() + byteArrayOf(1, 1) + vendorProfile()))
            write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x11, 0x22, 0xFF.toByte(), 0xD9.toByte()))
        }.toByteArray()
        val webp = webpFile(
            webpChunk("VP8X", byteArrayOf(0x20, 0, 0, 0, 9, 0, 0, 9, 0, 0)),
            webpChunk("ICCP", vendorProfile()),
            webpChunk("VP8 ", ByteArray(11) { 3 }),
        )
        for ((format, bytes) in listOf(ImageFormat.JPEG to jpeg, ImageFormat.WEBP to webp)) {
            val problem = verificationProblem(format, tmp.newFile().apply { writeBytes(bytes) })
            assertTrue("$format: $problem", problem?.contains("colour profile") == true)
            // ...and the stripper's output of the same file passes.
            val cleaned = tmp.newFile().apply { writeBytes(stripToBytes(bytes)) }
            assertNull("$format", verificationProblem(format, cleaned))
        }
    }
}
