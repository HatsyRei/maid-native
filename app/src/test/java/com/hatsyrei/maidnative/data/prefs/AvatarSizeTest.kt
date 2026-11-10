package com.hatsyrei.maidnative.data.prefs

import com.hatsyrei.maidnative.data.prefs.SettingsRepository.Companion.normalizeAvatarSize
import org.junit.Assert.assertEquals
import org.junit.Test

class AvatarSizeTest {

    @Test
    fun `values on the grid are kept`() {
        listOf(30, 32, 34, 36, 38, 40, 42).forEach { assertEquals(it, normalizeAvatarSize(it)) }
    }

    @Test
    fun `anything below the smallest size hides the picture`() {
        assertEquals(SettingsRepository.AVATAR_HIDDEN, normalizeAvatarSize(28))
        assertEquals(SettingsRepository.AVATAR_HIDDEN, normalizeAvatarSize(0))
        assertEquals(SettingsRepository.AVATAR_HIDDEN, normalizeAvatarSize(-5))
    }

    @Test
    fun `off-grid and oversized values snap down onto the grid`() {
        assertEquals(36, normalizeAvatarSize(37))
        assertEquals(42, normalizeAvatarSize(48))
    }
}
