package si.jakobkreft.exifremove.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import si.jakobkreft.exifremove.data.RuleAction
import si.jakobkreft.exifremove.data.Template
import java.io.File
import java.io.IOException
import java.lang.reflect.Modifier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

data class ProcessedImage(
    val file: File?,
    val displayName: String,
    val mimeType: String,
    val error: ProcessError? = null,
)

enum class ProcessError { UNSUPPORTED_FORMAT, UNREADABLE, NOT_PROVABLY_CLEAN }

class ProcessorOptions(
    val randomFileNames: Boolean,
    val convertUnsupported: Boolean,
)

object ExifProcessor {

    suspend fun processAll(
        context: Context,
        uris: List<Uri>,
        template: Template,
        options: ProcessorOptions,
    ): List<ProcessedImage> = withContext(Dispatchers.IO) {
        CleanedCache.prune(context)
        val sessionDir = CleanedCache.sessionDir(context, UUID.randomUUID().toString())
        uris.map { uri -> processOne(context, uri, template, options, sessionDir) }
    }

    private fun processOne(
        context: Context,
        uri: Uri,
        template: Template,
        options: ProcessorOptions,
        outDir: File,
    ): ProcessedImage {
        val originalName = queryDisplayName(context, uri)
        val temp = File.createTempFile("original", null, context.cacheDir)
        try {
            try {
                MediaAccess.openStream(context, uri)?.use { ins ->
                    temp.outputStream().use { outs -> ins.copyTo(outs) }
                } ?: return ProcessedImage(null, originalName ?: "?", "", ProcessError.UNREADABLE)
            } catch (e: Exception) {
                return ProcessedImage(null, originalName ?: "?", "", ProcessError.UNREADABLE)
            }

            val format = MetadataStripper.detectFormat(temp)
            if (format == ImageFormat.MP4) {
                return processVideo(context, uri, temp, originalName, template, options, outDir)
            }
            // HEIC/AVIF keep their metadata in ISO-BMFF item boxes that no
            // in-place strip here understands, so they are re-encoded from
            // decoded pixels — the one route that is clean by construction.
            if (format == ImageFormat.HEIF) {
                return if (options.convertUnsupported) {
                    convertToJpeg(temp, originalName, template, options, outDir)
                } else {
                    ProcessedImage(null, originalName ?: "?", "", ProcessError.UNSUPPORTED_FORMAT)
                }
            }
            return if (format != ImageFormat.UNSUPPORTED) {
                val (ext, mime) = when (format) {
                    ImageFormat.JPEG -> "jpg" to "image/jpeg"
                    ImageFormat.PNG -> "png" to "image/png"
                    ImageFormat.WEBP -> "webp" to "image/webp"
                    else -> throw IllegalStateException()
                }
                val outName = outputName(originalName, ext, options)
                val outFile = File(outDir, outName)
                try {
                    cleanFile(temp, outFile, template)
                    ProcessedImage(outFile, outName, mime)
                } catch (e: VerificationException) {
                    // Never hand back a file that looks cleaned but isn't.
                    outFile.delete()
                    ProcessedImage(
                        null, originalName ?: outName, mime, ProcessError.NOT_PROVABLY_CLEAN
                    )
                } catch (e: Exception) {
                    outFile.delete()
                    ProcessedImage(null, originalName ?: outName, mime, ProcessError.UNREADABLE)
                }
            } else if (options.convertUnsupported) {
                convertToJpeg(temp, originalName, template, options, outDir)
            } else {
                ProcessedImage(null, originalName ?: "?", "", ProcessError.UNSUPPORTED_FORMAT)
            }
        } finally {
            temp.delete()
        }
    }

