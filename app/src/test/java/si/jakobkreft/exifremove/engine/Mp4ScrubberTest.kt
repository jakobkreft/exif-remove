// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import si.jakobkreft.exifremove.data.RuleAction
import si.jakobkreft.exifremove.data.Template
import java.io.ByteArrayOutputStream
import java.io.File

class Mp4ScrubberTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------- fixtures

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val size = payload.size + 8
        out.write(byteArrayOf(
            ((size shr 24) and 0xFF).toByte(), ((size shr 16) and 0xFF).toByte(),
            ((size shr 8) and 0xFF).toByte(), (size and 0xFF).toByte(),
        ))
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(payload)
        return out.toByteArray()
    }

    private fun fullBoxPayload(version: Int, body: ByteArray): ByteArray =
        byteArrayOf(version.toByte(), 0, 0, 0) + body

    private fun be32(v: Int) = byteArrayOf(
        ((v shr 24) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
    )

    /** mvhd-style: creation + modification + timescale + duration (v0). */
    private fun timeHeader(creation: Int) =
        fullBoxPayload(0, be32(creation) + be32(creation) + be32(1000) + be32(60000))

    private fun keysBox(names: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(fullBoxPayload(0, be32(names.size)))
        for (name in names) {
            val bytes = name.toByteArray(Charsets.UTF_8)
            out.write(be32(bytes.size + 8))
            out.write("mdta".toByteArray(Charsets.US_ASCII))
            out.write(bytes)
        }
        return out.toByteArray()
    }

    private fun ilstEntry(index: Int, value: String): ByteArray {
        val data = box("data", be32(1) + be32(0) + value.toByteArray(Charsets.UTF_8))
        val out = ByteArrayOutputStream()
        val size = data.size + 8
        out.write(be32(size))
        out.write(be32(index))
        out.write(data)
        return out.toByteArray()
    }

    private val gpsString = "+46.0511+014.5051/"

    private fun buildMp4(): ByteArray {
        val keys = box("keys", keysBox(listOf(
            "com.apple.quicktime.location.ISO6709",
            "com.android.model",
            "com.android.capture.fps",
        )))
        val ilst = box("ilst",
            ilstEntry(1, gpsString) +
                ilstEntry(2, "Pixel 10 Pro") +
                ilstEntry(3, "30.0"))
        val meta = box("meta",
            box("hdlr", fullBoxPayload(0, be32(0) + "mdta".toByteArray() + ByteArray(9))) +
                keys + ilst)
        val udta = box("udta",
            box("©xyz", byteArrayOf(0, 18, 0x15, 0xC7.toByte()) + gpsString.toByteArray()) +
                box("©mak", "Google".toByteArray()) +
                box("©cmt", "secret comment".toByteArray()))
        val trak = box("trak",
            box("tkhd", timeHeader(0x11223344)) +
                box("mdia", box("mdhd", timeHeader(0x11223344))))
        val moov = box("moov",
            box("mvhd", timeHeader(0x11223344)) + meta + trak + udta)
        val uuidBox = box("uuid",
            ByteArray(16) { 0x77 } + "<xmp>gps here</xmp>".toByteArray())
        val ftyp = box("ftyp", "isom".toByteArray() + be32(512) + "isomiso2".toByteArray())
        val mdat = box("mdat", ByteArray(64) { 0x5A })
        return ftyp + mdat + moov + uuidBox
    }

    private fun scrubbed(template: Template): Pair<ByteArray, ByteArray> {
        val original = buildMp4()
        val file: File = tmp.newFile().apply { writeBytes(original) }
        assertTrue(Mp4Scrubber.isMp4(file))
        Mp4Scrubber.scrub(file, template)
        return original to file.readBytes()
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

    private fun ByteArray.indexOfSequence(needle: ByteArray): Int {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private val removeAll = Template(id = "t", name = "t")

    // ---------------------------------------------------------------- tests

    @Test
    fun `file size never changes`() {
        val (original, result) = scrubbed(removeAll)
        assertEquals(original.size, result.size)
    }

    @Test
    fun `mdat and ftyp are untouched`() {
        val (original, result) = scrubbed(removeAll)
        val mdatIndex = original.indexOfSequence("mdat".toByteArray())
        for (i in 0 until 64) {
            assertEquals(original[mdatIndex + 4 + i], result[mdatIndex + 4 + i])
        }
        assertTrue(result.containsSequence("isom".toByteArray()))
    }

    @Test
    fun `remove all wipes device info gps comments xmp and timestamps`() {
        val (_, result) = scrubbed(removeAll)
        assertFalse(result.containsSequence("Pixel 10 Pro".toByteArray()))
        assertFalse(result.containsSequence("Google".toByteArray()))
        assertFalse(result.containsSequence("secret comment".toByteArray()))
        assertFalse(result.containsSequence(gpsString.toByteArray()))
        assertFalse(result.containsSequence("<xmp>".toByteArray()))
        assertFalse(result.containsSequence("30.0".toByteArray()))
        // creation timestamp 0x11223344 zeroed everywhere
        assertFalse(result.containsSequence(byteArrayOf(0x11, 0x22, 0x33, 0x44)))
    }

    @Test
    fun `keep rules preserve their categories`() {
        val template = Template(
            id = "t", name = "t",
            gps = RuleAction.KEEP,
            dateTime = RuleAction.KEEP,
            cameraInfo = RuleAction.KEEP,
            otherExif = RuleAction.REMOVE,
        )
        val (_, result) = scrubbed(template)
        assertTrue(result.containsSequence(gpsString.toByteArray()))
        assertTrue(result.containsSequence("Pixel 10 Pro".toByteArray()))
        assertTrue(result.containsSequence("Google".toByteArray()))
        assertTrue(result.containsSequence(byteArrayOf(0x11, 0x22, 0x33, 0x44)))
        // other still wiped
        assertFalse(result.containsSequence("secret comment".toByteArray()))
        assertFalse(result.containsSequence("<xmp>".toByteArray()))
    }

    @Test
    fun `randomize gps rewrites location in place with same length`() {
        val template = Template(id = "t", name = "t", gps = RuleAction.RANDOMIZE)
        val (original, result) = scrubbed(template)
        assertEquals(original.size, result.size)
        assertFalse(result.containsSequence(gpsString.toByteArray()))
        // A replacement ISO6709 string exists: starts with + or - and ends with /
        val text = String(result, Charsets.ISO_8859_1)
        val match = Regex("""[+-]\d{2}\.\d+[+-]\d{3}\.\d+/""").find(text)
        assertNotNull(match)
        assertEquals(gpsString.length, match!!.value.length)
    }

    @Test
    fun `remove all frees the entire meta box leaving no keys or ilst`() {
        val (_, result) = scrubbed(removeAll)
        assertFalse(result.containsSequence("keys".toByteArray()))
        assertFalse(result.containsSequence("ilst".toByteArray()))
        assertFalse(result.containsSequence("mdta".toByteArray()))
    }

    @Test
    fun `partial keep preserves the exact meta structure for strict parsers`() {
        val template = Template(
            id = "t", name = "t",
            gps = RuleAction.KEEP,
            otherExif = RuleAction.REMOVE,
            cameraInfo = RuleAction.REMOVE,
        )
        val (original, result) = scrubbed(template)
        // Structure intact: keys/ilst/data headers all still present
        assertTrue(result.containsSequence("keys".toByteArray()))
        assertTrue(result.containsSequence("ilst".toByteArray()))
        assertTrue(result.containsSequence("data".toByteArray()))
        assertTrue(result.containsSequence(gpsString.toByteArray()))
        // Removed values are blanked, not renamed: entry sizes unchanged
        assertFalse(result.containsSequence("Pixel 10 Pro".toByteArray()))
        assertEquals(original.size, result.size)
        // The blanked UTF-8 value is spaces, keeping the string type valid
        val ilstIndex = result.indexOfSequence("ilst".toByteArray())
        assertTrue(String(result, ilstIndex, result.size - ilstIndex, Charsets.ISO_8859_1)
            .contains("            ")) // 12 spaces = "Pixel 10 Pro".length
    }

    @Test
    fun `metadata reader lists entries before and nothing sensitive after`() {
        val file = tmp.newFile().apply { writeBytes(buildMp4()) }
        val before = Mp4MetadataReader.read(file)
        // "+46.0511+014.5051/" is displayed in DMS format
        assertTrue(before.any {
            it.category == MetaCategory.LOCATION && it.value == "46° 3′ 3.96″ N, 14° 30′ 18.36″ E"
        })
        assertTrue(before.any { it.value == "Pixel 10 Pro" })
        assertTrue(before.any { it.value == "Google" && it.name == "Make" })
        assertTrue(before.any { it.category == MetaCategory.DATE })

        Mp4Scrubber.scrub(file, removeAll)
        val after = Mp4MetadataReader.read(file)
        assertTrue(after.none { it.category == MetaCategory.LOCATION })
        assertTrue(after.none { it.category == MetaCategory.CAMERA })
        assertTrue(after.none { it.category == MetaCategory.DATE })
    }

    @Test
    fun `randomize dates writes a nonzero timestamp everywhere`() {
        val template = Template(id = "t", name = "t", dateTime = RuleAction.RANDOMIZE)
        val (_, result) = scrubbed(template)
        assertFalse(result.containsSequence(byteArrayOf(0x11, 0x22, 0x33, 0x44)))
        // timescale still intact right after the two timestamps in mvhd
        assertTrue(result.containsSequence(be32(1000) + be32(60000)))
    }
}
