package com.hatsyrei.maidnative.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hatsyrei.maidnative.R
import com.hatsyrei.maidnative.data.prefs.AppLocale
import com.hatsyrei.maidnative.ui.common.AppDialog

/** Android 13+ hands off to the system's per-app language screen; older versions pick here. */
@Composable
internal fun LanguagePicker() {
    val activity = LocalActivity.current ?: return
    val locales = LocalConfiguration.current.locales
    val current = remember(locales) { AppLocale.get(activity) }
    var picking by remember { mutableStateOf(false) }

    AssistChip(
        onClick = {
            if (Build.VERSION.SDK_INT >= 33) {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_APP_LOCALE_SETTINGS,
                        Uri.fromParts("package", activity.packageName, null),
                    ),
                )
            } else {
                picking = true
            }
        },
        label = { Text(languageName(current)) },
    )

    if (picking) {
        AppDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.language_app_language)) },
            confirmButton = {
                TextButton(onClick = { picking = false }) { Text(stringResource(R.string.action_cancel)) }
            },
            text = {
                Column(Modifier.selectableGroup()) {
                    (listOf("") + AppLocale.supported).forEach { tag ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    picking = false
                                    if (tag != current) AppLocale.set(activity, tag)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = tag == current, onClick = null)
                            Text(
                                languageName(tag),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            },
        )
    }
}

// Each language is named in itself, so it can be found from any UI language.
@Composable
private fun languageName(tag: String): String = when (tag) {
    "" -> stringResource(R.string.language_system)
    "zh-CN" -> stringResource(R.string.language_zh_cn)
    else -> stringResource(R.string.language_en)
}
