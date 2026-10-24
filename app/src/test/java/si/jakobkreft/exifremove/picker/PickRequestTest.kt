// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.picker

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PickRequestTest {

    @Test
    fun `explicit types are passed through`() {
        assertArrayEquals(
            arrayOf("image/jpeg", "video/mp4"),
            PickRequest.mimeTypes(arrayOf("image/jpeg", "video/mp4"), "*/*"),
        )
    }

    @Test
    fun `a request for anything offers photos and videos`() {
        assertArrayEquals(
            arrayOf("image/*", "video/*"),
            PickRequest.mimeTypes(null, "*/*"),
        )
    }

    @Test
    fun `types the engine cannot clean are dropped`() {
        assertArrayEquals(
            arrayOf("image/png"),
            PickRequest.mimeTypes(arrayOf("application/pdf", "image/png"), null),
        )
    }

    @Test
    fun `a request for nothing cleanable still opens the picker`() {
        assertArrayEquals(
            arrayOf("image/*", "video/*"),
            PickRequest.mimeTypes(arrayOf("application/pdf"), null),
        )
    }

    @Test
    fun `a bare type with no extras is used`() {
        assertArrayEquals(arrayOf("image/*"), PickRequest.mimeTypes(null, "image/*"))
    }

    @Test
    fun `an intent that says nothing falls back`() {
        assertArrayEquals(arrayOf("image/*", "video/*"), PickRequest.mimeTypes(null, null))
    }
}
