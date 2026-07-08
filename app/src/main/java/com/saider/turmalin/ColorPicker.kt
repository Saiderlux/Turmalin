package com.saider.turmalin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * Selector de color personalizado (RF-04): controles de tono, saturación y
 * brillo con pistas de gradiente ("rueda o similar"), complemento de los
 * colores preestablecidos de la barra — no los sustituye. Sin Material, como
 * el resto de la app.
 */
@Composable
fun ColorPickerDialog(
    initialArgb: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialHsv = remember {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initialArgb, it) }
    }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var sat by remember { mutableFloatStateOf(initialHsv[1]) }
    var value by remember { mutableFloatStateOf(initialHsv[2]) }
    val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))

    fun hsvColor(h: Float, s: Float, v: Float) =
        Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))

    Dialog(onDismissRequest = onDismiss) {
        DialogSurface {
            DialogTitle("Color personalizado")
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Vista previa del color en edición.
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(1.dp, Theme.colors.outline, CircleShape),
                )
                Spacer(modifier = Modifier.width(12.dp))
                BasicText(
                    text = "Tono, saturación y brillo",
                    style = TextStyle(color = Theme.colors.textSecondary, fontSize = AppType.body),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            // Tono: arcoíris completo.
            GradientTrack(
                colors = List(13) { hsvColor(it * 30f, 1f, 1f) },
                fraction = hue / 360f,
                onFraction = { hue = it * 360f },
            )
            // Saturación: del gris (0) al tono puro, con el brillo actual.
            GradientTrack(
                colors = listOf(hsvColor(hue, 0f, value), hsvColor(hue, 1f, value)),
                fraction = sat,
                onFraction = { sat = it },
            )
            // Brillo: de negro al color actual saturado.
            GradientTrack(
                colors = listOf(Color.Black, hsvColor(hue, sat, 1f)),
                fraction = value,
                onFraction = { value = it },
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.align(Alignment.End)) {
                AppButton(label = "Cancelar", onClick = onDismiss, style = ButtonStyle.TEXT)
                AppButton(
                    label = "Usar color",
                    onClick = { onConfirm(argb) },
                    style = ButtonStyle.FILLED,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/** Pista horizontal con gradiente + thumb arrastrable, mapeada a [0,1]. */
@Composable
private fun GradientTrack(
    colors: List<Color>,
    fraction: Float,
    onFraction: (Float) -> Unit,
) {
    val outline = Theme.colors.outline
    // Como en GraphSlider: el pointerInput no se relanza, así que llama a la
    // lambda vigente, no a la de la primera composición.
    val currentOnFraction = rememberUpdatedState(onFraction)
    var widthPx by remember { mutableStateOf(1f) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .height(28.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                fun emit(x: Float) = currentOnFraction.value((x / widthPx).coerceIn(0f, 1f))
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    emit(down.position.x)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.pressed } ?: break
                        emit(change.position.x)
                        change.consume()
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val trackHeight = 14f
            val top = (size.height - trackHeight) / 2f
            drawRoundRect(
                brush = Brush.horizontalGradient(colors),
                topLeft = Offset(0f, top),
                size = androidx.compose.ui.geometry.Size(size.width, trackHeight),
                cornerRadius = CornerRadius(trackHeight / 2f),
            )
            val cx = size.width * fraction.coerceIn(0f, 1f)
            drawCircle(color = Color.White, radius = 13f, center = Offset(cx, size.height / 2f))
            drawCircle(
                color = outline,
                radius = 13f,
                center = Offset(cx, size.height / 2f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
            )
        }
    }
}
