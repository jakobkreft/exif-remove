// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.picker

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import si.jakobkreft.exifremove.R
import si.jakobkreft.exifremove.data.AppRepository
import si.jakobkreft.exifremove.engine.MediaAccess

/**
 * Publishes the device's photos and videos to the system file picker, once
 * per cleaning template. Picking `EXIF Remove — Remove everything` in an
 * upload dialog hands the other app a cleaned copy; the original never
 * leaves this process.
 *
 * Only DocumentsUI can query this provider (it is guarded by
 * MANAGE_DOCUMENTS in the manifest); the picking app receives nothing but a
 * read grant on the single document the user chose.
 */
class CleanDocumentsProvider : DocumentsProvider() {

    private val appContext: Context
        get() = context?.applicationContext ?: throw FileNotFoundException("No context")

    override fun onCreate(): Boolean = true

    // ------------------------------------------------------------- roots

    /**
     * One root, however many templates exist. A root per template read as
     * several unrelated apps in the picker's source list; the choice of what
     * to strip is a step inside the app's own root instead, where it reads
     * as a question rather than as duplicate entries.
     */
    override fun queryRoots(projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_PROJECTION)
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, PickerIntegration.ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, PickerIntegration.ROOT_DOCUMENT_ID)
            add(Root.COLUMN_TITLE, appContext.getString(R.string.app_name))
            add(Root.COLUMN_SUMMARY, appContext.getString(R.string.picker_root_summary))
            add(Root.COLUMN_MIME_TYPES, "image/*\nvideo/*")
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD)
        }
        return cursor
    }

    // --------------------------------------------------------- documents

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        if (PickerIntegration.isRoot(documentId)) {
            addDirRow(cursor, documentId, appContext.getString(R.string.app_name))
            return cursor
        }
        val state = runBlocking { AppRepository.get(appContext).currentState() }
        val template = PickerIntegration.templateFor(documentId, state.templates)
            ?: throw FileNotFoundException("Unknown template in $documentId")

        val folderPath = PickerIntegration.folderPathOf(documentId)
        if (folderPath != null) {
            addFolderRow(cursor, documentId, folderPath, template.name)
            return cursor
        }

        val mediaId = PickerIntegration.mediaIdOf(documentId)
            ?: throw FileNotFoundException("Malformed document id $documentId")
        val item = MediaCatalog.item(appContext, mediaId)
            ?: throw FileNotFoundException("No media $mediaId")
        addFileRow(cursor, documentId, item, state.randomFileNames, state.convertUnsupported)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        val state = runBlocking { AppRepository.get(appContext).currentState() }

        // The first level is the question this app exists to ask: what
        // should come off the file? Storage is browsed one level in, under
        // the answer, so the file and the treatment are chosen together.
        if (PickerIntegration.isRoot(parentDocumentId)) {
            state.templates.forEach { template ->
                addDirRow(
                    cursor,
                    PickerIntegration.templateDocumentId(template.id),
                    template.name,
                )
            }
            cursor.extras = Bundle().apply {
                putString(
                    DocumentsContract.EXTRA_INFO,
                    appContext.getString(R.string.picker_choose_template),
                )
            }
            return cursor
        }

        val templateId = PickerIntegration.templateIdOf(parentDocumentId)
        val template = PickerIntegration.templateFor(parentDocumentId, state.templates)
            ?: throw FileNotFoundException("Unknown template in $parentDocumentId")
        val folderPath = PickerIntegration.folderPathOf(parentDocumentId)
            ?: throw FileNotFoundException("Not a folder: $parentDocumentId")

        // A content provider cannot ask for a runtime permission, and reading
        // the gallery without ACCESS_MEDIA_LOCATION would hand back files the
        // system has already half-redacted. Say so instead of looking empty.
        if (!MediaAccess.hasFullAccess(appContext) &&
            MediaAccess.requiredPermissions().isNotEmpty()
        ) {
            cursor.extras = Bundle().apply {
                putString(
                    DocumentsContract.EXTRA_INFO,
                    appContext.getString(R.string.picker_needs_permission),
                )
            }
            return cursor
        }

        val listing = MediaCatalog.children(appContext, folderPath, state.convertUnsupported)
        listing.folders.forEach { folder ->
            addFolderRow(
                cursor,
                PickerIntegration.folderDocumentId(templateId, folder.path),
                folder.path,
                template.name,
            )
        }
        listing.files.forEach { item ->
            addFileRow(
                cursor,
                PickerIntegration.documentId(templateId, item.id),
                item,
                state.randomFileNames,
                state.convertUnsupported,
            )
        }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        // Everything the provider serves hangs off the one root.
        if (PickerIntegration.isRoot(parentDocumentId)) {
            return documentId != parentDocumentId
        }
        if (PickerIntegration.templateIdOf(parentDocumentId) !=
            PickerIntegration.templateIdOf(documentId)
        ) {
            return false
        }
        val parentPath = PickerIntegration.folderPathOf(parentDocumentId) ?: return false
        // Files carry no path of their own, so anything under the same root
        // counts as a descendant of it; folders compare by prefix.
        val childPath = PickerIntegration.folderPathOf(documentId) ?: return true
        return childPath != parentPath && childPath.startsWith(parentPath)
    }

    /**
     * A folder inside a template. The top of storage has no folder name of
     * its own, so it takes the template's — which is also what the picker
     * shows in its breadcrumb, keeping the chosen treatment in view while
     * the user walks down into DCIM or Pictures.
     */
    private fun addFolderRow(
        cursor: MatrixCursor,
        documentId: String,
        path: String,
        templateName: String,
    ) {
        addDirRow(cursor, documentId, MediaCatalog.folderName(path) ?: templateName)
    }

    private fun addDirRow(cursor: MatrixCursor, documentId: String, name: String) {
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, name)
            add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            add(Document.COLUMN_FLAGS, Document.FLAG_DIR_PREFERS_GRID)
        }
    }

    /**
     * The name and type advertised here are the ones the picking app reads
     * back, so they describe the cleaned output rather than the source: a
     * converted HEIC arrives as a JPEG, and with random file names on, the
     * original name is never handed over.
     *
     * Size and last-modified are deliberately absent. The cleaned size is not
     * known until the file is cleaned, and a modification time would give
     * back the capture date this app was asked to remove.
     */
    private fun addFileRow(
        cursor: MatrixCursor,
        documentId: String,
        item: MediaCatalog.Item,
        randomFileNames: Boolean,
        convertUnsupported: Boolean,
    ) {
        val output = OutputName.of(documentId, item, randomFileNames, convertUnsupported)
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, output.name)
            add(Document.COLUMN_MIME_TYPE, output.mimeType)
            add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_THUMBNAIL)
        }
    }

    // ------------------------------------------------------------ bytes

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read-only provider")
        val file = CleanOnRead.cleanedFile(appContext, documentId, signal)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /**
     * Thumbnails are decoded from the original, which is safe: cleaning never
     * touches pixels, so the preview is the same image the picker would show
     * anywhere else — and a freshly encoded thumbnail carries no metadata.
     */
    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: CancellationSignal?,
    ): AssetFileDescriptor {
        val mediaId = PickerIntegration.mediaIdOf(documentId)
            ?: throw FileNotFoundException("Malformed document id $documentId")
        val file = Thumbnails.of(appContext, mediaId, sizeHint, signal)
        return AssetFileDescriptor(
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
            0,
            AssetFileDescriptor.UNKNOWN_LENGTH,
        )
    }

    override fun getDocumentType(documentId: String): String {
        if (PickerIntegration.isRoot(documentId)) return Document.MIME_TYPE_DIR
        if (PickerIntegration.folderPathOf(documentId) != null) return Document.MIME_TYPE_DIR
        val mediaId = PickerIntegration.mediaIdOf(documentId)
            ?: throw FileNotFoundException("Malformed document id $documentId")
        val item = MediaCatalog.item(appContext, mediaId)
            ?: throw FileNotFoundException("No media $mediaId")
        val state = runBlocking { AppRepository.get(appContext).currentState() }
        return OutputName.of(
            documentId, item, state.randomFileNames, state.convertUnsupported
        ).mimeType
    }

    companion object {
        private val ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_ICON,
            Root.COLUMN_FLAGS,
        )

        private val DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
        )
    }
}
