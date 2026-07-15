package si.jakobkreft.exifremove.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import si.jakobkreft.exifremove.R
import si.jakobkreft.exifremove.data.AppState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: AppState,
    onSkipDialogChange: (Boolean) -> Unit,
    onRandomFileNamesChange: (Boolean) -> Unit,
    onConvertUnsupportedChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SwitchSetting(
                title = stringResource(R.string.setting_skip_dialog),
                description = stringResource(R.string.setting_skip_dialog_desc),
                checked = state.skipDialog,
                onChange = onSkipDialogChange,
            )
            SwitchSetting(
                title = stringResource(R.string.setting_random_filename),
                description = stringResource(R.string.setting_random_filename_desc),
                checked = state.randomFileNames,
                onChange = onRandomFileNamesChange,
            )
            SwitchSetting(
                title = stringResource(R.string.setting_convert_unsupported),
                description = stringResource(R.string.setting_convert_unsupported_desc),
                checked = state.convertUnsupported,
                onChange = onConvertUnsupportedChange,
            )
        }
    }
}

@Composable
private fun SwitchSetting(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onChange)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
    )
}
