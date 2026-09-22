// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.picker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.os.Build
import android.os.CancellationSignal
import android.util.Size
import java.io.File
import java.io.FileNotFoundException
import si.jakobkreft.exifremove.engine.CleanedCache

/**
 * Previews for the picker grid. Re-encoded from the decoded bitmap, so a
 * thumbnail never carries the metadata of the file it previews, and small
 * enough that the grid does not decode full-size images.
 */
object Thumbnails {

    private const val SESSION = "thumbs"

    fun of(
        context: Context,
        mediaId: Long,
        sizeHint: Point,
        signal: CancellationSignal?,
    ): File {
        val width = sizeHint.x.coerceIn(96, 1024)
        val height = sizeHint.y.coerceIn(96, 1024)
        val dir = CleanedCache.sessionDir(context, SESSION)
        val cached = File(dir, "$mediaId-${width}x$height.jpg")
        if (cached.isFile) return cached

        val item = MediaCatalog.item(context, mediaId)
            ?: throw FileNotFoundException("No media $mediaId")
        val bitmap = load(context, item, width, height, signal)
            ?: throw FileNotFoundException("No thumbnail for $mediaId")
        try {
            cached.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
        } finally {
            bitmap.recycle()
        }
        return cached
    }

    private fun load(
        context: Context,
        item: MediaCatalog.Item,
        width: Int,
        height: Int,
        signal: CancellationSignal?,
    ): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(
                MediaCatalog.uri(item), Size(width, height), signal
            )
        } else {
            decodeSampled(context, item, width, height)
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Before Android 10 there is no loadThumbnail; decoding the bounds first
     * keeps a 50 MP photo from being read into memory for a grid cell.
     */
    private fun decodeSampled(
        context: Context,
        item: MediaCatalog.Item,
        width: Int,
        height: Int,
    ): Bitmap? {
        val uri = MediaCatalog.uri(item)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null
        var sample = 1
        while (bounds.outWidth / sample > width * 2 && bounds.outHeight / sample > height * 2) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }
}
