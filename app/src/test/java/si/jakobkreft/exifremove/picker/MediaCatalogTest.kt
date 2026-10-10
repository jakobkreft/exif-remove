// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.picker

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaCatalogTest {

    @Test
    fun `relative paths are normalised to a trailing slash`() {
        assertEquals("DCIM/Camera/", MediaCatalog.relativePath("DCIM/Camera/"))
        assertEquals("DCIM/Camera/", MediaCatalog.relativePath("DCIM/Camera"))
        assertEquals("DCIM/Camera/", MediaCatalog.relativePath("/DCIM/Camera/"))
    }

    @Test
    fun `files sitting at the storage root have an empty path`() {
        assertEquals("", MediaCatalog.relativePath(null))
        assertEquals("", MediaCatalog.relativePath(""))
        assertEquals("", MediaCatalog.relativePath("/"))
    }

    @Test
    fun `folder names come off the end of the path`() {
        assertEquals("Camera", MediaCatalog.folderName("DCIM/Camera/"))
        assertEquals("Download", MediaCatalog.folderName("Download/"))
        // The storage root has no name of its own; the caller supplies one.
        assertEquals(null, MediaCatalog.folderName(""))
    }
}
