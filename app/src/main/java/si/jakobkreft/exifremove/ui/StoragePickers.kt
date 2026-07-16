package si.jakobkreft.exifremove.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Android redacts EXIF location from files served by its media providers
 * (the photo picker, and the "Recent/Images" sections of the documents UI).
 * Only real file access — browsing device storage — returns the original
 * bytes. These contracts open the picker directly in DCIM on device storage
 * so the default path is the unredacted one.
 */
private val INITIAL_URI: Uri = DocumentsContract.buildDocumentUri(
    "com.android.externalstorage.documents",
    "primary:DCIM",
)

/** Providers that redact location metadata before the app sees the file. */
val REDACTING_AUTHORITIES = setOf(
    "com.android.providers.media.documents",
    "media",
    "com.android.providers.media.photopicker",
    "com.google.android.apps.photos.contentprovider",
)

class OpenDocumentInStorage : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input)
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI, INITIAL_URI)
}

class OpenMultipleDocumentsInStorage : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input)
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI, INITIAL_URI)
}
