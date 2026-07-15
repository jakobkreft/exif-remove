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

enum class ProcessError { UNSUPPORTED_FORMAT, UNREADABLE }

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
        val sessionDir = File(File(context.cacheDir, "cleaned"), UUID.randomUUID().toString())
        sessionDir.mkdirs()
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
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    temp.outputStream().use { outs -> ins.copyTo(outs) }
                } ?: return ProcessedImage(null, originalName ?: "?", "", ProcessError.UNREADABLE)
            } catch (e: Exception) {
                return ProcessedImage(null, originalName ?: "?", "", ProcessError.UNREADABLE)
            }

            val format = MetadataStripper.detectFormat(temp)
            if (format == ImageFormat.MP4) {
                return processVideo(context, uri, temp, originalName, template, options, outDir)
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
                    MetadataStripper.strip(format, temp, outFile)
                    if (template.needsRewrite) {
                        rewriteMetadata(temp, outFile, template)
                    }
                    ProcessedImage(outFile, outName, mime)
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
            temp.inputStream().use { ins ->
                outFile.outputStream().use { outs -> ins.copyTo(outs) }
            }
            Mp4Scrubber.scrub(outFile, template)
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
            if (template.needsRewrite) rewriteMetadata(source, outFile, template)
        } catch (ignored: Exception) {
            // The JPEG itself is clean; kept metadata is best-effort here.
        }
        return ProcessedImage(outFile, outName, "image/jpeg")
    }

    // ------------------------------------------------------ metadata rewrite

    private val DATE_TAGS = setOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
    )

    private val CAMERA_TAGS = setOf(
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

    /**
     * Tags never copied when keeping "other" data: XMP (stripped by design),
     * thumbnail pointers and structural fields describing the encoded stream.
     */
    private val EXCLUDED_TAGS = setOf(
        ExifInterface.TAG_XMP,
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT,
        ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT_LENGTH,
        ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH,
        ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH,
        ExifInterface.TAG_THUMBNAIL_ORIENTATION,
        ExifInterface.TAG_IMAGE_WIDTH,
        ExifInterface.TAG_IMAGE_LENGTH,
        ExifInterface.TAG_BITS_PER_SAMPLE,
        ExifInterface.TAG_COMPRESSION,
        ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION,
        ExifInterface.TAG_SAMPLES_PER_PIXEL,
        ExifInterface.TAG_PLANAR_CONFIGURATION,
        ExifInterface.TAG_ROWS_PER_STRIP,
        ExifInterface.TAG_STRIP_BYTE_COUNTS,
        ExifInterface.TAG_STRIP_OFFSETS,
        ExifInterface.TAG_Y_CB_CR_COEFFICIENTS,
        ExifInterface.TAG_Y_CB_CR_POSITIONING,
        ExifInterface.TAG_Y_CB_CR_SUB_SAMPLING,
    )

    /** All TAG_* constants of ExifInterface, discovered once via reflection. */
    private val ALL_TAGS: List<String> by lazy {
        ExifInterface::class.java.fields
            .filter {
                Modifier.isStatic(it.modifiers) && it.type == String::class.java &&
                    it.name.startsWith("TAG_")
            }
            .mapNotNull { it.get(null) as? String }
            .distinct()
    }

    private fun rewriteMetadata(original: File, cleaned: File, template: Template) {
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
            RuleAction.KEEP -> ALL_TAGS.filter { it.startsWith("GPS") }.forEach(::copyTag)
            RuleAction.RANDOMIZE -> {
                dst.setLatLong(Random.nextDouble(-55.0, 70.0), Random.nextDouble(-180.0, 180.0))
                dirty = true
            }
            RuleAction.REMOVE -> Unit
        }

        when (template.dateTime) {
            RuleAction.KEEP -> DATE_TAGS.forEach(::copyTag)
            RuleAction.RANDOMIZE -> {
                val past = System.currentTimeMillis() -
                    Random.nextLong(0L, 20L * 365 * 24 * 60 * 60 * 1000)
                val formatted = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                    .format(Date(past))
                dst.setAttribute(ExifInterface.TAG_DATETIME, formatted)
                dst.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, formatted)
                dst.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, formatted)
                dirty = true
            }
            RuleAction.REMOVE -> Unit
        }

        if (template.cameraInfo == RuleAction.KEEP) {
            CAMERA_TAGS.forEach(::copyTag)
        }

        if (template.orientation == RuleAction.KEEP) {
            copyTag(ExifInterface.TAG_ORIENTATION)
        }

        if (template.otherExif == RuleAction.KEEP) {
            ALL_TAGS
                .filterNot { it.startsWith("GPS") }
                .filterNot { it in DATE_TAGS || it in CAMERA_TAGS || it in EXCLUDED_TAGS }
                .forEach(::copyTag)
        }

        if (dirty) {
            try {
                dst.saveAttributes()
            } catch (e: IOException) {
                // Saving can fail for formats without write support; the file
                // stays fully stripped, which is the safe direction.
            }
        }
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

    private fun queryDisplayName(context: Context, uri: Uri): String? {
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
