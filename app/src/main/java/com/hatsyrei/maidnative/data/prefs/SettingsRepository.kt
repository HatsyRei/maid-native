package com.hatsyrei.maidnative.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hatsyrei.maidnative.data.remote.EndpointScanner
import com.hatsyrei.maidnative.domain.ConversationDefaults
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Endpoint/model settings persisted via Jetpack DataStore (replaces the RN app's AsyncStorage + use-stored-* hooks). */
class SettingsRepository(private val context: Context) {

    data class Settings(
        val baseURL: String = DEFAULT_BASE_URL,
        val apiKey: String = "",
        val model: String = "",
        val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
        /**
         * Whether the model may think. Sent as
         * `chat_template_kwargs.enable_thinking` so a server whose default is
         * "off" (a `--reasoning off` flag, or a models.ini entry) still honours
         * the choice; endpoints that reject the argument fall back silently.
         */
        val reasoning: Boolean = true,
        /** ARGB accent, or 0 for the built-in blue. */
        val accentColor: Int = 0,
        val nameplate: String = DEFAULT_NAMEPLATE,
        /** Import time of the custom nameplate; only used to invalidate its decoded cache. */
        val nameplateStamp: Long = 0L,
        /** Port probed by the endpoint scan. */
        val scanPort: Int = EndpointScanner.DEFAULT_PORT,
        /** Subnet size swept by the endpoint scan, as a CIDR prefix length. */
        val scanPrefixLength: Int = EndpointScanner.DEFAULT_PREFIX_LENGTH,
    )

    /**
     * A saved Base URL + API key pair. API keys aren't memorable the way
     * passwords are, so the app holds them for the endpoints the user switches
     * between; [apiKey] is encrypted at rest by [SecretCipher].
     */
    data class EndpointPreset(
        val id: String,
        val name: String,
        val baseURL: String,
        val apiKey: String,
    )

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            baseURL = prefs[KEY_BASE_URL] ?: DEFAULT_BASE_URL,
            apiKey = SecretCipher.decode(prefs[KEY_API_KEY] ?: ""),
            model = prefs[KEY_MODEL] ?: "",
            systemPrompt = prefs[KEY_SYSTEM_PROMPT] ?: DEFAULT_SYSTEM_PROMPT,
            reasoning = prefs[KEY_REASONING] ?: true,
            accentColor = prefs[KEY_ACCENT] ?: 0,
            nameplate = prefs[KEY_NAMEPLATE] ?: DEFAULT_NAMEPLATE,
            nameplateStamp = prefs[KEY_NAMEPLATE_STAMP] ?: 0L,
            scanPort = (prefs[KEY_SCAN_PORT] ?: EndpointScanner.DEFAULT_PORT).coerceIn(MIN_PORT, MAX_PORT),
            scanPrefixLength = (prefs[KEY_SCAN_PREFIX] ?: EndpointScanner.DEFAULT_PREFIX_LENGTH)
                .takeIf { it in EndpointScanner.PREFIX_CHOICES } ?: EndpointScanner.DEFAULT_PREFIX_LENGTH,
        )
    }

    val presets: Flow<List<EndpointPreset>> = context.dataStore.data.map { prefs ->
        decodePresets(prefs[KEY_PRESETS])
    }

    suspend fun setBaseURL(value: String) = edit(KEY_BASE_URL, value)
    suspend fun setApiKey(value: String) = edit(KEY_API_KEY, SecretCipher.encode(value))
    suspend fun setModel(value: String) = edit(KEY_MODEL, value)
    suspend fun setSystemPrompt(value: String) = edit(KEY_SYSTEM_PROMPT, value)
    suspend fun setNameplate(value: String) = edit(KEY_NAMEPLATE, value)

    suspend fun setReasoning(enabled: Boolean) {
        context.dataStore.edit { it[KEY_REASONING] = enabled }
    }

    suspend fun setAccentColor(argb: Int) {
        context.dataStore.edit { it[KEY_ACCENT] = argb }
    }

    /** Written together so a scan reads one consistent pair. */
    suspend fun setScanOptions(port: Int, prefixLength: Int) {
        context.dataStore.edit {
            it[KEY_SCAN_PORT] = port.coerceIn(MIN_PORT, MAX_PORT)
            it[KEY_SCAN_PREFIX] = prefixLength
        }
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

    /** Adds a preset, or overwrites whichever one already carries [name]. */
    suspend fun savePreset(name: String, baseURL: String, apiKey: String) = editPresets { presets ->
        val trimmed = name.trim()
        val existing = presets.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        if (existing == null) {
            presets + EndpointPreset(UUID.randomUUID().toString(), trimmed, baseURL, apiKey)
        } else {
            presets.map {
                if (it.id == existing.id) it.copy(name = trimmed, baseURL = baseURL, apiKey = apiKey) else it
            }
        }
    }

    /** Takes over [name] the way [savePreset] does, dropping whichever preset held it before. */
    suspend fun renamePreset(id: String, name: String) = editPresets { presets ->
        val trimmed = name.trim()
        presets
            .filterNot { it.id != id && it.name.equals(trimmed, ignoreCase = true) }
            .map { if (it.id == id) it.copy(name = trimmed) else it }
    }

    suspend fun deletePreset(id: String) = editPresets { presets -> presets.filterNot { it.id == id } }

    /**
     * Switches the live endpoint in a single write, so the settings collector
     * sees one emission and fires one model fetch rather than two.
     */
    suspend fun applyPreset(preset: EndpointPreset) {
        context.dataStore.edit {
            it[KEY_BASE_URL] = preset.baseURL
            it[KEY_API_KEY] = SecretCipher.encode(preset.apiKey)
        }
    }

    private suspend fun editPresets(transform: (List<EndpointPreset>) -> List<EndpointPreset>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PRESETS] = encodePresets(transform(decodePresets(prefs[KEY_PRESETS])))
        }
    }

    private fun decodePresets(raw: String?): List<EndpointPreset> {
        if (raw.isNullOrEmpty()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val entry = array.optJSONObject(i) ?: return@mapNotNull null
                val id = entry.optString(FIELD_ID).ifEmpty { return@mapNotNull null }
                EndpointPreset(
                    id = id,
                    name = entry.optString(FIELD_NAME),
                    baseURL = entry.optString(FIELD_BASE_URL),
                    apiKey = SecretCipher.decode(entry.optString(FIELD_API_KEY)),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun encodePresets(presets: List<EndpointPreset>): String {
        val array = JSONArray()
        presets.forEach {
            array.put(
                JSONObject()
                    .put(FIELD_ID, it.id)
                    .put(FIELD_NAME, it.name)
                    .put(FIELD_BASE_URL, it.baseURL)
                    .put(FIELD_API_KEY, SecretCipher.encode(it.apiKey)),
            )
        }
        return array.toString()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_SYSTEM_PROMPT = ConversationDefaults.SYSTEM_PROMPT

        const val NAMEPLATE_NONE = "none"
        const val NAMEPLATE_BLOSSOM = "blossom"
        const val NAMEPLATE_TWILIGHT = "twilight"
        const val NAMEPLATE_CUSTOM = "custom"
        const val DEFAULT_NAMEPLATE = NAMEPLATE_NONE

        const val MIN_PORT = 1
        const val MAX_PORT = 65535

        private val KEY_BASE_URL = stringPreferencesKey("open-ai-base-url")
        private val KEY_API_KEY = stringPreferencesKey("open-ai-api-key")
        private val KEY_MODEL = stringPreferencesKey("open-ai-model")
        private val KEY_SYSTEM_PROMPT = stringPreferencesKey("system-prompt")
        private val KEY_REASONING = booleanPreferencesKey("reasoning-enabled")
        private val KEY_ACCENT = intPreferencesKey("accent-color")
        private val KEY_NAMEPLATE = stringPreferencesKey("composer-nameplate")
        private val KEY_NAMEPLATE_STAMP = longPreferencesKey("composer-nameplate-stamp")
        private val KEY_PRESETS = stringPreferencesKey("endpoint-presets")
        private val KEY_SCAN_PORT = intPreferencesKey("scan-port")
        private val KEY_SCAN_PREFIX = intPreferencesKey("scan-prefix-length")

        private const val FIELD_ID = "id"
        private const val FIELD_NAME = "name"
        private const val FIELD_BASE_URL = "baseURL"
        private const val FIELD_API_KEY = "apiKey"
    }
}
