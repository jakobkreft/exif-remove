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
    /** Metadata left after cleaning with the template; null = unsupported format. */
    val after: List<MetaEntry>?,
)

/** Runs the real cleaning engine on a copy of a file to compare before/after. */
object Inspector {

    suspend fun inspect(context: Context, uri: Uri, template: Template): Inspection? =
        withContext(Dispatchers.IO) {
            val temp = File.createTempFile("inspect", null, context.cacheDir)
            val cleaned = File.createTempFile("inspect_out", null, context.cacheDir)
            try {
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    temp.outputStream().use { outs -> ins.copyTo(outs) }
                } ?: return@withContext null

                val format = MetadataStripper.detectFormat(temp)
                val isVideo = format == ImageFormat.MP4
                val before = try {
                    if (isVideo) Mp4MetadataReader.read(temp) else ImageMetadataReader.read(temp)
                } catch (e: Exception) {
                    emptyList()
                }
                val after = try {
                    if (ExifProcessor.cleanFile(temp, cleaned, template)) {
                        if (isVideo) Mp4MetadataReader.read(cleaned)
                        else ImageMetadataReader.read(cleaned)
                    } else null
                } catch (e: Exception) {
                    null
                }
                Inspection(
                    fileName = ExifProcessor.queryDisplayName(context, uri) ?: "?",
                    isVideo = isVideo,
                    before = before,
                    after = after,
                )
            } catch (e: Exception) {
                null
            } finally {
                temp.delete()
                cleaned.delete()
            }
        }
}
