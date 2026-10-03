// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import si.jakobkreft.exifremove.R
import si.jakobkreft.exifremove.data.RuleAction
import si.jakobkreft.exifremove.data.Template

@Composable
fun templateSummary(template: Template): String {
    @Composable
    fun action(action: RuleAction): String = when (action) {
        RuleAction.KEEP -> stringResource(R.string.action_keep)
        RuleAction.REMOVE -> stringResource(R.string.action_remove)
        RuleAction.RANDOMIZE -> stringResource(R.string.action_randomize)
    }
    val main = listOf(
        stringResource(R.string.sum_location) to action(template.gps),
        stringResource(R.string.sum_date) to action(template.dateTime),
        stringResource(R.string.sum_camera) to action(template.cameraInfo),
        stringResource(R.string.sum_other) to action(template.otherExif),
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
