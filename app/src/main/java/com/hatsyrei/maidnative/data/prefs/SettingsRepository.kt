package com.hatsyrei.maidnative.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hatsyrei.maidnative.data.remote.EndpointScanner
import com.hatsyrei.maidnative.data.remote.Endpoints
import com.hatsyrei.maidnative.domain.ConversationDefaults
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
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
        /**
         * True when a key is stored but this device's Keystore can no longer
         * decrypt it. [apiKey] reads empty in that case, and the difference
         * matters: "no key" and "unreadable key" call for different fixes.
         */
        val apiKeyUnreadable: Boolean = false,
        /**
         * Whether plain-HTTP endpoints may be contacted at all. Off by default,
         * because an API key sent over HTTP is readable by anything on the same
         * network; local servers need it turned on deliberately.
         */
        val allowCleartext: Boolean = false,
        val model: String = "",
        val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
        /**
         * Whether the model may think. Sent as
         * `chat_template_kwargs.enable_thinking` so a server whose default is
         * "off" (a `--reasoning off` flag, or a models.ini entry) still honours
         * the choice; endpoints that reject the argument fall back silently.
         */
        val reasoning: Boolean = true,
        /**
         * Whether exports inline image and audio bytes. Off keeps the JSON
         * small, at the cost of those attachments not surviving the round trip.
         */
        val exportMedia: Boolean = true,
        /** ARGB accent, or 0 for the built-in blue. */
        val accentColor: Int = 0,
        /** Role labels shown on message cards. */
        val userName: String = DEFAULT_USER_NAME,
        val assistantName: String = DEFAULT_ASSISTANT_NAME,
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
        /**
         * The ciphertext as stored. Kept so an entry this device can no longer
         * decrypt survives edits to its neighbours: every preset write re-encodes
         * the whole list, and re-encrypting the empty [apiKey] substituted for an
         * unreadable key would destroy it for good.
         */
        val storedKey: String = "",
        val keyReadable: Boolean = true,
    )

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        val baseURL = prefs[KEY_BASE_URL] ?: DEFAULT_BASE_URL
        val apiKey = decodeApiKey(prefs[KEY_API_KEY] ?: "")
        Settings(
            baseURL = baseURL,
            apiKey = apiKey ?: "",
            apiKeyUnreadable = apiKey == null,
            // Absent means the user has never been asked. An endpoint that was
            // already configured over HTTP predates the toggle and keeps
            // working; a fresh install sits on the HTTPS default and starts off.
            allowCleartext = prefs[KEY_ALLOW_CLEARTEXT] ?: Endpoints.isCleartext(baseURL),
            model = prefs[KEY_MODEL] ?: "",
            systemPrompt = prefs[KEY_SYSTEM_PROMPT] ?: DEFAULT_SYSTEM_PROMPT,
            reasoning = prefs[KEY_REASONING] ?: true,
            exportMedia = prefs[KEY_EXPORT_MEDIA] ?: true,
            accentColor = prefs[KEY_ACCENT] ?: 0,
            // A cleared field falls back to the default rather than a blank label.
            userName = prefs[KEY_USER_NAME]?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_NAME,
            assistantName = prefs[KEY_ASSISTANT_NAME]?.takeIf { it.isNotBlank() }
                ?: DEFAULT_ASSISTANT_NAME,
            nameplate = prefs[KEY_NAMEPLATE] ?: DEFAULT_NAMEPLATE,
            nameplateStamp = prefs[KEY_NAMEPLATE_STAMP] ?: 0L,
            scanPort = (prefs[KEY_SCAN_PORT] ?: EndpointScanner.DEFAULT_PORT).coerceIn(MIN_PORT, MAX_PORT),
            scanPrefixLength = (prefs[KEY_SCAN_PREFIX] ?: EndpointScanner.DEFAULT_PREFIX_LENGTH)
                .takeIf { it in EndpointScanner.PREFIX_CHOICES } ?: EndpointScanner.DEFAULT_PREFIX_LENGTH,
        )
    }.distinctUntilChanged()

    val presets: Flow<List<EndpointPreset>> = context.dataStore.data
        // DataStore re-emits the whole snapshot on every write, including ones
        // this flow has nothing to do with (the active chat changes on each
        // conversation switch). Narrowing to the stored value first means the
        // JSON parse and its per-entry Keystore decrypt run only on a real edit.
        .map { it[KEY_PRESETS] }
        .distinctUntilChanged()
        .map { decodePresets(it) }

    /**
     * Root id of the conversation the app was last in, so a relaunch reopens it.
     * Kept out of [Settings] because it changes on every chat switch and nothing
     * in the UI reads it after startup.
     */
    val activeChat: Flow<String?> = context.dataStore.data.map { it[KEY_ACTIVE_CHAT] }

    // Decrypting through the Android Keystore is an IPC round trip, and the
    // ciphertext only changes when the user edits the key, so the last result is
    // held rather than re-derived for every snapshot the settings flow maps.
    @Volatile
    private var decodedApiKey: Pair<String, String?>? = null

    private fun decodeApiKey(stored: String): String? {
        decodedApiKey?.let { (cipher, plain) -> if (cipher == stored) return plain }
        return SecretCipher.decode(stored).also { decodedApiKey = stored to it }
    }

    /**
     * Points the app at [value], and drops the stored API key when that moves to
     * a different origin than the key was entered for. Returns the origin whose
     * key was dropped, or null if nothing was dropped.
     *
     * Without this, editing the Base URL — or accepting whatever host won the
     * local-network scan race — makes the very next `/models` fetch hand the
     * user's key to a server they never gave it to.
     */
    suspend fun setBaseURL(value: String): String? {
        var dropped: String? = null
        context.dataStore.edit { prefs ->
            val previous = prefs[KEY_BASE_URL] ?: DEFAULT_BASE_URL
            prefs[KEY_BASE_URL] = value
            if (prefs[KEY_API_KEY].isNullOrEmpty()) return@edit
            // A key written before origins were recorded is adopted by whatever
            // endpoint was configured at the moment it is first moved away from.
            val bound = prefs[KEY_API_KEY_ORIGIN] ?: Endpoints.origin(previous)
            if (bound != null && bound == Endpoints.origin(value)) return@edit
            prefs.remove(KEY_API_KEY)
            prefs.remove(KEY_API_KEY_ORIGIN)
            dropped = bound ?: previous
        }
        return dropped
    }

    /**
     * @throws SecretUnavailableException if the key cannot be encrypted, leaving
     * the stored key untouched. Encryption runs before the edit opens so a
     * failure cannot half-write the pair.
     */
    suspend fun setApiKey(value: String) {
        val cipher = SecretCipher.encode(value)
        context.dataStore.edit { prefs ->
            prefs.putKey(cipher, prefs[KEY_BASE_URL] ?: DEFAULT_BASE_URL)
        }
    }

    suspend fun setModel(value: String) = edit(KEY_MODEL, value)
    suspend fun setSystemPrompt(value: String) = edit(KEY_SYSTEM_PROMPT, value)
    suspend fun setUserName(value: String) = edit(KEY_USER_NAME, value.trim())
    suspend fun setAssistantName(value: String) = edit(KEY_ASSISTANT_NAME, value.trim())
    suspend fun setNameplate(value: String) = edit(KEY_NAMEPLATE, value)

    suspend fun setReasoning(enabled: Boolean) {
        context.dataStore.edit { it[KEY_REASONING] = enabled }
    }

    suspend fun setExportMedia(enabled: Boolean) {
        context.dataStore.edit { it[KEY_EXPORT_MEDIA] = enabled }
    }

    suspend fun setAllowCleartext(enabled: Boolean) {
        context.dataStore.edit { it[KEY_ALLOW_CLEARTEXT] = enabled }
    }

    suspend fun setAccentColor(argb: Int) {
        context.dataStore.edit { it[KEY_ACCENT] = argb }
    }

    suspend fun setActiveChat(rootId: String?) {
        context.dataStore.edit {
            if (rootId == null) it.remove(KEY_ACTIVE_CHAT) else it[KEY_ACTIVE_CHAT] = rootId
        }
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
                if (it.id == existing.id) {
                    it.copy(name = trimmed, baseURL = baseURL, apiKey = apiKey, keyReadable = true)
                } else {
                    it
                }
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
     * sees one emission and fires one model fetch rather than two. Base URL and
     * key move together, so this is the one path that legitimately re-binds the
     * key to a new origin.
     *
     * @throws SecretUnavailableException if the key cannot be encrypted, in
     * which case neither half is applied.
     */
    suspend fun applyPreset(preset: EndpointPreset) {
        val cipher = SecretCipher.encode(preset.apiKey)
        context.dataStore.edit {
            it[KEY_BASE_URL] = preset.baseURL
            it.putKey(cipher, preset.baseURL)
        }
    }

    /** Stores [cipher] bound to the origin of [url], or clears both when it is empty. */
    private fun MutablePreferences.putKey(cipher: String, url: String) {
        if (cipher.isEmpty()) {
            remove(KEY_API_KEY)
            remove(KEY_API_KEY_ORIGIN)
            return
        }
        this[KEY_API_KEY] = cipher
        // An unparseable URL gets an origin nothing can match, so the key is
        // dropped rather than sent, the moment the endpoint is corrected.
        this[KEY_API_KEY_ORIGIN] = Endpoints.origin(url) ?: ""
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
                val storedKey = entry.optString(FIELD_API_KEY)
                val apiKey = SecretCipher.decode(storedKey)
                EndpointPreset(
                    id = id,
                    name = entry.optString(FIELD_NAME),
                    baseURL = entry.optString(FIELD_BASE_URL),
                    apiKey = apiKey ?: "",
                    storedKey = storedKey,
                    keyReadable = apiKey != null,
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
                    .put(
                        FIELD_API_KEY,
                        if (it.keyReadable) SecretCipher.encode(it.apiKey) else it.storedKey,
                    ),
            )
        }
        return array.toString()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_SYSTEM_PROMPT = ConversationDefaults.SYSTEM_PROMPT

        const val DEFAULT_USER_NAME = "User"
        const val DEFAULT_ASSISTANT_NAME = "Assistant"

        const val NAMEPLATE_NONE = "none"
        const val NAMEPLATE_BLOSSOM = "blossom"
        const val NAMEPLATE_TWILIGHT = "twilight"
        const val NAMEPLATE_CUSTOM = "custom"
        const val DEFAULT_NAMEPLATE = NAMEPLATE_NONE

        const val MIN_PORT = 1
        const val MAX_PORT = 65535

        private val KEY_BASE_URL = stringPreferencesKey("open-ai-base-url")
        private val KEY_API_KEY = stringPreferencesKey("open-ai-api-key")
        private val KEY_API_KEY_ORIGIN = stringPreferencesKey("open-ai-api-key-origin")
        private val KEY_ALLOW_CLEARTEXT = booleanPreferencesKey("allow-cleartext-endpoints")
        private val KEY_MODEL = stringPreferencesKey("open-ai-model")
        private val KEY_SYSTEM_PROMPT = stringPreferencesKey("system-prompt")
        private val KEY_REASONING = booleanPreferencesKey("reasoning-enabled")
        private val KEY_EXPORT_MEDIA = booleanPreferencesKey("export-media")
        private val KEY_ACCENT = intPreferencesKey("accent-color")
        private val KEY_USER_NAME = stringPreferencesKey("user-name")
        private val KEY_ASSISTANT_NAME = stringPreferencesKey("assistant-name")
        private val KEY_NAMEPLATE = stringPreferencesKey("composer-nameplate")
        private val KEY_NAMEPLATE_STAMP = longPreferencesKey("composer-nameplate-stamp")
        private val KEY_PRESETS = stringPreferencesKey("endpoint-presets")
        private val KEY_SCAN_PORT = intPreferencesKey("scan-port")
        private val KEY_SCAN_PREFIX = intPreferencesKey("scan-prefix-length")
        private val KEY_ACTIVE_CHAT = stringPreferencesKey("active-chat")

        private const val FIELD_ID = "id"
        private const val FIELD_NAME = "name"
        private const val FIELD_BASE_URL = "baseURL"
        private const val FIELD_API_KEY = "apiKey"
    }
}
