package com.hatsyrei.maidnative.data.prefs

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinBaseUrlTest {

    private val baseUrl = stringPreferencesKey("open-ai-base-url")
    private val migration = SettingsRepository.PinBaseUrl

    private fun migrated(prefs: Preferences): Preferences = runBlocking {
        assertTrue(migration.shouldMigrate(prefs))
        migration.migrate(prefs)
    }

    @Test
    fun `a fresh install is pinned to no endpoint`() {
        assertEquals("", migrated(emptyPreferences())[baseUrl])
    }

    @Test
    fun `an existing install that never set a url keeps the old openai default`() {
        val prefs = preferencesOf(stringPreferencesKey("active-chat") to "root")
        val result = migrated(prefs)
        assertEquals("https://api.openai.com/v1", result[baseUrl])
        assertEquals("root", result[stringPreferencesKey("active-chat")])
    }

    @Test
    fun `a stored url is left alone`() = runBlocking {
        assertFalse(migration.shouldMigrate(preferencesOf(baseUrl to "http://192.168.1.5:8080/v1")))
        assertFalse(migration.shouldMigrate(preferencesOf(baseUrl to "")))
    }
}