    /** Copies the video and scrubs its metadata boxes in place. */
    private fun processVideo(
        context: Context,
        uri: Uri,
        temp: File,
        originalName: String?,
        template: Template,
        options: ProcessorOptions,
        outDir: File,
    ): ProcessedImage {
        val nameExt = originalName?.substringAfterLast('.', "")?.lowercase()
        val ext = if (nameExt in setOf("mp4", "mov", "3gp", "m4v")) nameExt!! else "mp4"
        val resolverMime = try {
            context.contentResolver.getType(uri)
        } catch (e: Exception) {
            null
        }
        val mime = if (resolverMime?.startsWith("video/") == true) resolverMime else "video/mp4"
        val outName = outputName(originalName, ext, options, prefix = "VID_")
        val outFile = File(outDir, outName)
        return try {
            cleanFile(temp, outFile, template)
            ProcessedImage(outFile, outName, mime)
        } catch (e: Exception) {
            outFile.delete()
            ProcessedImage(null, originalName ?: outName, mime, ProcessError.UNREADABLE)
        }
    }

    /** Decodes an unsupported format (HEIC, …) and re-encodes it as a clean JPEG. */
    private fun convertToJpeg(
        source: File,
        originalName: String?,
        template: Template,
        options: ProcessorOptions,
        outDir: File,
    ): ProcessedImage {
        val bitmap: Bitmap = BitmapFactory.decodeFile(source.absolutePath)
            ?: return ProcessedImage(null, originalName ?: "?", "", ProcessError.UNSUPPORTED_FORMAT)
        val outName = outputName(originalName?.let { stripExtension(it) + ".jpg" }, "jpg", options)
        val outFile = File(outDir, outName)
        try {
            outFile.outputStream().buffered().use { outs ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outs)
            }
        } finally {
            bitmap.recycle()
        }
        try {
            copyBackKeptTags(source, outFile, template)
        } catch (ignored: Exception) {
            // The JPEG itself is clean; kept metadata is best-effort here.
        }
        // The pixels were re-encoded, so any EXIF present is what we just
        // wrote — but still prove nothing else came along with it.
        return try {
            OutputVerifier.verify(ImageFormat.JPEG, outFile, keepExif = true)
            ProcessedImage(outFile, outName, "image/jpeg")
        } catch (e: VerificationException) {
            outFile.delete()
            ProcessedImage(null, originalName ?: outName, "", ProcessError.NOT_PROVABLY_CLEAN)
        }
    }

    // ------------------------------------------------------ metadata rewrite

    internal val DATE_TAGS = setOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        // The GPS fix moment is time information: governed by the date rule,
        // not the location rule.
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_TIMESTAMP,
    )

    /** GPS tags that carry location (GPS time/date belong to DATE_TAGS). */
    private fun isLocationTag(tag: String): Boolean =
        tag.startsWith("GPS") && tag !in DATE_TAGS

    internal val CAMERA_TAGS = setOf(
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_IMAGE_UNIQUE_ID,
        ExifInterface.TAG_CAMERA_OWNER_NAME,
        ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SERIAL_NUMBER,
        ExifInterface.TAG_MAKER_NOTE,
    )

    /** All TAG_* constants of ExifInterface, discovered once via reflection. */
    internal val ALL_TAGS: List<String> by lazy {
        ExifInterface::class.java.fields
            .filter {
                Modifier.isStatic(it.modifiers) && it.type == String::class.java &&
                    it.name.startsWith("TAG_")
            }
            .mapNotNull { it.get(null) as? String }
            .distinct()
    }

    /**
     * Path A (template keeps "other" metadata): the EXIF block survived the
     * strip; delete or randomize only the targeted categories in place so
     * every kept tag stays byte-for-byte identical.
     */
    private fun editExifSurgically(original: File, cleaned: File, template: Template) {
        val exif = ExifInterface(cleaned.absolutePath)

        fun clearTag(tag: String) {
            try {
                exif.setAttribute(tag, null)
            } catch (ignored: Exception) {
                // Not writable; nothing to clear.
            }
        }

        when (template.gps) {
            RuleAction.KEEP -> Unit
            RuleAction.REMOVE -> ALL_TAGS.filter(::isLocationTag).forEach(::clearTag)
            RuleAction.RANDOMIZE -> {
                ALL_TAGS.filter(::isLocationTag).forEach(::clearTag)
                exif.setLatLong(randomLatitude(), randomLongitude())
            }
        }

        when (template.dateTime) {
            RuleAction.KEEP -> Unit
            RuleAction.REMOVE -> DATE_TAGS.forEach(::clearTag)
            RuleAction.RANDOMIZE -> {
                DATE_TAGS.forEach(::clearTag)
                setRandomDateTime(exif)
            }
        }

        if (template.cameraInfo == RuleAction.REMOVE) {
            CAMERA_TAGS.forEach(::clearTag)
        }

        // Always removed, even in keep-other mode: the embedded thumbnail
        // (a smaller copy of the possibly-uncleaned image).
        clearTag(ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT)
        clearTag(ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT_LENGTH)
        clearTag(ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH)
        clearTag(ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH)
        clearTag(ExifInterface.TAG_THUMBNAIL_ORIENTATION)
        clearTag(ExifInterface.TAG_XMP)
        disableThumbnailWriteback(exif)

        // ExifInterface synthesizes these while parsing any file (image size
        // from the stream, "0" defaults); never write them back. A genuinely
        // stored light source has a non-zero value and is kept.
        clearTag(ExifInterface.TAG_IMAGE_WIDTH)
        clearTag(ExifInterface.TAG_IMAGE_LENGTH)
        if (exif.getAttribute(ExifInterface.TAG_LIGHT_SOURCE) == "0") {
            clearTag(ExifInterface.TAG_LIGHT_SOURCE)
        }

        try {
            exif.saveAttributes()
        } catch (e: IOException) {
            fullStripFallback(cleaned)
        }

        // Belt and braces: if the thumbnail still survived, strip fully.
        if (ExifInterface(cleaned.absolutePath).hasThumbnail()) {
            fullStripFallback(cleaned)
            copyBackKeptTags(original, cleaned, template)
        }
    }

    private val SYNTHESIZED_TAGS = setOf(
        ExifInterface.TAG_IMAGE_WIDTH,
        ExifInterface.TAG_IMAGE_LENGTH,
        ExifInterface.TAG_LIGHT_SOURCE,
    )

    /**
     * saveAttributes() re-embeds the thumbnail it parsed even after its
     * pointer tags are cleared; the flag is private, so flip it via
     * reflection (kept by proguard-rules.pro).
     */
    private fun disableThumbnailWriteback(exif: ExifInterface) {
        try {
            val field = ExifInterface::class.java.getDeclaredField("mHasThumbnail")
            field.isAccessible = true
            field.setBoolean(exif, false)
        } catch (ignored: Exception) {
            // Handled by the hasThumbnail() fallback after saving.
        }
    }

    private fun fullStripFallback(cleaned: File) {
        val fallback = File(cleaned.parentFile, cleaned.name + ".tmp")
        MetadataStripper.strip(MetadataStripper.detectFormat(cleaned), cleaned, fallback)
        fallback.copyTo(cleaned, overwrite = true)
        fallback.delete()
    }

    /**
     * Path B (template removes "other" metadata): everything was stripped;
     * copy back only what the template keeps. Orientation is always restored.
     */
    private fun copyBackKeptTags(original: File, cleaned: File, template: Template) {
        val src = ExifInterface(original.absolutePath)
        val dst = ExifInterface(cleaned.absolutePath)
        var dirty = false

        fun copyTag(tag: String) {
            val value = src.getAttribute(tag) ?: return
            try {
                dst.setAttribute(tag, value)
                dirty = true
            } catch (ignored: Exception) {
                // Some tags are not writable; skip them.
            }
        }

        when (template.gps) {
            RuleAction.KEEP -> ALL_TAGS.filter(::isLocationTag).forEach(::copyTag)
            RuleAction.RANDOMIZE -> {
                dst.setLatLong(randomLatitude(), randomLongitude())
                dirty = true
            }
            RuleAction.REMOVE -> Unit
        }

        when (template.dateTime) {
            RuleAction.KEEP -> DATE_TAGS.forEach(::copyTag)
            RuleAction.RANDOMIZE -> {
                setRandomDateTime(dst)
                dirty = true
            }
            RuleAction.REMOVE -> Unit
        }

        if (template.cameraInfo == RuleAction.KEEP) {
            CAMERA_TAGS.forEach(::copyTag)
        }

        // Orientation is always restored ("0" is the parser default, not data)
        val orientation = src.getAttribute(ExifInterface.TAG_ORIENTATION)
        if (orientation != null && orientation != "0") {
            copyTag(ExifInterface.TAG_ORIENTATION)
        }

        if (dirty) {
            // The destination was fully stripped, so anything present now was
            // synthesized by ExifInterface while parsing; don't write it back.
            for (tag in SYNTHESIZED_TAGS) {
                try {
                    dst.setAttribute(tag, null)
                } catch (ignored: Exception) {
                }
            }
            try {
                dst.saveAttributes()
            } catch (e: IOException) {
                // Saving can fail for formats without write support; the file
                // stays fully stripped, which is the safe direction.
            }
        }
    }

    private fun randomLatitude() = Random.nextDouble(-55.0, 70.0)
    private fun randomLongitude() = Random.nextDouble(-180.0, 180.0)

    private fun setRandomDateTime(exif: ExifInterface) {
        val past = System.currentTimeMillis() -
            Random.nextLong(0L, 20L * 365 * 24 * 60 * 60 * 1000)
        val formatted = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(past))
        exif.setAttribute(ExifInterface.TAG_DATETIME, formatted)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, formatted)
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, formatted)
    }

    /**
     * Cleans a media file on disk — the single engine behind both the share
     * flow and the inspector. Returns false when the format is unsupported.
     */
    internal fun cleanFile(source: File, dest: File, template: Template): Boolean {
        val format = MetadataStripper.detectFormat(source)
        return when {
            format == ImageFormat.MP4 -> {
                source.copyTo(dest, overwrite = true)
                Mp4Scrubber.scrub(dest, template)
                true
            }
            format == ImageFormat.HEIF || format == ImageFormat.UNSUPPORTED -> false
            else -> {
                val surgical = template.otherExif == RuleAction.KEEP
                MetadataStripper.strip(format, source, dest, keepExif = surgical)
                val exifWrittenBack = template.needsRewrite || hasOrientation(source)
                if (surgical) {
                    editExifSurgically(source, dest, template)
                } else if (exifWrittenBack) {
                    copyBackKeptTags(source, dest, template)
                }
                // Last line of defence: prove the produced file is metadata-free
                // rather than trusting that the strip did what it intended.
                OutputVerifier.verify(format, dest, keepExif = surgical || exifWrittenBack)
                true
            }
        }
    }

    private fun hasOrientation(source: File): Boolean = try {
        (ExifInterface(source.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)) != 0
    } catch (e: Exception) {
        false
    }

    // -------------------------------------------------------------- helpers

    private fun outputName(
        originalName: String?,
        ext: String,
        options: ProcessorOptions,
        prefix: String = "IMG_",
    ): String {
        if (options.randomFileNames || originalName.isNullOrBlank()) {
            val random = List(8) { "0123456789abcdef".random() }.joinToString("")
            return "$prefix$random.$ext"
        }
        val sanitized = originalName.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        return if (sanitized.contains('.')) sanitized else "$sanitized.$ext"
    }

    private fun stripExtension(name: String): String =
        name.substringBeforeLast('.', name)

    internal fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        } catch (e: Exception) {
            uri.lastPathSegment
        }
    }
}
