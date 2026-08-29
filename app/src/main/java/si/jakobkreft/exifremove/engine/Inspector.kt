// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.engine

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import si.jakobkreft.exifremove.data.Template
import java.io.File

data class Inspection(
    val fileName: String,
    val isVideo: Boolean,
    /** Metadata found in the original file. */
    val before: List<MetaEntry>,
    /**
     * The result of really cleaning a copy with the chosen template.
     * Null when the format cannot be cleaned in place — it is re-encoded
     * on share instead, which removes everything.
     */
    val report: CleaningReport?,
)

/** Runs the real cleaning engine on a copy of a file to compare before/after. */
object Inspector {

    suspend fun inspect(context: Context, uri: Uri, template: Template): Inspection? =
        withContext(Dispatchers.IO) {
            val temp = File.createTempFile("inspect", null, context.cacheDir)
            val cleaned = File.createTempFile("inspect_out", null, context.cacheDir)
            try {
                MediaAccess.openStream(context, uri)?.use { ins ->
                    temp.outputStream().use { outs -> ins.copyTo(outs) }
                } ?: return@withContext null

                val format = MetadataStripper.detectFormat(temp)
                val before = ExifProcessor.readMetadata(temp, format)
                val report = try {
                    ExifProcessor.cleanFile(temp, cleaned, template)
                } catch (e: Exception) {
                    null
                }
                Inspection(
                    fileName = ExifProcessor.queryDisplayName(context, uri) ?: "?",
                    isVideo = format == ImageFormat.MP4,
                    before = before,
                    report = report,
                )
            } catch (e: Exception) {
                null
            } finally {
                temp.delete()
                cleaned.delete()
            }
        }
}
