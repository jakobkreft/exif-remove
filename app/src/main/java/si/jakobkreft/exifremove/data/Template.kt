// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.data

import kotlinx.serialization.Serializable

@Serializable
enum class RuleAction { KEEP, REMOVE, RANDOMIZE }

/**
 * A cleaning template. Each category of metadata gets an action.
 * XMP, IPTC, comments and embedded thumbnails are always removed;
 * ICC color profiles are always kept.
 */
@Serializable
data class Template(
    val id: String,
    val name: String,
    val gps: RuleAction = RuleAction.REMOVE,
    val dateTime: RuleAction = RuleAction.REMOVE,
    val cameraInfo: RuleAction = RuleAction.REMOVE,
    val otherExif: RuleAction = RuleAction.REMOVE,
    /**
     * Which way up the picture is. Eight possible values that say nothing
     * about the photographer, but dropping them leaves some viewers showing
     * the photo sideways.
     */
    val orientation: RuleAction = RuleAction.REMOVE,
    /**
     * The Ultra HDR gain map. Holds no identifying data, but without it an
     * HDR photo renders flat on an HDR screen.
     */
    val gainMap: RuleAction = RuleAction.REMOVE,
    val builtIn: Boolean = false,
) {
    /** True when some metadata must be written back after a full strip. */
    val needsRewrite: Boolean
        get() = listOf(gps, dateTime, cameraInfo, otherExif, orientation)
            .any { it != RuleAction.REMOVE }

    companion object {
        const val ID_REMOVE_EVERYTHING = "builtin-remove-everything"
        const val ID_REMOVE_LOCATION = "builtin-remove-location"
        const val ID_SCRAMBLE = "builtin-scramble"

        /** Removed in 1.1. */
        const val LEGACY_ID_KEEP_ORIENTATION = "builtin-keep-orientation"

        fun builtIns(): List<Template> = listOf(
            Template(
                id = ID_REMOVE_EVERYTHING,
                name = "Remove everything",
                builtIn = true,
            ),
            Template(
                id = ID_REMOVE_LOCATION,
                name = "Remove location only",
                gps = RuleAction.REMOVE,
                dateTime = RuleAction.KEEP,
                cameraInfo = RuleAction.KEEP,
                otherExif = RuleAction.KEEP,
                // This template means "everything but the location", so the
                // two that only affect how the photo looks stay as well.
                orientation = RuleAction.KEEP,
                gainMap = RuleAction.KEEP,
                builtIn = true,
            ),
            Template(
                id = ID_SCRAMBLE,
                name = "Scramble location & date",
                gps = RuleAction.RANDOMIZE,
                dateTime = RuleAction.RANDOMIZE,
                builtIn = true,
            ),
        )
    }
}
