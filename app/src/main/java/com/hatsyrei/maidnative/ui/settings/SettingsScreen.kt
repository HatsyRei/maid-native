package com.hatsyrei.maidnative.ui.settings

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.hatsyrei.maidnative.data.prefs.SettingsRepository
import com.hatsyrei.maidnative.ui.chat.ChatUiState
import com.hatsyrei.maidnative.ui.chat.ModelSelector

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: ChatUiState,
    onBaseURL: (String) -> Unit,
    onApiKey: (String) -> Unit,
    onModel: (String) -> Unit,
    onRefreshModels: () -> Unit,
    onScan: (String) -> Unit,
    onResetScan: () -> Unit,
    onAccentColor: (Int) -> Unit,
    onNameplate: (String) -> Unit,
    onImportNameplate: (Uri) -> Unit,
    onBack: () -> Unit,
) {
    var baseURL by remember(state.settings.baseURL) { mutableStateOf(state.settings.baseURL) }
    var apiKey by remember(state.settings.apiKey) { mutableStateOf(state.settings.apiKey) }

    // Reset the scan button to its idle state each time Settings is opened.
    LaunchedEffect(Unit) { onResetScan() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                AutoSaveTextField(
                    text = baseURL,
                    onTextChange = { baseURL = it },
                    committed = state.settings.baseURL,
                    label = "Base URL",
                    onCommit = onBaseURL,
                    modifier = Modifier.weight(1f),
                )
                val scanSucceeded = state.foundURL != null && state.settings.baseURL == state.foundURL
                FilledIconButton(
                    onClick = { onScan(baseURL) },
                    enabled = !state.scanning && !scanSucceeded,
                    modifier = Modifier.size(56.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    when {
                        state.scanning -> CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        scanSucceeded -> Icon(Icons.Filled.Check, contentDescription = "Endpoint found")
                        else -> Icon(Icons.Filled.Search, contentDescription = "Scan for endpoint")
                    }
                }
            }

            AutoSaveTextField(
                text = apiKey,
                onTextChange = { apiKey = it },
                committed = state.settings.apiKey,
                label = "API key (optional for local endpoints)",
                onCommit = onApiKey,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
            )

            Spacer(Modifier.height(8.dp))

            Text("Model", style = MaterialTheme.typography.titleMedium)
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (state.models.isEmpty()) {
                Text(
                    "No models loaded. Tap the scan button or refresh to fetch /models.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ModelSelector(
                    models = state.models,
                    selected = state.settings.model,
                    onSelect = onModel,
                )
            }
            AssistChip(
                onClick = onRefreshModels,
                enabled = !state.refreshingModels,
                label = { Text("Refresh models") },
                leadingIcon = {
                    if (state.refreshingModels) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                    }
                },
            )

            Spacer(Modifier.height(8.dp))

            ThemeSection(
                accentColor = state.settings.accentColor,
                nameplate = state.settings.nameplate,
                onAccentColor = onAccentColor,
                onNameplate = onNameplate,
                onImportNameplate = onImportNameplate,
            )
        }
    }
}

/**
 * An [OutlinedTextField] that commits its value on IME "Done" and when it loses
 * focus (only when the text actually differs from the last [committed] value).
 */
@Composable
private fun AutoSaveTextField(
    text: String,
    onTextChange: (String) -> Unit,
    committed: String,
    label: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            onCommit(text)
            focusManager.clearFocus()
        }),
        modifier = modifier.onFocusChanged { focus ->
            if (focused && !focus.isFocused && text != committed) onCommit(text)
            focused = focus.isFocused
        },
    )
}
