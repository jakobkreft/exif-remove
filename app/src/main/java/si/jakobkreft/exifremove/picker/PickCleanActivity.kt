// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.picker

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import si.jakobkreft.exifremove.R
import si.jakobkreft.exifremove.data.AppRepository
import si.jakobkreft.exifremove.data.Template
import si.jakobkreft.exifremove.engine.CleaningProgress
import si.jakobkreft.exifremove.engine.ExifProcessor
import si.jakobkreft.exifremove.engine.ProcessError
import si.jakobkreft.exifremove.engine.ProcessedImage
import si.jakobkreft.exifremove.engine.ProcessorOptions
import si.jakobkreft.exifremove.ui.FileResultRow
import si.jakobkreft.exifremove.ui.VerifiedBadge
import si.jakobkreft.exifremove.ui.templateSummary
import si.jakobkreft.exifremove.ui.theme.ExifRemoveTheme

private const val FILE_PROVIDER_AUTHORITY = "si.jakobkreft.exifremove.fileprovider"

/**
 * The app as a source in another app's "choose a file" dialog, standing
 * between that app and the file it asked for.
 *
 * Picking `EXIF Remove` opens the ordinary system document picker, so the
 * file can come from anywhere the device offers — internal storage, an SD
 * card, Drive, any other provider — not just from what this app can
 * enumerate. The chosen file is cleaned here and the cleaned copy is what
 * the asking app receives; it never holds a handle on the original.
 *
 * This is the ACTION_GET_CONTENT half of the picker integration.
 * ACTION_OPEN_DOCUMENT callers never see activities, only storage roots, and
 * are served by [CleanDocumentsProvider] instead.
 */
class PickCleanActivity : ComponentActivity() {

    private var picked by mutableStateOf<List<Uri>>(emptyList())

    private lateinit var pickOne: ActivityResultLauncher<Array<String>>
    private lateinit var pickMany: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Backing out of any stage hands the caller a cancel rather than
        // nothing at all, which some apps take as a silent failure.
        setResult(RESULT_CANCELED)

        pickOne = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            onPicked(listOfNotNull(uri))
        }
        pickMany = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            onPicked(uris)
        }

        if (savedInstanceState == null) {
            val types = PickRequest.mimeTypes(
                intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES),
                intent.type,
            )
            // OPEN_DOCUMENT lists storage roots only, so opening it from here
            // cannot loop back into this activity.
            if (intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)) {
                pickMany.launch(types)
            } else {
                pickOne.launch(types)
            }
        }

        setContent {
            ExifRemoveTheme {
                val uris = picked
                if (uris.isNotEmpty()) {
                    PickSheet(
                        uris = uris,
                        onUse = ::returnCleaned,
                        onFinish = { finish() },
                    )
                }
            }
        }
    }

    private fun onPicked(uris: List<Uri>) {
        if (uris.isEmpty()) finish() else picked = uris
    }

    /**
     * The cleaned copies go back as a one-shot read grant on this app's own
     * cache, the same route the share sheet uses. A caller that asks to hold
     * the uri permanently gets nothing later on, which is correct: the
     * cleaned file is a copy made for this hand-over, not a document the
     * other app owns.
     */
    private fun returnCleaned(images: List<ProcessedImage>) {
        val cleaned = images.filter { it.file != null }
        if (cleaned.isEmpty()) {
            finish()
            return
        }
        val uris = cleaned.map {
            FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, it.file!!)
        }
        val data = Intent()
        if (uris.size == 1) {
            data.setDataAndType(uris[0], cleaned[0].mimeType)
        } else {
            // Multi-select results travel in the clip data; GET_CONTENT
            // callers that asked for several files read them from there.
            val clip = ClipData.newUri(contentResolver, cleaned[0].displayName, uris[0])
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            data.clipData = clip
            data.type = when {
                cleaned.all { it.mimeType.startsWith("image/") } -> "image/*"
                cleaned.all { it.mimeType.startsWith("video/") } -> "video/*"
                else -> "*/*"
            }
        }
        data.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        setResult(RESULT_OK, data)
        finish()
    }
}

