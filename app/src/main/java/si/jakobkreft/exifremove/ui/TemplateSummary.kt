// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import si.jakobkreft.exifremove.R
import si.jakobkreft.exifremove.data.RuleAction
import si.jakobkreft.exifremove.data.Template

@Composable
fun RuleAction.label(): String = stringResource(
    when (this) {
        RuleAction.KEEP -> R.string.action_keep
        RuleAction.REMOVE -> R.string.action_remove
        RuleAction.RANDOMIZE -> R.string.action_randomize
    }
)

@Composable
fun templateSummary(template: Template): String {
    val main = listOf(
        stringResource(R.string.sum_location) to template.gps.label(),
        stringResource(R.string.sum_date) to template.dateTime.label(),
        stringResource(R.string.sum_camera) to template.cameraInfo.label(),
        stringResource(R.string.sum_other) to template.otherExif.label(),
    ).joinToString(" · ") { (label, act) -> "$label: $act" }

    // Six categories make an unreadable subtitle, so the two that exist only
    // to protect how a photo looks are mentioned only when they are kept.
    val extras = buildList {
        if (template.orientation == RuleAction.KEEP) {
            add(stringResource(R.string.sum_keeps_orientation))
        }
        if (template.gainMap == RuleAction.KEEP) add(stringResource(R.string.sum_keeps_hdr))
    }
    return if (extras.isEmpty()) main else "$main · ${extras.joinToString(", ")}"
}
