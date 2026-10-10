// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.picker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.exifremove.data.Template

class PickerIntegrationTest {

    @Test
    fun `file document id round trips`() {
        val id = PickerIntegration.documentId(Template.ID_SCRAMBLE, 42L)
        assertEquals(Template.ID_SCRAMBLE, PickerIntegration.templateIdOf(id))
        assertEquals(42L, PickerIntegration.mediaIdOf(id))
        assertNull(PickerIntegration.folderPathOf(id))
        assertFalse(PickerIntegration.isRoot(id))
    }

    @Test
    fun `folder document id round trips`() {
        val id = PickerIntegration.folderDocumentId(Template.ID_SCRAMBLE, "DCIM/Camera/")
        assertEquals(Template.ID_SCRAMBLE, PickerIntegration.templateIdOf(id))
        assertEquals("DCIM/Camera/", PickerIntegration.folderPathOf(id))
        assertNull(PickerIntegration.mediaIdOf(id))
    }

    @Test
    fun `a template stands for the whole of storage`() {
        val template = PickerIntegration.templateDocumentId(Template.ID_REMOVE_EVERYTHING)
        assertTrue(PickerIntegration.isTemplate(template))
        assertFalse(PickerIntegration.isRoot(template))
        assertEquals("", PickerIntegration.folderPathOf(template))
        assertNull(PickerIntegration.mediaIdOf(template))
        // An empty path must not produce a second, different id for it.
        assertEquals(
            template,
            PickerIntegration.folderDocumentId(Template.ID_REMOVE_EVERYTHING, "")
        )
    }

    @Test
    fun `the root is above every template and is no template itself`() {
        val root = PickerIntegration.ROOT_DOCUMENT_ID
        assertTrue(PickerIntegration.isRoot(root))
        assertFalse(PickerIntegration.isTemplate(root))
        // It names no folder, so it can never be listed as one.
        assertNull(PickerIntegration.folderPathOf(root))
        assertNull(PickerIntegration.mediaIdOf(root))
        assertNull(PickerIntegration.templateFor(root, Template.builtIns()))
        // No template id may collide with it.
        Template.builtIns().forEach { assertNotEquals(root, it.id) }
    }

    @Test
    fun `a numeric folder name is not read as a media id`() {
        val id = PickerIntegration.folderDocumentId(Template.ID_SCRAMBLE, "DCIM/100/")
        assertNull(PickerIntegration.mediaIdOf(id))
        assertEquals("DCIM/100/", PickerIntegration.folderPathOf(id))
    }

    @Test
    fun `built-in template ids survive the separator`() {
        // Every built-in id contains hyphens; only the slash may split.
        Template.builtIns().forEach { template ->
            val id = PickerIntegration.documentId(template.id, 7L)
            assertEquals(template.id, PickerIntegration.templateIdOf(id))
            assertEquals(7L, PickerIntegration.mediaIdOf(id))
        }
    }

    @Test
    fun `malformed ids do not parse as media`() {
        assertNull(PickerIntegration.mediaIdOf("template/f/not-a-number"))
        assertNull(PickerIntegration.mediaIdOf("template/f/"))
        assertNull(PickerIntegration.mediaIdOf("template"))
        assertNull(PickerIntegration.mediaIdOf(""))
    }

    @Test
    fun `template lookup ignores unknown roots`() {
        val templates = Template.builtIns()
        assertNotNull(
            PickerIntegration.templateFor(
                PickerIntegration.documentId(Template.ID_SCRAMBLE, 1L), templates
            )
        )
        assertNull(PickerIntegration.templateFor("deleted-template/f/1", templates))
    }
}
