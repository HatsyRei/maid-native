package com.hatsyrei.maidnative.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.hatsyrei.maidnative.R
import com.hatsyrei.maidnative.data.prefs.SettingsRepository
import com.hatsyrei.maidnative.ui.theme.LocalNameplate

/** Accent colour + composer pill artwork. */
@Composable
internal fun ThemeSection(
    accentColor: Int,
    nameplate: String,
    onAccentColor: (Int) -> Unit,
    onNameplate: (String) -> Unit,
    onImportNameplate: (Uri) -> Unit,
) {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(onImportNameplate) }

    Text("Theme", style = MaterialTheme.typography.titleMedium)

    Label("Accent")
    AccentPicker(selected = accentColor, onSelect = onAccentColor)

    Label("Composer background")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NameplateOption(
            selected = nameplate == SettingsRepository.NAMEPLATE_NONE,
            onClick = { onNameplate(SettingsRepository.NAMEPLATE_NONE) },
            label = "None",
        )
        NameplateOption(
            painter = painterResource(R.drawable.nameplate_blossom),
            selected = nameplate == SettingsRepository.NAMEPLATE_BLOSSOM,
            onClick = { onNameplate(SettingsRepository.NAMEPLATE_BLOSSOM) },
            label = "Blossom",
        )
        NameplateOption(
            painter = painterResource(R.drawable.nameplate_twilight),
            selected = nameplate == SettingsRepository.NAMEPLATE_TWILIGHT,
            onClick = { onNameplate(SettingsRepository.NAMEPLATE_TWILIGHT) },
            label = "Twilight",
        )
        val custom = nameplate == SettingsRepository.NAMEPLATE_CUSTOM
        NameplateOption(
            // Only the active choice is decoded, so a stored custom image has no
            // painter to preview unless it is the one in use.
            painter = LocalNameplate.current.takeIf { custom },
            selected = custom,
            onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            label = if (custom) "Change" else "Choose",
            icon = !custom,
        )
    }
    Text(
        "Fades out while you type. Images are cropped to the pill and copied into the app.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun NameplateOption(
    selected: Boolean,
    onClick: () -> Unit,
    painter: Painter? = null,
    label: String,
    icon: Boolean = false,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .width(96.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = RoundedCornerShape(20.dp),
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (painter != null) {
                Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            if (icon) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Choose an image",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
