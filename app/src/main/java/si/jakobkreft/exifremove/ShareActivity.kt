package si.jakobkreft.exifremove

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import si.jakobkreft.exifremove.data.AppRepository
import si.jakobkreft.exifremove.data.Template
import si.jakobkreft.exifremove.engine.ExifProcessor
import si.jakobkreft.exifremove.engine.ProcessError
import si.jakobkreft.exifremove.engine.ProcessedImage
import si.jakobkreft.exifremove.engine.ProcessorOptions
import si.jakobkreft.exifremove.ui.FileResultRow
import si.jakobkreft.exifremove.ui.VerifiedBadge
import si.jakobkreft.exifremove.ui.templateSummary
import si.jakobkreft.exifremove.ui.theme.ExifRemoveTheme

private const val FILE_PROVIDER_AUTHORITY = "si.jakobkreft.exifremove.fileprovider"

class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = extractUris(intent)
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.nothing_to_share, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContent {
            ExifRemoveTheme {
                ShareSheet(
                    uris = uris,
                    saveSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                    onShare = ::shareCleaned,
                    onSave = ::saveCleaned,
                    onFinish = { finish() },
                )
            }
        }
    }

    private fun extractUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        )
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.filterNotNull()
                ?: emptyList()
        else -> emptyList()
    }

    private fun shareCleaned(images: List<ProcessedImage>) {
        val contentUris = ArrayList(
            images.mapNotNull { image ->
                image.file?.let { FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, it) }
            }
        )
        if (contentUris.isEmpty()) return
        val mimeTypes = images.mapNotNull { it.file?.let { _ -> it.mimeType } }.distinct()
        val shareIntent = if (contentUris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, contentUris[0]) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, contentUris)
            }
        }
        shareIntent.type = when {
            mimeTypes.size == 1 -> mimeTypes[0]
            mimeTypes.all { it.startsWith("image/") } -> "image/*"
            mimeTypes.all { it.startsWith("video/") } -> "video/*"
            else -> "*/*"
        }
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The app deliberately leaves itself in this chooser. Cleaning an
        // already-clean file is a cheap no-op (no re-encode, so no quality
        // loss), and being able to hand a cleaned file back to another copy
        // of the app is what makes chaining work — most usefully across a
        // work/personal profile boundary, where the second copy can save it
        // on the other side.
        val chooser = Intent.createChooser(shareIntent, null).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(chooser)
        finish()
    }

    private fun saveCleaned(images: List<ProcessedImage>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                for (item in images) {
                    val file = item.file ?: continue
                    val isVideo = item.mimeType.startsWith("video/")
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, item.displayName)
                        put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            if (isVideo) "Movies/EXIF Remove" else "Pictures/EXIF Remove",
                        )
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val collection = if (isVideo) {
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    }
                    val itemUri = contentResolver.insert(collection, values) ?: continue
                    contentResolver.openOutputStream(itemUri)?.use { outs ->
                        file.inputStream().use { ins -> ins.copyTo(outs) }
                    }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    contentResolver.update(itemUri, values, null, null)
                }
            }
            Toast.makeText(this@ShareActivity, R.string.saved_to_gallery, Toast.LENGTH_LONG).show()
            finish()
        }
    }
}

private sealed interface Stage {
    data object Pick : Stage
    data object Working : Stage
    data class Done(val results: List<ProcessedImage>) : Stage
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareSheet(
    uris: List<Uri>,
    saveSupported: Boolean,
    onShare: (List<ProcessedImage>) -> Unit,
    onSave: (List<ProcessedImage>) -> Unit,
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
        stage = Stage.Working
        val results = ExifProcessor.processAll(
            context = context,
            uris = uris,
            template = template,
            options = ProcessorOptions(
                randomFileNames = appState.randomFileNames,
                convertUnsupported = appState.convertUnsupported,
            ),
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(vertical = 24.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            stringResource(R.string.processing),
                            style = MaterialTheme.typography.bodyLarge,
                        )
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
                        // Videos are scrubbed in place rather than rebuilt, so
                        // there is no re-parsed container behind the claim.
                        // Only promise verification where it actually ran.
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

                    // One row per file: a plain summary, with the evidence a
                    // tap away for anyone who wants to check the work.
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
                        Button(
                            onClick = { onShare(cleaned) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.share)) }
                        if (saveSupported) {
                            OutlinedButton(
                                onClick = { onSave(cleaned) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.save_to_gallery)) }
                        }
                    }
                }
            }
        }
    }
}
