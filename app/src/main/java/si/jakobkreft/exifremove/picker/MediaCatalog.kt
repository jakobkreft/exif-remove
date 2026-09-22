// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.picker

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

/**
 * Storage as the picker sees it: the folders people actually know —
 * DCIM/Camera, Pictures/Screenshots, Download — rather than one endless
 * chronological list.
 *
 * The tree is derived from the paths MediaStore already records, not from
 * walking the filesystem. Since Android 11 an app cannot list directories on
 * shared storage without all-files access, a permission this app has no
 * business holding; media paths give the same folders without it. Folders
 * holding nothing cleanable are therefore not shown, which is the right
 * answer anyway — there is nothing to pick in them.
 */
object MediaCatalog {

    data class Item(val id: Long, val mimeType: String, val displayName: String?)

    /** A folder in the tree. [path] is relative to storage, with a trailing slash. */
    data class Folder(val path: String, val name: String)

    /** Immediate subfolders and files of one folder. */
    data class Listing(val folders: List<Folder>, val files: List<Item>)

    /** Formats the engine can strip without re-encoding. */
    private val IN_PLACE_IMAGE_MIMES = setOf("image/jpeg", "image/png", "image/webp")

    /** Formats only reachable by decoding and re-encoding to JPEG. */
    private val CONVERTIBLE_IMAGE_MIMES = setOf(
        "image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence",
        "image/avif", "image/x-adobe-dng", "image/tiff", "image/bmp", "image/gif",
    )

    /**
     * A cap rather than the whole of storage: DocumentsUI loads the cursor in
     * one go, and a single folder holding this many files is already past
     * what anyone scrolls before using the picker's own search.
     */
    private const val MAX_ROWS = 5000

    private fun filesUri(): Uri = MediaStore.Files.getContentUri("external")

    /**
     * The path column. RELATIVE_PATH arrived in Android 10; before that the
     * absolute DATA path is all there is, and the storage root is trimmed off
     * it to get the same shape.
     */
    private val pathColumn: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.MediaColumns.RELATIVE_PATH
        } else {
            @Suppress("DEPRECATION")
            MediaStore.MediaColumns.DATA
        }

    /**
     * What sits directly inside [path] — `""` being the storage root itself.
     * Deeper entries only contribute their next path segment, so opening a
     * folder never pulls in the subtree below it.
     */
    fun children(context: Context, path: String, convertUnsupported: Boolean): Listing {
        val folders = LinkedHashMap<String, Folder>()
        val files = ArrayList<Item>()
        val mimes = supportedMimes(convertUnsupported)
        val placeholders = mimes.joinToString(",") { "?" }
        val selection = "(${MediaStore.Files.FileColumns.MIME_TYPE} IN ($placeholders) OR " +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?) AND $pathColumn LIKE ? ESCAPE '\\'"
        val args = mimes.toTypedArray() +
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString() +
            "${escapeLike(likePrefix(path))}%"

        query(context, selection, args, "${MediaStore.Files.FileColumns.DATE_ADDED} DESC") { cursor ->
            while (cursor.moveToNext() && files.size < MAX_ROWS) {
                val mime = cursor.getString(1) ?: continue
                val entryPath = relativePath(cursor.getString(3))
                if (entryPath == path) {
                    files += Item(cursor.getLong(0), mime, cursor.getString(2))
                } else if (entryPath.startsWith(path)) {
                    val segment = entryPath.removePrefix(path).substringBefore('/')
                    if (segment.isNotEmpty()) {
                        folders.getOrPut(segment) { Folder("$path$segment/", segment) }
                    }
                }
            }
        }
        return Listing(folders.values.sortedBy { it.name.lowercase() }, files)
    }

    fun item(context: Context, mediaId: Long): Item? {
        var found: Item? = null
        query(
            context,
            "${MediaStore.Files.FileColumns._ID} = ?",
            arrayOf(mediaId.toString()),
            null,
        ) { cursor ->
            if (cursor.moveToFirst()) {
                val mime = cursor.getString(1)
                if (mime != null) found = Item(cursor.getLong(0), mime, cursor.getString(2))
            }
        }
        return found
    }

    /**
     * The typed MediaStore row — images under Images, videos under Video.
     * The generic files collection would do for reading bytes, but
     * `setRequireOriginal` and `loadThumbnail` are both specified against the
     * typed collections, and those are exactly the two calls this app needs.
     */
    fun uri(item: Item): Uri = Uri.withAppendedPath(
        if (isVideo(item.mimeType)) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        },
        item.id.toString(),
    )

    fun isVideo(mimeType: String): Boolean = mimeType.startsWith("video/")

    /** True when cleaning this type means re-encoding it to JPEG. */
    fun needsConversion(mimeType: String): Boolean = mimeType in CONVERTIBLE_IMAGE_MIMES

    /** The name shown for a folder document, storage root included. */
    fun folderName(path: String): String? =
        path.trimEnd('/').substringAfterLast('/').ifEmpty { null }

    /**
     * Normalises a stored path to `Dir/Sub/` form. Before Android 10 the
     * column is absolute and carries the file name too, so both the storage
     * root and the name are trimmed away.
     */
    internal fun relativePath(stored: String?): String {
        if (stored.isNullOrEmpty()) return ""
        val withoutRoot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            stored
        } else {
            @Suppress("DEPRECATION")
            val root = Environment.getExternalStorageDirectory().path.trimEnd('/') + "/"
            stored.removePrefix(root).substringBeforeLast('/', "")
        }
        val trimmed = withoutRoot.trim('/')
        return if (trimmed.isEmpty()) "" else "$trimmed/"
    }

    /** Pre-Android 10 the column holds an absolute path, so the LIKE must too. */
    private fun likePrefix(path: String): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            path
        } else {
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory().path.trimEnd('/') + "/" + path
        }

    /** A folder named `100%_backup` would otherwise match half of storage. */
    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun supportedMimes(convertUnsupported: Boolean): List<String> =
        if (convertUnsupported) {
            (IN_PLACE_IMAGE_MIMES + CONVERTIBLE_IMAGE_MIMES).toList()
        } else {
            // Offering a file the engine would refuse only produces a dead
            // entry in someone's upload dialog.
            IN_PLACE_IMAGE_MIMES.toList()
        }

    private inline fun query(
        context: Context,
        selection: String,
        args: Array<String>,
        sortOrder: String?,
        body: (android.database.Cursor) -> Unit,
    ) {
        try {
            context.contentResolver.query(
                filesUri(),
                arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.MIME_TYPE,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    pathColumn,
                ),
                selection,
                args,
                sortOrder,
            )?.use(body)
        } catch (e: Exception) {
            // A missing or unreadable volume just means an empty listing.
        }
    }
}
