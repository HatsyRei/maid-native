package com.hatsyrei.maidnative.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.hatsyrei.maidnative.R
import com.hatsyrei.maidnative.data.prefs.SettingsRepository
import com.hatsyrei.maidnative.data.store.NameplateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Art shown behind the resting composer pill. Not a static local: changing it is
 * rare, so only the composer needs to recompose for it.
 */
val LocalNameplate = compositionLocalOf<Painter?> { null }

/** Resolves the stored nameplate choice to a painter; custom images decode off the main thread. */
@Composable
fun rememberNameplate(theme: ThemeSettings): Painter? = when (theme.nameplate) {
    SettingsRepository.NAMEPLATE_BLOSSOM -> painterResource(R.drawable.nameplate_blossom)
    SettingsRepository.NAMEPLATE_TWILIGHT -> painterResource(R.drawable.nameplate_twilight)
    SettingsRepository.NAMEPLATE_CUSTOM -> {
        val context = LocalContext.current
        val painter by produceState<Painter?>(null, theme.nameplateStamp) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    NameplateStore(context).decode()?.asImageBitmap()?.let(::BitmapPainter)
                }.getOrNull()
            }
        }
        painter
    }

    else -> null
}
