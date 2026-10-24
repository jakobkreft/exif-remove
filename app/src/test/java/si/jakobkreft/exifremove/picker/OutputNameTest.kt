// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.picker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.exifremove.data.Template

class OutputNameTest {

    private fun item(mime: String, name: String?) = MediaCatalog.Item(11L, mime, name)

    private fun nameFor(
        item: MediaCatalog.Item,
        randomFileNames: Boolean = false,
        convertUnsupported: Boolean = true,
        documentId: String = PickerIntegration.documentId(Template.ID_SCRAMBLE, 11L),
    ) = OutputName.of(documentId, item, randomFileNames, convertUnsupported)

    @Test
    fun `in-place formats keep their name and type`() {
        val output = nameFor(item("image/png", "holiday.png"))
        assertEquals("holiday.png", output.name)
        assertEquals("image/png", output.mimeType)
    }

    @Test
    fun `converted formats are advertised as the jpeg they become`() {
        val output = nameFor(item("image/heic", "IMG_0042.HEIC"))
        assertEquals("IMG_0042.jpg", output.name)
        assertEquals("image/jpeg", output.mimeType)
    }

    @Test
    fun `conversion off leaves the original type alone`() {
        val output = nameFor(item("image/heic", "IMG_0042.HEIC"), convertUnsupported = false)
        assertEquals("image/heic", output.mimeType)
    }

    @Test
    fun `random names hide the original and stay stable`() {
        val source = item("image/jpeg", "from-the-protest.jpg")
        val first = nameFor(source, randomFileNames = true)
        val second = nameFor(source, randomFileNames = true)
        // The picker queries the document more than once; both queries have
        // to agree, or the file arrives under a different name than shown.
        assertEquals(first.name, second.name)
        assertFalse(first.name.contains("protest"))
        assertTrue(first.name.startsWith("IMG_"))
        assertTrue(first.name.endsWith(".jpg"))
    }

    @Test
    fun `a different template yields a different random name`() {
        val source = item("image/jpeg", "a.jpg")
        val one = nameFor(
            source,
            randomFileNames = true,
            documentId = PickerIntegration.documentId(Template.ID_SCRAMBLE, 11L),
        )
        val two = nameFor(
            source,
            randomFileNames = true,
            documentId = PickerIntegration.documentId(Template.ID_REMOVE_EVERYTHING, 11L),
        )
        assertFalse(one.name == two.name)
    }

    @Test
    fun `videos get a video prefix and keep their container`() {
        val output = nameFor(item("video/mp4", null), randomFileNames = true)
        assertTrue(output.name.startsWith("VID_"))
        assertEquals("video/mp4", output.mimeType)
    }

    @Test
    fun `path separators are stripped from names`() {
        val output = nameFor(item("image/jpeg", "../../etc/passwd.jpg"))
        assertFalse(output.name.contains("/"))
    }
}
