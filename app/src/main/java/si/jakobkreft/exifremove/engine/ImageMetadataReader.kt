package si.jakobkreft.exifremove.engine

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.Locale

/** Reads image EXIF metadata (JPEG/PNG/WebP/HEIC) for display. */
object ImageMetadataReader {

    /**
     * Tags hidden from the viewer: values ExifInterface synthesizes from the
     * image stream itself (dimensions, JFIF resolution) and structural fields
     * that describe encoding rather than carry information about the user.
     */
    private val HIDDEN_TAGS = setOf(
        ExifInterface.TAG_IMAGE_WIDTH,
        ExifInterface.TAG_IMAGE_LENGTH,
        ExifInterface.TAG_PIXEL_X_DIMENSION,
        ExifInterface.TAG_PIXEL_Y_DIMENSION,
        ExifInterface.TAG_BITS_PER_SAMPLE,
        ExifInterface.TAG_COMPRESSION,
        ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION,
        ExifInterface.TAG_SAMPLES_PER_PIXEL,
        ExifInterface.TAG_PLANAR_CONFIGURATION,
        ExifInterface.TAG_ROWS_PER_STRIP,
        ExifInterface.TAG_STRIP_BYTE_COUNTS,
        ExifInterface.TAG_STRIP_OFFSETS,
        ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT,
        ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT_LENGTH,
        ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH,
        ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH,
        ExifInterface.TAG_THUMBNAIL_ORIENTATION,
        ExifInterface.TAG_X_RESOLUTION,
        ExifInterface.TAG_Y_RESOLUTION,
        ExifInterface.TAG_RESOLUTION_UNIT,
        ExifInterface.TAG_Y_CB_CR_COEFFICIENTS,
        ExifInterface.TAG_Y_CB_CR_POSITIONING,
        ExifInterface.TAG_Y_CB_CR_SUB_SAMPLING,
        ExifInterface.TAG_COMPONENTS_CONFIGURATION,
        ExifInterface.TAG_EXIF_VERSION,
        ExifInterface.TAG_FLASHPIX_VERSION,
        ExifInterface.TAG_INTEROPERABILITY_INDEX,
        ExifInterface.TAG_GPS_VERSION_ID,
    )

    /** GPS tags replaced by the formatted "GPS position"/"GPS altitude" rows. */
    private val FORMATTED_GPS_TAGS = setOf(
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
    )

    fun read(file: File): List<MetaEntry> {
        val exif = ExifInterface(file.absolutePath)
        val entries = mutableListOf<MetaEntry>()

        exif.latLong?.let { coords ->
            entries += MetaEntry(
                MetaCategory.LOCATION,
                "GPS position",
                formatDms(coords[0], coords[1]),
            )
            val altitude = exif.getAltitude(Double.NaN)
            if (!altitude.isNaN()) {
                entries += MetaEntry(
                    MetaCategory.LOCATION,
                    "GPS altitude",
                    String.format(Locale.US, "%.1f m", altitude),
                )
            }
        }

        for (tag in ExifProcessor.ALL_TAGS) {
            if (tag in HIDDEN_TAGS || tag in FORMATTED_GPS_TAGS) continue
            val value = try {
                exif.getAttribute(tag) ?: continue
            } catch (e: Exception) {
                continue
            }
            // Parser defaults, not file contents ("0" = undefined/unknown)
            if (tag == ExifInterface.TAG_ORIENTATION && value == "0") continue
            if (tag == ExifInterface.TAG_LIGHT_SOURCE && value == "0") continue
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

    /** 43.66407, 15.62384 → "43° 39′ 50.65″ N, 15° 37′ 25.82″ E" */
    fun formatDms(latitude: Double, longitude: Double): String =
        "${dmsPart(latitude, 'N', 'S')}, ${dmsPart(longitude, 'E', 'W')}"

    private fun dmsPart(value: Double, positive: Char, negative: Char): String {
        val absolute = kotlin.math.abs(value)
        val degrees = absolute.toInt()
        val minutesFull = (absolute - degrees) * 60
        val minutes = minutesFull.toInt()
        val seconds = (minutesFull - minutes) * 60
        return String.format(
            Locale.US, "%d° %d′ %.2f″ %c",
            degrees, minutes, seconds, if (value >= 0) positive else negative,
        )
    }

    /** "SubSecTimeOriginal" → "Sub Sec Time Original", "GPSLatitude" → "GPS Latitude" */
    private fun prettify(tag: String): String =
        tag.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")
            .replace(Regex("(?<=[A-Z])(?=[A-Z][a-z])"), " ")
}
