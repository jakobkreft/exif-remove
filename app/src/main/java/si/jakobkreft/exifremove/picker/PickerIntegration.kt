// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.picker

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.DocumentsContract
import si.jakobkreft.exifremove.data.Template

/**
 * The app's entry in the system file picker. Any app or website asking for a
 * file goes through that picker, so appearing there is the one way to hand a
 * cleaned copy straight into an upload without a detour through the share
 * sheet and back.
 *
 * Two components answer for it, because the system picker treats the two
 * ways of asking for a file differently: [CleanDocumentsProvider] is the
 * storage root that ACTION_OPEN_DOCUMENT callers see, and
 * [PickCleanActivity] is the source that ACTION_GET_CONTENT callers see.
 *
 * Either way the asking app receives only a cleaned copy, never a handle on
 * the original.
 */
object PickerIntegration {

    const val AUTHORITY = "si.jakobkreft.exifremove.documents"

    /** The one root the picker lists the app under. */
    const val ROOT_ID = "exifremove"

    /**
     * The document above every template. It is not a template id itself, and
     * cannot collide with one: built-in ids are hyphenated words and custom
     * ones are UUIDs, neither of which starts with `@`.
     */
    const val ROOT_DOCUMENT_ID = "@root"

    /**
     * A document id is `@root` for the root, `<templateId>` for a template's
     * view of storage, `<templateId>/d/<path>/` for a folder inside it and
     * `<templateId>/f/<mediaId>` for a file. The kind is spelled out rather
     * than inferred, so a folder called `42` cannot be read as a media id.
     */
    fun templateDocumentId(templateId: String): String = templateId

    fun folderDocumentId(templateId: String, path: String): String =
        if (path.isEmpty()) templateId else "$templateId/d/$path"

    fun documentId(templateId: String, mediaId: Long): String = "$templateId/f/$mediaId"

    fun templateIdOf(documentId: String): String = documentId.substringBefore('/')

    /** Null unless this id names a file. */
    fun mediaIdOf(documentId: String): Long? {
        val rest = documentId.substringAfter('/', "")
        if (!rest.startsWith("f/")) return null
        return rest.removePrefix("f/").toLongOrNull()
    }

    /**
     * The folder an id stands for, `""` being the top of storage; null when
     * the id names a file, or the root, which stands for no folder at all.
     */
    fun folderPathOf(documentId: String): String? {
        if (isRoot(documentId)) return null
        val rest = documentId.substringAfter('/', "")
        return when {
            rest.isEmpty() -> ""
            rest.startsWith("d/") -> rest.removePrefix("d/")
            else -> null
        }
    }

    fun isRoot(documentId: String): Boolean = documentId == ROOT_DOCUMENT_ID

    /** True for a template's own document, the level between root and storage. */
    fun isTemplate(documentId: String): Boolean =
        !isRoot(documentId) && !documentId.contains('/')

    fun templateFor(documentId: String, templates: List<Template>): Template? {
        if (isRoot(documentId)) return null
        val id = templateIdOf(documentId)
        return templates.firstOrNull { it.id == id }
    }

    /**
     * Turning the integration off disables the provider component outright,
     * which is what removes the entry from the picker; a preference alone
     * would leave an empty source sitting in the list.
     */
    /**
     * The storage root, on or off. Disabling the component outright is what
     * removes the entry from the picker; a preference alone would leave an
     * empty source sitting in the list.
     */
    fun setRootEnabled(context: Context, enabled: Boolean) {
        setComponentEnabled(context, CleanDocumentsProvider::class.java, enabled)
        notifyRootsChanged(context)
    }

    /**
     * The entry that opens the picker itself, on or off.
     *
     * Kept separate from the root because some pickers show an app once:
     * where a package already contributes a storage root, its
     * ACTION_GET_CONTENT entry is dropped from the source list. Turning the
     * root off is then the only way to reach this one.
     */
    fun setBrowseEnabled(context: Context, enabled: Boolean) {
        setComponentEnabled(context, PickCleanActivity::class.java, enabled)
    }

    private fun setComponentEnabled(context: Context, cls: Class<*>, enabled: Boolean) {
        val component = ComponentName(context, cls)
        val target = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        if (context.packageManager.getComponentEnabledSetting(component) != target) {
            context.packageManager.setComponentEnabledSetting(
                component, target, PackageManager.DONT_KILL_APP
            )
        }
    }

    /** Re-reads the root list, so template edits show up in the picker at once. */
    fun notifyRootsChanged(context: Context) {
        try {
            context.contentResolver.notifyChange(
                DocumentsContract.buildRootsUri(AUTHORITY), null
            )
        } catch (e: Exception) {
            // The picker simply refreshes on its own next time.
        }
    }
}
