package si.jakobkreft.exifremove.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.ScreenRotation
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import si.jakobkreft.exifremove.R
import si.jakobkreft.exifremove.data.AppState
import si.jakobkreft.exifremove.data.RuleAction
import si.jakobkreft.exifremove.data.Template
import si.jakobkreft.exifremove.engine.Inspection
import si.jakobkreft.exifremove.engine.Inspector
import si.jakobkreft.exifremove.engine.MetaCategory
import si.jakobkreft.exifremove.engine.MetaEntry

private enum class RowStatus { REMOVED, KEPT, CHANGED, ADDED }

private class CompareRow(
    val entry: MetaEntry,
    val status: RowStatus,
    val newValue: String?,
    /** True when the change came from a RANDOMIZE rule (vs. an edit side effect). */
    val randomized: Boolean = false,
)

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
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            pickedUri = uri
            reloadKey++
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
                        IconButton(onClick = {
                            picker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageAndVideo
                                )
                            )
                        }) {
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
                Button(onClick = {
                    picker.launch(
                        PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageAndVideo
                        )
                    )
                }) { Text(stringResource(R.string.pick_file)) }
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
) {
    val template = state.templates.firstOrNull { it.id == selectedTemplateId }
        ?: state.defaultTemplate
    val rows = remember(result, template) { buildRows(result, template) }
    val remaining = result.after?.size ?: 0
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
        if (result.after == null) {
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

        for (category in MetaCategory.entries) {
            val categoryRows = rows.filter { it.entry.category == category }
            if (categoryRows.isEmpty()) continue
            item(key = "header-$category") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                ) {
                    Icon(
                        category.icon(),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        category.label(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            items(categoryRows, key = { "$category-${it.entry.name}-${it.entry.value}" }) { row ->
                MetaRow(row)
            }
        }
    }
}

@Composable
private fun MetaRow(row: CompareRow) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(row.entry.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    row.entry.value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = if (row.status == RowStatus.REMOVED) {
                        TextDecoration.LineThrough
                    } else {
                        TextDecoration.None
                    },
                )
                if (row.status == RowStatus.CHANGED && row.newValue != null) {
                    Text(
                        "→ ${row.newValue}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            StatusChip(row)
        }
    }
}

@Composable
private fun StatusChip(row: CompareRow) {
    val (label, container, content) = when (row.status) {
        RowStatus.REMOVED -> Triple(
            stringResource(R.string.status_removed),
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        RowStatus.KEPT -> Triple(
            stringResource(R.string.status_kept),
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RowStatus.CHANGED -> Triple(
            stringResource(
                if (row.randomized) R.string.status_randomized else R.string.status_modified
            ),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        RowStatus.ADDED -> Triple(
            stringResource(
                if (row.randomized) R.string.status_randomized else R.string.status_added
            ),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private fun buildRows(result: Inspection, template: Template): List<CompareRow> {
    val after = result.after ?: return result.before.map {
        CompareRow(it, RowStatus.REMOVED, null)
    }
    val afterByName = after.groupBy { it.name }
    val beforeNames = result.before.map { it.name }.toSet()
    val rows = result.before.map { entry ->
        val match = afterByName[entry.name]?.firstOrNull()
        val randomized = template.ruleFor(entry.category) == RuleAction.RANDOMIZE
        when {
            match == null -> CompareRow(entry, RowStatus.REMOVED, null)
            match.value.trim() == entry.value.trim() -> CompareRow(entry, RowStatus.KEPT, null)
            match.value.isBlank() -> CompareRow(entry, RowStatus.REMOVED, null)
            else -> CompareRow(entry, RowStatus.CHANGED, match.value, randomized)
        }
    }
    // Entries that only exist after cleaning (e.g. a scrambled location
    // written into a file that had none).
    val added = after.filter { it.name !in beforeNames && it.value.isNotBlank() }.map { entry ->
        CompareRow(
            entry, RowStatus.ADDED, null,
            randomized = template.ruleFor(entry.category) == RuleAction.RANDOMIZE,
        )
    }
    return rows + added
}

private fun Template.ruleFor(category: MetaCategory): RuleAction = when (category) {
    MetaCategory.LOCATION -> gps
    MetaCategory.DATE -> dateTime
    MetaCategory.CAMERA -> cameraInfo
    MetaCategory.ORIENTATION -> RuleAction.KEEP
    MetaCategory.OTHER -> otherExif
}

@Composable
private fun MetaCategory.label(): String = stringResource(
    when (this) {
        MetaCategory.LOCATION -> R.string.sum_location
        MetaCategory.DATE -> R.string.sum_date
        MetaCategory.CAMERA -> R.string.sum_camera
        MetaCategory.ORIENTATION -> R.string.sum_orientation
        MetaCategory.OTHER -> R.string.sum_other
    }
)

private fun MetaCategory.icon(): ImageVector = when (this) {
    MetaCategory.LOCATION -> Icons.Outlined.LocationOn
    MetaCategory.DATE -> Icons.Outlined.Schedule
    MetaCategory.CAMERA -> Icons.Outlined.CameraAlt
    MetaCategory.ORIENTATION -> Icons.Outlined.ScreenRotation
    MetaCategory.OTHER -> Icons.Outlined.Description
}
