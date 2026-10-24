// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.picker

/**
 * What an asking app said it wanted, read off its ACTION_GET_CONTENT intent.
 */
object PickRequest {

    private val FALLBACK = arrayOf("image/*", "video/*")

    /**
     * The types to open the document picker with: what the caller asked for,
     * narrowed to what the engine can clean.
     *
     * A caller asking for any file type is offered photos and videos rather
     * than everything on the device — anything else would be picked and then
     * refused. A caller asking only for types the engine cannot clean is
     * given the same offer, so the picker is never opened empty.
     */
    fun mimeTypes(declared: Array<String>?, type: String?): Array<String> {
        val asked = declared?.toList() ?: listOfNotNull(type)
        val usable = asked.filter { it.startsWith("image/") || it.startsWith("video/") }
        return if (usable.isEmpty()) FALLBACK else usable.toTypedArray()
    }
}
