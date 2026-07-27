package com.hatsyrei.maidnative.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Endpoint/model settings persisted via Jetpack DataStore (replaces the RN app's AsyncStorage + use-stored-* hooks). */
class SettingsRepository(private val context: Context) {

    data class Settings(
        val baseURL: String = DEFAULT_BASE_URL,
        val apiKey: String = "",
        val model: String = "",
        val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    )

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            baseURL = prefs[KEY_BASE_URL] ?: DEFAULT_BASE_URL,
            apiKey = prefs[KEY_API_KEY] ?: "",
            model = prefs[KEY_MODEL] ?: "",
            systemPrompt = prefs[KEY_SYSTEM_PROMPT] ?: DEFAULT_SYSTEM_PROMPT,
        )
    }

    suspend fun setBaseURL(value: String) = edit(KEY_BASE_URL, value)
    suspend fun setApiKey(value: String) = edit(KEY_API_KEY, value)
    suspend fun setModel(value: String) = edit(KEY_MODEL, value)
    suspend fun setSystemPrompt(value: String) = edit(KEY_SYSTEM_PROMPT, value)

    private suspend fun edit(key: Preferences.Key<String>, value: String) {
        context.dataStore.edit { it[key] = value }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant."

        private val KEY_BASE_URL = stringPreferencesKey("open-ai-base-url")
        private val KEY_API_KEY = stringPreferencesKey("open-ai-api-key")
        private val KEY_MODEL = stringPreferencesKey("open-ai-model")
        private val KEY_SYSTEM_PROMPT = stringPreferencesKey("system-prompt")
    }
}
