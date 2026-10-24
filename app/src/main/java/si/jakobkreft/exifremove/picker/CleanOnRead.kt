// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.picker

import android.content.Context
import android.os.CancellationSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import si.jakobkreft.exifremove.data.AppRepository
import si.jakobkreft.exifremove.engine.ExifProcessor
import si.jakobkreft.exifremove.engine.ProcessError
import si.jakobkreft.exifremove.engine.ProcessorOptions
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

/**
 * Cleaning happens here, at the moment the picking app opens the file —
 * never while the picker is merely listing the gallery. Browsing costs
 * nothing, and nothing is written to disk for files the user scrolls past.
 */
object CleanOnRead {

    private val produced = ConcurrentHashMap<String, File>()
    private val locks = ConcurrentHashMap<String, Any>()

    /**
     * The cleaned copy of [documentId]. Repeated opens of the same document
     * reuse it — the picker itself opens a file more than once, and cleaning
     * a video twice is not free.
     */
    fun cleanedFile(
        context: Context,
        documentId: String,
        signal: CancellationSignal? = null,
    ): File {
        produced[documentId]?.let { if (it.isFile) return it }
        val lock = locks.getOrPut(documentId) { Any() }
        synchronized(lock) {
            produced[documentId]?.let { if (it.isFile) return it }
            val file = clean(context, documentId, signal)
            produced[documentId] = file
            return file
        }
    }

    private fun clean(
        context: Context,
        documentId: String,
        signal: CancellationSignal?,
    ): File {
        val mediaId = PickerIntegration.mediaIdOf(documentId)
            ?: throw FileNotFoundException("Malformed document id $documentId")
        val item = MediaCatalog.item(context, mediaId)
            ?: throw FileNotFoundException("No media $mediaId")
        val state = runBlocking { AppRepository.get(context).currentState() }
        val template = PickerIntegration.templateFor(documentId, state.templates)
            ?: throw FileNotFoundException("Unknown template in $documentId")

        val result = runBlocking {
            val work = CoroutineScope(Dispatchers.IO).async {
                ExifProcessor.processAll(
                    context = context,
                    uris = listOf(MediaCatalog.uri(item)),
                    template = template,
                    options = ProcessorOptions(
                        randomFileNames = state.randomFileNames,
                        convertUnsupported = state.convertUnsupported,
                        verifyOutput = state.verifyOutput,
                    ),
                )
            }
            signal?.setOnCancelListener { work.cancel() }
            work.await()
        }.firstOrNull() ?: throw FileNotFoundException("Nothing produced for $documentId")

        // The app's promise is that a file which cannot be proven clean is
        // withheld rather than handed on. Failing the open keeps that promise
        // here too: the picking app gets an error, never the original bytes.
        val file = result.file
        if (file == null || result.error != null) {
            throw FileNotFoundException(
                "Could not clean $documentId: ${result.error ?: ProcessError.UNREADABLE}"
            )
        }
        return file
    }

    /** Drops the memo; the files themselves are swept by CleanedCache. */
    fun forget() {
        produced.clear()
        locks.clear()
    }
}
