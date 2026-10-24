// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.picker

import java.security.MessageDigest

/**
 * The name and type the picking app will see. The picker asks for these
 * before anything is cleaned, so they are derived rather than read off the
 * finished file — and derived deterministically, so the entry shown in the
 * dialog is the entry that is handed over afterwards.
 */
object OutputName {

    data class Output(val name: String, val mimeType: String)

    fun of(
        documentId: String,
        item: MediaCatalog.Item,
        randomFileNames: Boolean,
        convertUnsupported: Boolean,
    ): Output {
        val converting = convertUnsupported && MediaCatalog.needsConversion(item.mimeType)
        val mime = if (converting) "image/jpeg" else item.mimeType
        val ext = extensionFor(mime, item.displayName)
        val prefix = if (MediaCatalog.isVideo(mime)) "VID_" else "IMG_"

        // File names leak too — the app's own setting governs that here as
        // well. Random, but a stable function of the document id, so the two
        // separate queries the picker makes agree on one name.
        if (randomFileNames || item.displayName.isNullOrBlank()) {
            return Output("$prefix${stableSuffix(documentId)}.$ext", mime)
        }
        val sanitized = item.displayName.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val base = sanitized.substringBeforeLast('.', sanitized)
        return Output("$base.$ext", mime)
    }

    private fun extensionFor(mimeType: String, displayName: String?): String = when (mimeType) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> {
            val fromName = displayName?.substringAfterLast('.', "")?.lowercase()
            when {
                !fromName.isNullOrBlank() && fromName.length <= 4 -> fromName
                MediaCatalog.isVideo(mimeType) -> "mp4"
                else -> "jpg"
            }
        }
    }

    private fun stableSuffix(documentId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(documentId.toByteArray())
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }
}
