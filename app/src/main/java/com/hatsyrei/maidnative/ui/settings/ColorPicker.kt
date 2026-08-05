package com.hatsyrei.maidnative.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** Material 200-level tones: bright enough to stay legible on the AMOLED canvas. */
private val AccentPresets = listOf(
    Color(0xFF90CAF9), // blue
    Color(0xFF80DEEA), // cyan
    Color(0xFFA5D6A7), // green
    Color(0xFFE6EE9C), // lime
    Color(0xFFFFE082), // amber
    Color(0xFFFFAB91), // deep orange
    Color(0xFFF48FB1), // pink
    Color(0xFFCE93D8), // purple
    Color(0xFF9FA8DA), // indigo
    Color(0xFFB0BEC5), // blue grey
)

/**
 * Preset swatches plus a custom entry. [selected] is 0 for the built-in accent,
 * which keeps the first preset shown as active without storing a colour.
 */
@Composable
internal fun AccentPicker(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    val current = if (selected == 0) AccentPresets.first() else Color(selected)
    val custom = selected != 0 && AccentPresets.none { it.toArgb() == selected }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AccentPresets.forEach { preset ->
            Swatch(
                color = preset,
                selected = !custom && preset.toArgb() == current.toArgb(),
                onClick = { onSelect(if (preset == AccentPresets.first()) 0 else preset.toArgb()) },
            )
        }
        Swatch(
            color = if (custom) current else Color.Transparent,
            selected = custom,
            onClick = { picking = true },
            rainbow = !custom,
        )
    }

    if (picking) {
        ColorPickerDialog(
            initial = current,
            onDismiss = { picking = false },
            onConfirm = {
                onSelect(it.toArgb())
                picking = false
            },
        )
    }
}

@Composable
private fun Swatch(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    rainbow: Boolean = false,
) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(
                if (rainbow) {
                    Modifier.background(Brush.sweepGradient(HueWheel))
                } else {
                    Modifier.background(color)
                },
            )
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else outline,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Selected",
                tint = if (color.luminance() > 0.45f) Color.Black else Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private val HueWheel = List(7) { Color.hsv(it * 60f % 360f, 0.7f, 1f) }

@Composable
private fun ColorPickerDialog(
    initial: Color,
    onDismiss: () -> Unit,
    onConfirm: (Color) -> Unit,
) {
    val start = remember(initial) { initial.toHsv() }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var saturation by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    val color = Color.hsv(hue, saturation, value)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Accent colour") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(color),
                )
                SaturationValueField(
                    hue = hue,
                    saturation = saturation,
                    value = value,
                    onChange = { s, v ->
                        saturation = s
                        value = v
                    },
                )
                HueSlider(hue = hue, onChange = { hue = it })
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(color) }) { Text("Select") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SaturationValueField(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (Float, Float) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.hsv(hue, 1f, 1f))
            .background(Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            .trackPointer { position, size ->
                onChange(
                    (position.x / size.width).coerceIn(0f, 1f),
                    1f - (position.y / size.height).coerceIn(0f, 1f),
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawThumb(Offset(saturation * size.width, (1f - value) * size.height))
        }
    }
}

@Composable
private fun HueSlider(hue: Float, onChange: (Float) -> Unit) {
    val spectrum = remember { List(13) { Color.hsv(it * 30f % 360f, 1f, 1f) } }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(CircleShape)
            .background(Brush.horizontalGradient(spectrum))
            .trackPointer { position, size ->
                onChange((position.x / size.width).coerceIn(0f, 1f) * 360f)
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawThumb(Offset(hue / 360f * size.width, size.height / 2f))
        }
    }
}

private fun DrawScope.drawThumb(center: Offset) {
    drawCircle(Color.Black, radius = 9.dp.toPx(), center = center, alpha = 0.35f)
    drawCircle(
        Color.White,
        radius = 8.dp.toPx(),
        center = center,
        style = Stroke(width = 2.dp.toPx()),
    )
}

/**
 * Reports the pointer position on press and for the rest of the drag. Consumes
 * so the surrounding vertical scroll can't steal the gesture mid-adjust.
 */
private fun Modifier.trackPointer(
    onPosition: (Offset, IntSize) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        onPosition(down.position, size)
        down.consume()
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            onPosition(change.position, size)
            change.consume()
        }
    }
}

private fun Color.toHsv(): FloatArray {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    return hsv
}
