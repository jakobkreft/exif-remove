// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.engine

import si.jakobkreft.exifremove.data.RuleAction
import si.jakobkreft.exifremove.data.Template

/** Metadata category a piece of metadata belongs to, shared by all engines. */
enum class MetaCategory { LOCATION, DATE, CAMERA, ORIENTATION, OTHER }

/** What [this] template does with metadata of [category]. */
internal fun Template.ruleFor(category: MetaCategory): RuleAction = when (category) {
    MetaCategory.LOCATION -> gps
    MetaCategory.DATE -> dateTime
    MetaCategory.CAMERA -> cameraInfo
    MetaCategory.ORIENTATION -> orientation
    MetaCategory.OTHER -> otherExif
}

/** One human-readable piece of metadata found in a file. */
data class MetaEntry(
    val category: MetaCategory,
    val name: String,
    val value: String,
)
