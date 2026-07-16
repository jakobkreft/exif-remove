package si.jakobkreft.exifremove.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.InputStream

/**
 * Since Android 11, the system strips EXIF location from media files read by
 * apps without ACCESS_MEDIA_LOCATION — through the photo picker, the
 * documents UI and even direct storage paths. With the permissions below
 * granted, a picked document can be mapped back to its MediaStore entry and
 * opened via MediaStore.setRequireOriginal(), which returns the true bytes.
 */
object MediaAccess {

    /** Providers that redact location metadata for apps without access. */
    val REDACTING_AUTHORITIES = setOf(
        "com.android.providers.media.documents",
        "com.android.externalstorage.documents",
        "media",
        "com.android.providers.media.photopicker",
        "com.google.android.apps.photos.contentprovider",
    )

    fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        else -> emptyArray() // No redaction before Android 10
    }

    fun hasFullAccess(context: Context): Boolean = requiredPermissions().all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Maps [uri] to an unredacted equivalent when possible; otherwise
     * returns it unchanged.
     */
    fun resolveForReading(context: Context, uri: Uri): Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return uri
        if (!hasFullAccess(context)) return uri
        return try {
            val mediaUri = when {
                uri.authority == MediaStore.AUTHORITY -> uri
                DocumentsContract.isDocumentUri(context, uri) ->
                    MediaStore.getMediaUri(context, uri)
                else -> null
            }
            if (mediaUri != null) MediaStore.setRequireOriginal(mediaUri) else uri
        } catch (e: Exception) {
            uri
        }
    }

    /** Opens [uri] preferring the unredacted original, falling back safely. */
    fun openStream(context: Context, uri: Uri): InputStream? = try {
        context.contentResolver.openInputStream(resolveForReading(context, uri))
    } catch (e: Exception) {
        try {
            context.contentResolver.openInputStream(uri)
        } catch (e2: Exception) {
            null
        }
    }
}
