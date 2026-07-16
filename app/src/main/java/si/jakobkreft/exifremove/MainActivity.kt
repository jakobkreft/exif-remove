package si.jakobkreft.exifremove

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import si.jakobkreft.exifremove.data.AppRepository
import si.jakobkreft.exifremove.ui.AboutScreen
import si.jakobkreft.exifremove.ui.InspectorScreen
import si.jakobkreft.exifremove.ui.MainScreen
import si.jakobkreft.exifremove.ui.OnboardingScreen
import si.jakobkreft.exifremove.ui.SettingsScreen
import si.jakobkreft.exifremove.ui.TemplateEditorScreen
import si.jakobkreft.exifremove.ui.theme.ExifRemoveTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ExifRemoveTheme {
                AppNavigation()
            }
        }
    }
}

private const val SCREEN_HOME = "home"
private const val SCREEN_EDITOR = "editor"
private const val SCREEN_SETTINGS = "settings"
private const val SCREEN_ABOUT = "about"
private const val SCREEN_INSPECTOR = "inspector"

@Composable
private fun AppNavigation() {
    val context = LocalContext.current
    val repository = AppRepository.get(context)
    val state by repository.state.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    var screen by rememberSaveable { mutableStateOf(SCREEN_HOME) }
    var editingTemplateId by rememberSaveable { mutableStateOf<String?>(null) }

    // SAF document picker: unlike the photo picker, it returns the raw file,
    // so EXIF location is not redacted by the system.
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val intent = Intent(context, ShareActivity::class.java).apply {
                action = Intent.ACTION_SEND_MULTIPLE
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            context.startActivity(intent)
        }
    }

    var replayTutorial by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = screen != SCREEN_HOME) { screen = SCREEN_HOME }

    val appState = state ?: return

    if (!appState.onboardingDone || replayTutorial) {
        OnboardingScreen(onDone = {
            replayTutorial = false
            scope.launch { repository.setOnboardingDone() }
        })
        return
    }

    when (screen) {
        SCREEN_HOME -> MainScreen(
            state = appState,
            onCleanImages = { pickMedia.launch(arrayOf("image/*", "video/*")) },
            onNewTemplate = {
                editingTemplateId = null
                screen = SCREEN_EDITOR
            },
            onEditTemplate = { id ->
                editingTemplateId = id
                screen = SCREEN_EDITOR
            },
            onDeleteTemplate = { id -> scope.launch { repository.deleteTemplate(id) } },
            onSetDefault = { id -> scope.launch { repository.setDefaultTemplate(id) } },
            onRestoreDefaults = { scope.launch { repository.restoreBuiltIns() } },
            onSettings = { screen = SCREEN_SETTINGS },
            onAbout = { screen = SCREEN_ABOUT },
            onInspect = { screen = SCREEN_INSPECTOR },
            onTutorial = { replayTutorial = true },
        )
        SCREEN_EDITOR -> TemplateEditorScreen(
            existing = appState.templates.firstOrNull { it.id == editingTemplateId },
            onSave = { template ->
                scope.launch {
                    repository.upsertTemplate(template)
                    screen = SCREEN_HOME
                }
            },
            onBack = { screen = SCREEN_HOME },
        )
        SCREEN_SETTINGS -> SettingsScreen(
            state = appState,
            onSkipDialogChange = { scope.launch { repository.setSkipDialog(it) } },
            onRandomFileNamesChange = { scope.launch { repository.setRandomFileNames(it) } },
            onConvertUnsupportedChange = { scope.launch { repository.setConvertUnsupported(it) } },
            onBack = { screen = SCREEN_HOME },
        )
        SCREEN_ABOUT -> AboutScreen(onBack = { screen = SCREEN_HOME })
        SCREEN_INSPECTOR -> InspectorScreen(
            state = appState,
            onBack = { screen = SCREEN_HOME },
        )
    }
}
