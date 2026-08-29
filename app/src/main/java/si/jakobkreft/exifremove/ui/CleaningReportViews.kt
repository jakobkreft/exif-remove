// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.ui

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PhotoSizeSelectLarge
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import si.jakobkreft.exifremove.R
import si.jakobkreft.exifremove.engine.ChangeStatus
import si.jakobkreft.exifremove.engine.CleaningReport
import si.jakobkreft.exifremove.engine.Finding
import si.jakobkreft.exifremove.engine.FindingKind
import si.jakobkreft.exifremove.engine.MetaCategory
import si.jakobkreft.exifremove.engine.ProcessError
import si.jakobkreft.exifremove.engine.ProcessedImage
import si.jakobkreft.exifremove.engine.TrailerKind

/**
 * The cleaning report: what the app actually did to each file.
 *
 * Collapsed it is one honest line; expanded it is the evidence. The app is
 * for people who do not want to take a privacy claim on faith, so the proof
 * is always one tap away — and never in the way of simply sharing.
 */

@Composable
fun FileResultRow(
    image: ProcessedImage,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val failed = image.file == null
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (failed) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
    ) {
        val report = image.report
        val canExpand = !failed && report != null && report.hasAnythingToReport
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (canExpand) Modifier.clickable { onToggle() } else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        failed -> Icons.Outlined.Block
                        image.mimeType.startsWith("video/") -> Icons.Filled.Movie
                        else -> Icons.Filled.Image
                    },
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (failed) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(image.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (failed) image.error.message() else report.summary(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (canExpand) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = stringResource(
                            if (expanded) R.string.report_hide_details
                            else R.string.report_show_details
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (failed && image.error == ProcessError.NOT_PROVABLY_CLEAN) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.error_not_provably_clean_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (expanded && report != null) {
                Spacer(Modifier.height(8.dp))
                ReportDetail(report)
            }
        }
    }
}

@Composable
fun ReportDetail(report: CleaningReport) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (report.findings.isNotEmpty()) {
            Text(
                stringResource(R.string.report_findings),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            report.findings.forEach { FindingRow(it) }
        }
        for (category in MetaCategory.entries) {
            val rows = report.changes.filter { it.entry.category == category }
            if (rows.isEmpty()) continue
            CategoryHeader(category)
            rows.forEach { MetaChangeRow(it) }
        }
    }
}

@Composable
fun FindingRow(finding: Finding) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    val (icon: ImageVector, text: String) = when (finding.kind) {
        FindingKind.TRAILING_DATA -> when (finding.trailer) {
            TrailerKind.VIDEO -> Icons.Outlined.Movie to
                stringResource(R.string.finding_trailing_video, size(finding.bytes))
            TrailerKind.IMAGE -> Icons.Outlined.Layers to
                stringResource(R.string.finding_trailing_image, size(finding.bytes))
            TrailerKind.UNKNOWN -> Icons.Outlined.Warning to
                stringResource(R.string.finding_trailing_unknown, size(finding.bytes))
        }
        FindingKind.EMBEDDED_THUMBNAIL -> Icons.Outlined.PhotoSizeSelectLarge to
            stringResource(R.string.finding_thumbnail, size(finding.bytes))
        FindingKind.METADATA_SEGMENT -> Icons.Outlined.Layers to pluralStringResource(
            R.plurals.finding_segments,
            finding.count,
            finding.count,
            finding.names.joinToString(", "),
        )
        FindingKind.VIDEO_BOXES -> Icons.Outlined.Movie to
            pluralStringResource(R.plurals.finding_video_boxes, finding.count, finding.count)
        FindingKind.RE_ENCODED -> Icons.Outlined.Autorenew to
            stringResource(R.string.finding_reencoded)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The "Verified clean" badge — the app's one real promise, stated plainly. */
@Composable
fun VerifiedBadge(modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.verified_clean),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun CleaningReport?.summary(): String {
    val context = LocalContext.current
    if (this == null) return stringResource(R.string.report_nothing_found)
    val parts = mutableListOf<String>()
    if (removedCount > 0) {
        parts += pluralStringResource(R.plurals.report_removed_n, removedCount, removedCount)
    }
    if (scrambledCount > 0) {
        parts += pluralStringResource(R.plurals.report_scrambled_n, scrambledCount, scrambledCount)
    }
    if (keptCount > 0) {
        parts += pluralStringResource(R.plurals.report_kept_n, keptCount, keptCount)
    }
    if (hiddenBytes > 0) {
        parts += stringResource(
            R.string.report_hidden_payload,
            Formatter.formatShortFileSize(context, hiddenBytes),
        )
    }
    if (findings.any { it.kind == FindingKind.RE_ENCODED }) {
        parts += stringResource(R.string.report_reencoded_short)
    }
    return if (parts.isEmpty()) stringResource(R.string.report_nothing_found)
    else parts.joinToString(" · ")
}

@Composable
private fun ProcessError?.message(): String = stringResource(
    when (this) {
        ProcessError.UNSUPPORTED_FORMAT -> R.string.error_unsupported_format
        ProcessError.NOT_PROVABLY_CLEAN -> R.string.error_not_provably_clean
        else -> R.string.error_could_not_read
    }
)
