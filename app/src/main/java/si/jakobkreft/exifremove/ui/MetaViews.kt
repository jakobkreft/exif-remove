// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import si.jakobkreft.exifremove.R
import si.jakobkreft.exifremove.engine.ChangeStatus
import si.jakobkreft.exifremove.engine.MetaCategory
import si.jakobkreft.exifremove.engine.MetaChange

/**
 * The shared vocabulary for showing metadata: one row per entry, a status
 * chip, and per-category headers. Used by the metadata viewer and by the
 * cleaning report, so a file reads the same either way.
 */

@Composable
fun MetaChangeRow(change: MetaChange, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(change.entry.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    change.entry.value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = if (change.status == ChangeStatus.REMOVED) {
                        TextDecoration.LineThrough
                    } else {
                        TextDecoration.None
                    },
                )
                if (change.status == ChangeStatus.CHANGED && change.newValue != null) {
                    Text(
                        "→ ${change.newValue}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            StatusChip(change)
        }
    }
}

@Composable
fun StatusChip(change: MetaChange) {
    val (label, container, content) = when (change.status) {
        ChangeStatus.REMOVED -> Triple(
            stringResource(R.string.status_removed),
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        ChangeStatus.KEPT -> Triple(
            stringResource(R.string.status_kept),
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChangeStatus.CHANGED -> Triple(
            stringResource(
                if (change.randomized) R.string.status_randomized else R.string.status_modified
            ),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        ChangeStatus.ADDED -> Triple(
            stringResource(
                if (change.randomized) R.string.status_randomized else R.string.status_added
            ),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun CategoryHeader(category: MetaCategory, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(top = 12.dp, bottom = 2.dp),
    ) {
        Icon(
            category.icon(),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            category.label(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
fun MetaCategory.label(): String = stringResource(
    when (this) {
        MetaCategory.LOCATION -> R.string.sum_location
        MetaCategory.DATE -> R.string.sum_date
        MetaCategory.CAMERA -> R.string.sum_camera
        MetaCategory.ORIENTATION -> R.string.sum_orientation
        MetaCategory.OTHER -> R.string.sum_other
    }
)

fun MetaCategory.icon(): ImageVector = when (this) {
    MetaCategory.LOCATION -> Icons.Outlined.LocationOn
    MetaCategory.DATE -> Icons.Outlined.Schedule
    MetaCategory.CAMERA -> Icons.Outlined.CameraAlt
    MetaCategory.ORIENTATION -> Icons.Outlined.ScreenRotation
    MetaCategory.OTHER -> Icons.Outlined.Description
}
