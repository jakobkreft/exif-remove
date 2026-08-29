package si.jakobkreft.exifremove.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import si.jakobkreft.exifremove.R
import si.jakobkreft.exifremove.data.AppState
import si.jakobkreft.exifremove.data.Template
import si.jakobkreft.exifremove.engine.Inspection
import si.jakobkreft.exifremove.engine.MediaAccess
import si.jakobkreft.exifremove.engine.Inspector
import si.jakobkreft.exifremove.engine.MetaCategory
import si.jakobkreft.exifremove.engine.ChangeStatus
import si.jakobkreft.exifremove.engine.Finding
import si.jakobkreft.exifremove.engine.MetaChange

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectorScreen(
    state: AppState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var pickedUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var selectedTemplateId by rememberSaveable { mutableStateOf(state.defaultTemplateId) }
    var inspection by remember { mutableStateOf<Inspection?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    val selectedTemplate = state.templates.firstOrNull { it.id == selectedTemplateId }
        ?: state.defaultTemplate

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pickedUri = uri
            reloadKey++
        }
    }

    // Media-location access lifts Android's EXIF location redaction; the
    // picker is launched from the permission callback either way.
    val mediaPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { picker.launch(arrayOf("image/*", "video/*")) }

    fun pick() {
        if (MediaAccess.hasFullAccess(context) ||
            MediaAccess.requiredPermissions().isEmpty()
        ) {
            picker.launch(arrayOf("image/*", "video/*"))
        } else {
            mediaPermissions.launch(MediaAccess.requiredPermissions())
        }
    }

    LaunchedEffect(reloadKey, selectedTemplateId) {
        val uri = pickedUri ?: return@LaunchedEffect
        loading = true
        failed = false
        inspection = Inspector.inspect(context, uri, selectedTemplate)
        failed = inspection == null
        loading = false
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.view_metadata)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    if (pickedUri != null) {
                        IconButton(onClick = { pick() }) {
                            Icon(Icons.Filled.Image, stringResource(R.string.pick_another))
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            pickedUri == null || failed -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Outlined.Policy,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(
                        if (failed) R.string.inspector_could_not_read
                        else R.string.inspector_intro
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
                Button(onClick = { pick() }) { Text(stringResource(R.string.pick_file)) }
            }

            loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            else -> inspection?.let { result ->
                InspectionResult(
                    result = result,
                    state = state,
                    selectedTemplateId = selectedTemplate.id,
                    onTemplateSelected = { selectedTemplateId = it },
                    padding = padding,
                    redactedSource = pickedUri?.authority in MediaAccess.REDACTING_AUTHORITIES &&
                        !MediaAccess.hasFullAccess(context),
                )
            }
        }
    }
}

@Composable
private fun InspectionResult(
    result: Inspection,
    state: AppState,
    selectedTemplateId: String,
    onTemplateSelected: (String) -> Unit,
    padding: PaddingValues,
    redactedSource: Boolean,
) {
    val template = state.templates.firstOrNull { it.id == selectedTemplateId }
        ?: state.defaultTemplate
    val rows = remember(result) {
        result.report?.changes
            ?: result.before.map { MetaChange(it, ChangeStatus.REMOVED) }
    }
    val remaining = rows.count { it.status != ChangeStatus.REMOVED }
    val findings = result.report?.findings.orEmpty()
    var templateMenuOpen by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (result.isVideo) Icons.Filled.Movie else Icons.Filled.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            result.fileName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (result.before.isEmpty()) {
                            stringResource(R.string.inspector_no_metadata)
                        } else {
                            stringResource(
                                R.string.inspector_summary, result.before.size, remaining
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.inspector_template),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    FilterChip(
                        selected = true,
                        onClick = { templateMenuOpen = true },
                        label = {
                            Text(
                                state.templates
                                    .firstOrNull { it.id == selectedTemplateId }?.name ?: "?"
                            )
                        },
                        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
                    )
                    DropdownMenu(
                        expanded = templateMenuOpen,
                        onDismissRequest = { templateMenuOpen = false },
                    ) {
                        state.templates.forEach { template ->
                            DropdownMenuItem(
                                text = { Text(template.name) },
                                onClick = {
                                    templateMenuOpen = false
                                    onTemplateSelected(template.id)
                                },
                            )
                        }
                    }
                }
            }
        }
        if (redactedSource) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Text(
                        stringResource(R.string.inspector_redaction_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        if (result.report == null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Text(
                        stringResource(R.string.inspector_unsupported_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        // Removals a tag listing cannot show, because once they are gone
        // there is nothing left to list — a motion photo's hidden video is
        // invisible in every "before/after" table, including this one.
        if (findings.isNotEmpty()) {
            item(key = "findings-header") {
                Text(
                    stringResource(R.string.inspector_findings_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                )
            }
            items(findings, key = { "finding-${it.kind}-${it.bytes}" }) { finding ->
                FindingRow(finding)
            }
        }

        for (category in MetaCategory.entries) {
            val categoryRows = rows.filter { it.entry.category == category }
            if (categoryRows.isEmpty()) continue
            item(key = "header-$category") { CategoryHeader(category) }
            items(categoryRows, key = { "$category-${it.entry.name}-${it.entry.value}" }) { row ->
                MetaChangeRow(row)
            }
        }
    }
}
