// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.engine

/** One human-readable piece of metadata found in a file. */
data class MetaEntry(
    val category: MetaCategory,
    val name: String,
    val value: String,
)
