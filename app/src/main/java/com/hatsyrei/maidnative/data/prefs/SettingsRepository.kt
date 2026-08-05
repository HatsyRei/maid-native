package com.hatsyrei.maidnative.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hatsyrei.maidnative.domain.ConversationDefaults
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
        /** ARGB accent, or 0 for the built-in blue. */
        val accentColor: Int = 0,
        val nameplate: String = DEFAULT_NAMEPLATE,
        /** Import time of the custom nameplate; only used to invalidate its decoded cache. */
        val nameplateStamp: Long = 0L,
    )

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            baseURL = prefs[KEY_BASE_URL] ?: DEFAULT_BASE_URL,
            apiKey = prefs[KEY_API_KEY] ?: "",
            model = prefs[KEY_MODEL] ?: "",
            systemPrompt = prefs[KEY_SYSTEM_PROMPT] ?: DEFAULT_SYSTEM_PROMPT,
            accentColor = prefs[KEY_ACCENT] ?: 0,
            nameplate = prefs[KEY_NAMEPLATE] ?: DEFAULT_NAMEPLATE,
            nameplateStamp = prefs[KEY_NAMEPLATE_STAMP] ?: 0L,
        )
    }

    suspend fun setBaseURL(value: String) = edit(KEY_BASE_URL, value)
    suspend fun setApiKey(value: String) = edit(KEY_API_KEY, value)
    suspend fun setModel(value: String) = edit(KEY_MODEL, value)
    suspend fun setSystemPrompt(value: String) = edit(KEY_SYSTEM_PROMPT, value)
    suspend fun setNameplate(value: String) = edit(KEY_NAMEPLATE, value)

    suspend fun setAccentColor(argb: Int) {
        context.dataStore.edit { it[KEY_ACCENT] = argb }
    }

    /** Selects the freshly imported custom image; the stamp busts its decoded cache. */
    suspend fun setCustomNameplate(stamp: Long) {
        context.dataStore.edit {
            it[KEY_NAMEPLATE] = NAMEPLATE_CUSTOM
            it[KEY_NAMEPLATE_STAMP] = stamp
        }
    }

    private suspend fun edit(key: Preferences.Key<String>, value: String) {
        context.dataStore.edit { it[key] = value }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_SYSTEM_PROMPT = ConversationDefaults.SYSTEM_PROMPT

        const val NAMEPLATE_NONE = "none"
        const val NAMEPLATE_BLOSSOM = "blossom"
        const val NAMEPLATE_TWILIGHT = "twilight"
        const val NAMEPLATE_CUSTOM = "custom"
        const val DEFAULT_NAMEPLATE = NAMEPLATE_BLOSSOM

        private val KEY_BASE_URL = stringPreferencesKey("open-ai-base-url")
        private val KEY_API_KEY = stringPreferencesKey("open-ai-api-key")
        private val KEY_MODEL = stringPreferencesKey("open-ai-model")
        private val KEY_SYSTEM_PROMPT = stringPreferencesKey("system-prompt")
        private val KEY_ACCENT = intPreferencesKey("accent-color")
        private val KEY_NAMEPLATE = stringPreferencesKey("composer-nameplate")
        private val KEY_NAMEPLATE_STAMP = longPreferencesKey("composer-nameplate-stamp")
    }
}
