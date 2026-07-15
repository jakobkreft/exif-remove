package si.jakobkreft.exifremove.engine

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.Locale

/** Reads image EXIF metadata (JPEG/PNG/WebP/HEIC) for display. */
object ImageMetadataReader {

    fun read(file: File): List<MetaEntry> {
        val exif = ExifInterface(file.absolutePath)
        val entries = mutableListOf<MetaEntry>()

        exif.latLong?.let { coords ->
            entries += MetaEntry(
                MetaCategory.LOCATION,
                "Coordinates",
                String.format(Locale.US, "%.5f, %.5f", coords[0], coords[1]),
            )
        }

        for (tag in ExifProcessor.ALL_TAGS) {
            val value = try {
                exif.getAttribute(tag) ?: continue
            } catch (e: Exception) {
                continue
            }
            if (tag == ExifInterface.TAG_XMP) {
                entries += MetaEntry(MetaCategory.OTHER, "XMP packet", "${value.length} chars")
                continue
            }
            val category = when {
                tag.startsWith("GPS") -> MetaCategory.LOCATION
                tag in ExifProcessor.DATE_TAGS -> MetaCategory.DATE
                tag in ExifProcessor.CAMERA_TAGS -> MetaCategory.CAMERA
                tag == ExifInterface.TAG_ORIENTATION -> MetaCategory.ORIENTATION
                else -> MetaCategory.OTHER
            }
            entries += MetaEntry(category, prettify(tag), value.take(120))
        }
        return entries
    }

    /** "SubSecTimeOriginal" → "Sub Sec Time Original", "GPSLatitude" → "GPS Latitude" */
    private fun prettify(tag: String): String =
        tag.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")
            .replace(Regex("(?<=[A-Z])(?=[A-Z][a-z])"), " ")
}
