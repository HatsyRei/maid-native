package com.hatsyrei.maidnative.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hatsyrei.maidnative.data.prefs.SettingsRepository

/** How message cards are drawn, provided once by the theme so the thread needs no extra parameters. */
@Immutable
data class ChatAppearance(
    /** Null when profile pictures are hidden. */
    val avatarSize: Dp? = SettingsRepository.DEFAULT_AVATAR_SIZE.dp,
)

val LocalChatAppearance = compositionLocalOf { ChatAppearance() }