private sealed interface Stage {
    data object Pick : Stage
    data class Working(val progress: CleaningProgress?) : Stage
    data class Done(val results: List<ProcessedImage>) : Stage
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickSheet(
    uris: List<Uri>,
    onUse: (List<ProcessedImage>) -> Unit,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { AppRepository.get(context) }
    val state by repository.state.collectAsState(initial = null)
    var stage by remember { mutableStateOf<Stage>(Stage.Pick) }
    var expandedIndex by remember { mutableStateOf<Int?>(null) }
    var selectedTemplate by remember { mutableStateOf<Template?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val appState = state ?: return

    LaunchedEffect(Unit) {
        if (appState.skipDialog && selectedTemplate == null) {
            selectedTemplate = appState.defaultTemplate
        }
    }

    LaunchedEffect(selectedTemplate) {
        val template = selectedTemplate ?: return@LaunchedEffect
        stage = Stage.Working(null)
        val results = ExifProcessor.processAll(
            context = context,
            uris = uris,
            template = template,
            options = ProcessorOptions(
                randomFileNames = appState.randomFileNames,
                convertUnsupported = appState.convertUnsupported,
                verifyOutput = appState.verifyOutput,
            ),
            onProgress = { progress -> stage = Stage.Working(progress) },
        )
        stage = Stage.Done(results)
    }

    ModalBottomSheet(
        onDismissRequest = onFinish,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                pluralStringResource(R.plurals.clean_n_images, uris.size, uris.size),
                style = MaterialTheme.typography.titleLarge,
            )
            when (val current = stage) {
                is Stage.Pick -> {
                    Text(
                        stringResource(R.string.choose_template),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    appState.templates.forEach { template ->
                        Card(
                            onClick = { selectedTemplate = template },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        template.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (template.id == appState.defaultTemplateId) {
                                        Text(
                                            stringResource(R.string.default_template),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    templateSummary(template),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                is Stage.Working -> {
                    val progress = current.progress
                    val fraction = progress?.fraction
                    Column(
                        modifier = Modifier.padding(vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            if (progress != null && progress.total > 1) {
                                stringResource(
                                    R.string.cleaning_n_of_m,
                                    (progress.completed + 1).coerceAtMost(progress.total),
                                    progress.total,
                                )
                            } else {
                                stringResource(R.string.processing)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (fraction != null && progress.total > 1) {
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        val name = progress?.currentName
                        if (!name.isNullOrBlank()) {
                            Text(
                                name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                is Stage.Done -> {
                    val cleaned = current.results.filter { it.file != null }
                    val withheld = current.results.count {
                        it.error == ProcessError.NOT_PROVABLY_CLEAN
                    }
                    val failed = current.results.size - cleaned.size - withheld

                    if (cleaned.isEmpty()) {
                        Text(
                            stringResource(R.string.nothing_could_be_cleaned),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text(
                            pluralStringResource(
                                R.plurals.n_images_ready, cleaned.size, cleaned.size
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (cleaned.all { it.report?.verified == true }) {
                            VerifiedBadge()
                        }
                    }
                    if (withheld > 0) {
                        Text(
                            pluralStringResource(
                                R.plurals.n_files_withheld, withheld, withheld
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (failed > 0) {
                        Text(
                            pluralStringResource(R.plurals.n_images_failed, failed, failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Column(
                        modifier = Modifier
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        current.results.forEachIndexed { index, image ->
                            FileResultRow(
                                image = image,
                                expanded = expandedIndex == index,
                                onToggle = {
                                    expandedIndex = if (expandedIndex == index) null else index
                                },
                            )
                        }
                    }

                    if (cleaned.isEmpty()) {
                        OutlinedButton(
                            onClick = onFinish,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.cancel)) }
                    } else {
                        // The hand-over is a deliberate tap: the report above
                        // is worth reading before the file leaves the app.
                        Button(
                            onClick = { onUse(cleaned) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                pluralStringResource(
                                    R.plurals.use_n_files, cleaned.size, cleaned.size
                                )
                            )
                        }
                        OutlinedButton(
                            onClick = onFinish,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.cancel)) }
                    }
                }
            }
        }
    }
}
