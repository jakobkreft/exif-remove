package si.jakobkreft.exifremove.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import si.jakobkreft.exifremove.R
import si.jakobkreft.exifremove.data.RuleAction
import si.jakobkreft.exifremove.data.Template
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateEditorScreen(
    existing: Template?,
    onSave: (Template) -> Unit,
    onBack: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var gps by rememberSaveable { mutableStateOf(existing?.gps ?: RuleAction.REMOVE) }
    var dateTime by rememberSaveable { mutableStateOf(existing?.dateTime ?: RuleAction.REMOVE) }
    var cameraInfo by rememberSaveable { mutableStateOf(existing?.cameraInfo ?: RuleAction.REMOVE) }
    var otherExif by rememberSaveable { mutableStateOf(existing?.otherExif ?: RuleAction.REMOVE) }
    var showNameError by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (existing == null) R.string.new_template else R.string.edit_template
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (name.isBlank()) {
                            showNameError = true
                        } else {
                            onSave(
                                Template(
                                    id = existing?.id ?: UUID.randomUUID().toString(),
                                    name = name.trim(),
                                    gps = gps,
                                    dateTime = dateTime,
                                    cameraInfo = cameraInfo,
                                    otherExif = otherExif,
                                    builtIn = existing?.builtIn ?: false,
                                )
                            )
                        }
                    }) {
                        Icon(Icons.Filled.Check, stringResource(R.string.save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.height(0.dp))
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    showNameError = false
                },
                label = { Text(stringResource(R.string.template_name)) },
                isError = showNameError,
                supportingText = if (showNameError) {
                    { Text(stringResource(R.string.template_name_empty)) }
                } else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            CategoryRow(
                label = stringResource(R.string.cat_gps),
                value = gps,
                options = listOf(RuleAction.KEEP, RuleAction.REMOVE, RuleAction.RANDOMIZE),
                onChange = { gps = it },
            )
            CategoryRow(
                label = stringResource(R.string.cat_datetime),
                value = dateTime,
                options = listOf(RuleAction.KEEP, RuleAction.REMOVE, RuleAction.RANDOMIZE),
                onChange = { dateTime = it },
            )
            CategoryRow(
                label = stringResource(R.string.cat_camera),
                value = cameraInfo,
                options = listOf(RuleAction.KEEP, RuleAction.REMOVE),
                onChange = { cameraInfo = it },
            )
            CategoryRow(
                label = stringResource(R.string.cat_other),
                value = otherExif,
                options = listOf(RuleAction.KEEP, RuleAction.REMOVE),
                onChange = { otherExif = it },
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.always_removed_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CategoryRow(
    label: String,
    value: RuleAction,
    options: List<RuleAction>,
    onChange: (RuleAction) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = value == option,
                    onClick = { onChange(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) {
                    Text(
                        stringResource(
                            when (option) {
                                RuleAction.KEEP -> R.string.action_keep
                                RuleAction.REMOVE -> R.string.action_remove
                                RuleAction.RANDOMIZE -> R.string.action_randomize
                            }
                        )
                    )
                }
            }
        }
    }
}
