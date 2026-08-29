// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.engine

import android.content.Context
import java.io.File

/**
 * Cleaned files have to outlive the share intent — the receiving app reads
 * them through the FileProvider after this process is gone — so they cannot be
 * deleted on the way out. Left alone they accumulate: a copy of every image
 * the user has ever shared, sitting in the app's cache indefinitely, which is
 * exactly the material someone using this app does not want lying around.
 *
 * Each run therefore sweeps the sessions of earlier runs once the receiving
 * app has had ample time to read them.
 */
object CleanedCache {

    private const val DIR_NAME = "cleaned"
    private const val MAX_AGE_MS = 60L * 60 * 1000 // an hour

    fun sessionDir(context: Context, name: String): File =
        File(root(context), name).apply { mkdirs() }

    /** Deletes session directories older than an hour. Safe to call anytime. */
    fun prune(context: Context) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        root(context).listFiles()?.forEach { session ->
            val newest = (session.walkBottomUp().maxOfOrNull { it.lastModified() } ?: 0L)
            if (newest < cutoff) session.deleteRecursively()
        }
    }

    /** Deletes every cleaned file immediately, whatever its age. */
    fun clear(context: Context) {
        root(context).deleteRecursively()
    }

    private fun root(context: Context) = File(context.cacheDir, DIR_NAME)
}
