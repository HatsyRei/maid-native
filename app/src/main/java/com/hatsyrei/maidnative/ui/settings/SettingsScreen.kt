package com.hatsyrei.maidnative.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hatsyrei.maidnative.data.prefs.SettingsRepository
import com.hatsyrei.maidnative.ui.chat.ChatUiState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: ChatUiState,
    onBaseURL: (String) -> Unit,
    onApiKey: (String) -> Unit,
    onModel: (String) -> Unit,
    onRefreshModels: () -> Unit,
    onScan: () -> Unit,
    onBack: () -> Unit,
) {
    var baseURL by remember(state.settings.baseURL) { mutableStateOf(state.settings.baseURL) }
    var apiKey by remember(state.settings.apiKey) { mutableStateOf(state.settings.apiKey) }

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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = baseURL,
                    onValueChange = { baseURL = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                val scanSucceeded = state.foundURL != null && state.settings.baseURL == state.foundURL
                FilledIconButton(
                    onClick = onScan,
                    enabled = !state.scanning && !scanSucceeded,
                ) {
                    when {
                        state.scanning -> CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        scanSucceeded -> Icon(Icons.Filled.Check, contentDescription = "Endpoint found")
                        else -> Icon(Icons.Filled.Search, contentDescription = "Scan for endpoint")
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { onBaseURL(baseURL) },
                    label = { Text("Save endpoint & load models") },
                    leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                )
                AssistChip(
                    onClick = {
                        baseURL = SettingsRepository.DEFAULT_BASE_URL
                        onBaseURL(SettingsRepository.DEFAULT_BASE_URL)
                    },
                    label = { Text("Reset to default") },
                )
            }

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API key (optional for local endpoints)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            AssistChip(
                onClick = { onApiKey(apiKey) },
                label = { Text("Save API key") },
            )

            Text("Model", style = MaterialTheme.typography.titleMedium)
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (state.models.isEmpty()) {
                Text(
                    "No models loaded. Save the endpoint above to fetch /models.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.models.forEach { model ->
                        FilterChip(
                            selected = model == state.settings.model,
                            onClick = { onModel(model) },
                            label = { Text(model) },
                        )
                    }
                }
            }
            AssistChip(
                onClick = onRefreshModels,
                label = { Text("Refresh models") },
                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
            )
        }
    }
}
