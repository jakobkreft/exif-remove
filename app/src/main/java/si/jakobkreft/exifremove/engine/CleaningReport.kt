// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.engine

/**
 * What actually happened to one file, in enough detail that someone who
 * cares can check the app's work instead of trusting it.
 *
 * Two halves, because neither alone tells the whole story:
 *  - [changes] is the tag-level view — the location, the timestamp, the
 *    camera model that a metadata viewer would have shown.
 *  - [findings] is the structural view — whole containers removed, which
 *    no tag listing can show, because once they are gone there is nothing
 *    left to enumerate. A motion photo's hidden video lives here.
 */
data class CleaningReport(
    val format: ImageFormat,
    val changes: List<MetaChange>,
    val findings: List<Finding>,
    val originalBytes: Long,
    val cleanedBytes: Long,
    /** True when the output was re-parsed and proved free of metadata. */
    val verified: Boolean,
) {
    val removedCount: Int get() = changes.count { it.status == ChangeStatus.REMOVED }
    val keptCount: Int get() = changes.count { it.status == ChangeStatus.KEPT }
    val scrambledCount: Int
        get() = changes.count {
            it.randomized &&
                (it.status == ChangeStatus.CHANGED || it.status == ChangeStatus.ADDED)
        }

    /** Bytes of hidden payload removed beyond the metadata tags themselves. */
    val hiddenBytes: Long
        get() = findings
            .filter { it.kind == FindingKind.TRAILING_DATA || it.kind == FindingKind.EMBEDDED_THUMBNAIL }
            .sumOf { it.bytes }

    val hasAnythingToReport: Boolean
        get() = changes.isNotEmpty() || findings.isNotEmpty()
}
