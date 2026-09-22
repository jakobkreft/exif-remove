// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import si.jakobkreft.exifremove.picker.PickerIntegration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppState(
    val templates: List<Template>,
    val defaultTemplateId: String,
    val skipDialog: Boolean,
    val randomFileNames: Boolean,
    val convertUnsupported: Boolean,
    val verifyOutput: Boolean,
    val pickerIntegration: Boolean,
    val onboardingDone: Boolean,
) {
    val defaultTemplate: Template
        get() = templates.firstOrNull { it.id == defaultTemplateId }
            ?: templates.firstOrNull()
            ?: Template.builtIns().first()
}

class AppRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val templateListSerializer = ListSerializer(Template.serializer())

    private object Keys {
        val TEMPLATES = stringPreferencesKey("templates_json")
        val DEFAULT_TEMPLATE = stringPreferencesKey("default_template_id")
        val SKIP_DIALOG = booleanPreferencesKey("skip_dialog")
        val RANDOM_FILE_NAMES = booleanPreferencesKey("random_file_names")
        val CONVERT_UNSUPPORTED = booleanPreferencesKey("convert_unsupported")
        val VERIFY_OUTPUT = booleanPreferencesKey("verify_output")
        val PICKER_INTEGRATION = booleanPreferencesKey("picker_integration")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    }

    val state: Flow<AppState> = context.dataStore.data.map { prefs -> prefs.toState() }

    suspend fun currentState(): AppState = state.first()

    private fun Preferences.toState(): AppState {
        val templates = this[Keys.TEMPLATES]?.let {
            try {
                json.decodeFromString(templateListSerializer, it)
                    // Migration: this built-in template no longer exists
                    .filterNot { template -> template.id == Template.LEGACY_ID_KEEP_ORIENTATION }
                    .ifEmpty { null }
            } catch (e: Exception) {
                null
            }
        } ?: Template.builtIns()
        return AppState(
            templates = templates,
            defaultTemplateId = this[Keys.DEFAULT_TEMPLATE] ?: Template.ID_REMOVE_EVERYTHING,
            skipDialog = this[Keys.SKIP_DIALOG] ?: false,
            randomFileNames = this[Keys.RANDOM_FILE_NAMES] ?: true,
            convertUnsupported = this[Keys.CONVERT_UNSUPPORTED] ?: true,
            // On by default: the guarantee is the point of the app, and it is
            // never weakened without the user asking for it.
            verifyOutput = this[Keys.VERIFY_OUTPUT] ?: true,
            pickerIntegration = this[Keys.PICKER_INTEGRATION] ?: true,
            onboardingDone = this[Keys.ONBOARDING_DONE] ?: false,
        )
    }

    private suspend fun saveTemplates(templates: List<Template>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TEMPLATES] = json.encodeToString(templateListSerializer, templates)
        }
        // Every template is a separate source in the file picker, so adding,
        // renaming or deleting one has to reach the picker's root list.
        PickerIntegration.notifyRootsChanged(context)
    }

    suspend fun upsertTemplate(template: Template) {
        val templates = currentState().templates.toMutableList()
        val index = templates.indexOfFirst { it.id == template.id }
        if (index >= 0) templates[index] = template else templates.add(template)
        saveTemplates(templates)
    }

    suspend fun deleteTemplate(id: String) {
        saveTemplates(currentState().templates.filterNot { it.id == id })
    }

    suspend fun restoreBuiltIns() {
        // Replace/re-add pristine built-ins, keep custom templates
        val custom = currentState().templates.filterNot { it.builtIn }
        saveTemplates(Template.builtIns() + custom)
    }

    suspend fun setDefaultTemplate(id: String) {
        context.dataStore.edit { it[Keys.DEFAULT_TEMPLATE] = id }
    }

    suspend fun setSkipDialog(value: Boolean) {
        context.dataStore.edit { it[Keys.SKIP_DIALOG] = value }
    }

    suspend fun setRandomFileNames(value: Boolean) {
        context.dataStore.edit { it[Keys.RANDOM_FILE_NAMES] = value }
    }

    suspend fun setConvertUnsupported(value: Boolean) {
        context.dataStore.edit { it[Keys.CONVERT_UNSUPPORTED] = value }
    }

    suspend fun setVerifyOutput(value: Boolean) {
        context.dataStore.edit { it[Keys.VERIFY_OUTPUT] = value }
    }

    suspend fun setPickerIntegration(value: Boolean) {
        context.dataStore.edit { it[Keys.PICKER_INTEGRATION] = value }
        PickerIntegration.setEnabled(context, value)
    }

    suspend fun setOnboardingDone() {
        context.dataStore.edit { it[Keys.ONBOARDING_DONE] = true }
    }

    companion object {
        @Volatile private var instance: AppRepository? = null

        fun get(context: Context): AppRepository =
            instance ?: synchronized(this) {
                instance ?: AppRepository(context.applicationContext).also { instance = it }
            }
    }
}
