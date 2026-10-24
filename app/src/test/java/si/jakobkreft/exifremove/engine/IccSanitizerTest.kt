// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream

class IccSanitizerTest {

    private fun u32(value: Int) = byteArrayOf(
        (value shr 24).toByte(), (value shr 16).toByte(),
        (value shr 8).toByte(), value.toByte(),
    )

    /**
     * A minimal ICC profile carrying one tag: a v4 `mluc` copyright holding
     * UTF-16 text, which is how a real Display P3 profile stores it.
     */
    private fun profileWithCopyright(text: String, tag: String = "cprt"): ByteArray {
        val utf16 = text.toByteArray(Charsets.UTF_16BE)
        val tagData = ByteArrayOutputStream().apply {
            write("mluc".toByteArray())
            write(u32(0))            // reserved
            write(u32(1))            // one record
            write(u32(12))           // record size
            write("enUS".toByteArray())
            write(u32(utf16.size))   // length
            write(u32(28))           // offset, relative to the tag
            write(utf16)
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write(ByteArray(128) { 0x11 })   // header, recognisably non-zero
            write(u32(1))                    // tag count
            write(tag.toByteArray())
            write(u32(144))                  // tag data offset
            write(u32(tagData.size))
            write(tagData)
        }.toByteArray()
    }

    @Test
    fun `a utf-16 vendor copyright is blanked`() {
        val profile = profileWithCopyright("Copyright (c) 2023 Google Inc.")
        val sizeBefore = profile.size
        val needle = "Google".toByteArray(Charsets.UTF_16BE)
        assertTrue(
            profile.toList().windowed(needle.size)
                .any { it.toByteArray().contentEquals(needle) }
        )

        IccSanitizer.sanitizeProfileInPlace(profile, profile.size)

        assertFalse(
            profile.toList().windowed(needle.size)
                .any { it.toByteArray().contentEquals(needle) }
        )
        // the structure is preserved: nothing resized, so every tag offset
        // recorded in the table is still valid, and the tag still exists
        assertEquals(sizeBefore, profile.size)
        assertEquals("cprt", String(profile, 132, 4, Charsets.ISO_8859_1))
        assertEquals("mluc", String(profile, 144, 4, Charsets.ISO_8859_1))
    }

    @Test
    fun `the colour space name is left alone`() {
        // 'desc' names the colour space, not a vendor, so it must survive.
        val profile = profileWithCopyright("Display P3", tag = "desc")
        IccSanitizer.sanitizeProfileInPlace(profile, profile.size)
        val needle = "Display P3".toByteArray(Charsets.UTF_16BE)
        assertTrue(
            profile.toList().windowed(needle.size)
                .any { it.toByteArray().contentEquals(needle) }
        )
    }

    /** PNG iCCP data: profile name, NUL, compression method 0, zlib stream. */
    private fun iccpData(name: String, profile: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(name.toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(0, 0))
        DeflaterOutputStream(out).use { it.write(profile) }
        return out.toByteArray()
    }

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean =
        toList().windowed(needle.size).any { it.toByteArray().contentEquals(needle) }

    @Test
    fun `a png profile is inflated, cleaned and renamed`() {
        val original = profileWithCopyright("Copyright (c) 2023 Google Inc.")
        val rebuilt = IccSanitizer.sanitizePngChunk(iccpData("Skia", original))
        assertNotNull(rebuilt)
        assertTrue(rebuilt!!.copyOf(12).contentEquals("ICC profile\u0000".toByteArray()))

        val profile = IccSanitizer.pngProfile(rebuilt)
        assertNotNull(profile)
        assertEquals(original.size, profile!!.size)
        assertFalse(profile.containsSequence("Google".toByteArray(Charsets.UTF_16BE)))
    }

    @Test
    fun `a png profile that inflates past any real size is refused`() {
        // 17 MB of zeros compresses to a few kilobytes.
        assertNull(IccSanitizer.pngProfile(iccpData("bomb", ByteArray(17 shl 20))))
    }

    @Test
    fun `bytes after the compressed png profile are refused`() {
        val data = iccpData("ICC profile", profileWithCopyright("x")) + "hidden".toByteArray()
        assertNull(IccSanitizer.pngProfile(data))
        assertNull(IccSanitizer.sanitizePngChunk(data))
    }

    @Test
    fun `a malformed profile is left alone rather than crashing`() {
        val claims = ByteArray(140) { 0x7F }          // tag count far beyond the buffer
        IccSanitizer.sanitizeProfileInPlace(claims, claims.size)
        val truncated = ByteArray(40)
        IccSanitizer.sanitizeProfileInPlace(truncated, truncated.size)
    }
}
