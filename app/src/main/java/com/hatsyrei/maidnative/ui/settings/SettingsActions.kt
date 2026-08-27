package com.hatsyrei.maidnative.ui.settings

import android.net.Uri
import androidx.compose.runtime.Stable
import com.hatsyrei.maidnative.data.prefs.SettingsRepository.EndpointPreset
import com.hatsyrei.maidnative.data.store.AvatarStore

/**
 * Everything the settings screen can ask the view model to do. Grouped for the
 * same reason as `ChatActions`: loose lambdas made every section unskippable
 * and spread one feature across three parameter lists.
 */
@Stable
class SettingsActions(
    val setBaseURL: (String) -> Unit,
    val setApiKey: (String) -> Unit,
    val setAllowCleartext: (Boolean) -> Unit,
    val setModel: (String) -> Unit,
    val refreshModels: () -> Unit,
    val setReasoning: (Boolean) -> Unit,
    val scan: (port: Int, prefixLength: Int) -> Unit,
    val resetScan: () -> Unit,
    val savePreset: (name: String, baseURL: String, apiKey: String) -> Unit,
    val applyPreset: (EndpointPreset) -> Unit,
    val renamePreset: (id: String, name: String) -> Unit,
    val deletePreset: (String) -> Unit,
    val setAccentColor: (Int) -> Unit,
    val setNameplate: (String) -> Unit,
    val importNameplate: (Uri) -> Unit,
    val setUserName: (String) -> Unit,
    val setAssistantName: (String) -> Unit,
    val importAvatar: (AvatarStore.Role, Uri) -> Unit,
    val removeAvatar: (AvatarStore.Role) -> Unit,
    val setExportMedia: (Boolean) -> Unit,
)
