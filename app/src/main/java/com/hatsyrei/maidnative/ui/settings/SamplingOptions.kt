package com.hatsyrei.maidnative.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hatsyrei.maidnative.domain.Sampling
import com.hatsyrei.maidnative.domain.SamplingParam
import com.hatsyrei.maidnative.ui.icons.TuneIcon

private val TRUNCATION = listOf(
    SamplingParam.TEMPERATURE,
    SamplingParam.TOP_P,
    SamplingParam.TOP_K,
    SamplingParam.MIN_P,
)

private val PENALTIES = listOf(
    SamplingParam.FREQUENCY_PENALTY,
    SamplingParam.PRESENCE_PENALTY,
)

/** Opens the sampling dialog, and carries how many fields are currently overridden. */
@Composable
internal fun SamplingChip(sampling: Sampling, onClick: () -> Unit) {
    val overrides = sampling.overrides
    val active = overrides > 0
    AssistChip(
        onClick = onClick,
        label = { Text(if (active) "Sampling · $overrides" else "Sampling") },
        leadingIcon = { Icon(TuneIcon, contentDescription = null) },
        // Filled when something is overridden, so the screen says at a glance
        // whether requests carry anything beyond the endpoint's own settings.
        // Same fill as the model pill and the selected chat entry.
        colors = if (!active) {
            AssistChipDefaults.assistChipColors()
        } else {
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        border = if (active) null else AssistChipDefaults.assistChipBorder(enabled = true),
    )
}

/**
 * Per-field sampling overrides. Each row is off by default: an off field is left
 * out of the request entirely, so the endpoint's own flags — and the recommended
 * sampling llama.cpp reads from the model's metadata — still apply.
 */
@Composable
internal fun SamplingDialog(
    sampling: Sampling,
    onDismiss: () -> Unit,
    onConfirm: (Sampling) -> Unit,
) {
    var draft by remember { mutableStateOf(sampling) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = { Text("Sampling") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TRUNCATION.forEach { param ->
                    SamplingRow(param, draft[param]) { draft = draft.with(param, it) }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text("Penalties", style = MaterialTheme.typography.titleSmall)
                PENALTIES.forEach { param ->
                    SamplingRow(param, draft[param]) { draft = draft.with(param, it) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** [value] null means the field is not overridden; [onChange] null turns it back off. */
@Composable
private fun SamplingRow(
    param: SamplingParam,
    value: Float?,
    onChange: (Float?) -> Unit,
) {
    val enabled = value != null
    // Holds the position while the row is off, so flipping the switch back on
    // resumes where the user left it rather than snapping to the fallback.
    var parked by remember { mutableFloatStateOf(value ?: param.fallback) }
    val current = value ?: parked

    Column(modifier = Modifier.padding(top = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(param.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    param.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { on -> onChange(if (on) parked else null) },
            )
        }

        Row(
            // Clears the tall M3 slider thumb from the label above it.
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Slider(
                value = current,
                onValueChange = {
                    parked = it
                    onChange(it)
                },
                valueRange = param.min..param.max,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (enabled) param.format(current) else "(${param.format(param.fallback)})",
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.End,
                modifier = Modifier.width(52.dp),
            )
        }
    }
}
