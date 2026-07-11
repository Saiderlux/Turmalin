package com.saider.turmalin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

// Paleta de plumas (RF-04): 8 colores básicos. Excluye a propósito el azul
// reservado del overlay de links (RF-23a) para que la tinta no se confunda con
// una región linkeada.
val PEN_COLORS = listOf(
    0xFF000000.toInt(), // Negro
    0xFF808080.toInt(), // Gris
    0xFFFFFFFF.toInt(), // Blanco
    0xFFE53935.toInt(), // Rojo
    0xFF00BCD4.toInt(), // Cian
    0xFF43A047.toInt(), // Verde
    0xFFEC407A.toInt(), // Rosa
    0xFFFDD835.toInt(), // Amarillo
)

// Grosor de trazo (RF-03): slider continuo dentro de este rango.
val PEN_SIZE_RANGE = 1f..16f
const val DEFAULT_PEN_SIZE = 4f

/** Borde de pantalla al que está acoplada la barra de herramientas. */
enum class DockEdge { TOP, BOTTOM, LEFT, RIGHT }

/** ¿La barra se dibuja en vertical en este borde? */
val DockEdge.isVertical: Boolean
    get() = this == DockEdge.LEFT || this == DockEdge.RIGHT

/**
 * Borde más cercano al centro de la barra al soltarla: decide dónde se acopla.
 * Función pura para poder testear el snap sin Compose.
 */
fun nearestDockEdge(center: Offset, canvas: IntSize): DockEdge {
    val distances = mapOf(
        DockEdge.LEFT to center.x,
        DockEdge.RIGHT to canvas.width - center.x,
        DockEdge.TOP to center.y,
        DockEdge.BOTTOM to canvas.height - center.y,
    )
    return distances.minBy { it.value }.key
}

/**
 * Barra de herramientas acoplable: pluma, las dos gomas y el Lazo de vínculo
 * (RF-17), más la paleta de color y grosor (RF-03/04) que solo aparece con la
 * pluma seleccionada. El asa (⠿) permite arrastrarla; al soltarla se acopla al
 * borde más cercano ([nearestDockEdge]) y se orienta horizontal o vertical
 * según el borde.
 *
 * [temporaryEraserTool] refleja el atajo del botón del S Pen (RF-05c): mientras
 * el botón está presionado se resalta la goma que el atajo está usando (la última
 * usada), aunque la selección persistente siga siendo otra herramienta.
 */
@Composable
fun InkToolbar(
    selectedTool: Tool,
    temporaryEraserTool: Tool?,
    penColorArgb: Int,
    penSize: Float,
    // RF-37: deshacer/rehacer de ink desde la barra (sin gestos táctiles, que
    // chocarían con el pan/zoom de RF-09a/09b). Atenuados sin pasos disponibles.
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToolSelect: (Tool) -> Unit,
    onColorSelect: (Int) -> Unit,
    onSizeSelect: (Float) -> Unit,
    // RF-04: abre el selector de color personalizado (el diálogo vive en el
    // canvas; la barra solo lo invoca).
    onOpenColorPicker: () -> Unit,
    vertical: Boolean,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Theme.colors
    val highlightedTool = temporaryEraserTool ?: selectedTool

    val tools: @Composable () -> Unit = {
        DragHandle(onDrag = onDrag, onDragEnd = onDragEnd)
        AppChip(
            label = "Pluma",
            selected = highlightedTool == Tool.PEN,
            onClick = { onToolSelect(Tool.PEN) },
        )
        AppChip(
            label = "Goma trazo",
            selected = highlightedTool == Tool.ERASER_STROKE,
            onClick = { onToolSelect(Tool.ERASER_STROKE) },
        )
        AppChip(
            label = "Goma parcial",
            selected = highlightedTool == Tool.ERASER_PARTIAL,
            onClick = { onToolSelect(Tool.ERASER_PARTIAL) },
        )
        AppChip(
            label = "Lazo de vínculo",
            selected = highlightedTool == Tool.LASSO,
            onClick = { onToolSelect(Tool.LASSO) },
        )
        HistoryButton(label = "↶", enabled = canUndo, onClick = onUndo)
        HistoryButton(label = "↷", enabled = canRedo, onClick = onRedo)
    }

    // Paleta de color y grosor: solo relevante mientras se escribe. Los
    // preestablecidos son accesos rápidos (RF-04); la rueda abre el selector
    // personalizado y muestra el color activo cuando no es un preset.
    val palette: @Composable () -> Unit = {
        for (color in PEN_COLORS) {
            ColorSwatch(
                colorArgb = color,
                selected = color == penColorArgb,
                onClick = { onColorSelect(color) },
            )
        }
        CustomColorSwatch(
            penColorArgb = penColorArgb,
            selected = penColorArgb !in PEN_COLORS,
            onClick = onOpenColorPicker,
        )
    }

    // Grosor continuo (RF-03): slider + punto de vista previa del grosor actual,
    // más campo numérico (v2 1.3) para repetir un valor exacto entre sesiones.
    val sizeSlider: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SizePreviewDot(size = penSize, colorArgb = penColorArgb)
            Box(modifier = Modifier.width(160.dp).padding(start = 6.dp)) {
                GraphSlider(
                    label = "Grosor",
                    value = penSize,
                    range = PEN_SIZE_RANGE,
                    onChange = onSizeSelect,
                    onCommit = {},
                )
            }
            NumberField(
                value = penSize,
                range = PEN_SIZE_RANGE,
                onValue = onSizeSelect,
                decimals = 1,
                modifier = Modifier.width(56.dp).padding(start = 6.dp),
            )
        }
    }

    val container = Modifier
        .background(colors.surface, RoundedCornerShape(12.dp))
        .border(1.dp, colors.outlineVariant, RoundedCornerShape(12.dp))
        .padding(6.dp)

    if (vertical) {
        Row(modifier = modifier.then(container)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { tools() }
            if (selectedTool == Tool.PEN) {
                Column(
                    modifier = Modifier.padding(start = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    palette()
                    sizeSlider()
                }
            }
        }
    } else {
        Column(modifier = modifier.then(container)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { tools() }
            if (selectedTool == Tool.PEN) {
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    palette()
                    Box(modifier = Modifier.width(10.dp))
                    sizeSlider()
                }
            }
        }
    }
}

/** Botón de deshacer/rehacer (RF-37): atenuado cuando no hay pasos que aplicar. */
@Composable
private fun HistoryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = Theme.colors
    Box(
        modifier = Modifier
            .padding(3.dp)
            .size(30.dp)
            .clip(CircleShape)
            .border(1.dp, colors.outlineVariant, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                color = if (enabled) colors.textPrimary else colors.disabled,
                fontSize = AppType.title,
            ),
        )
    }
}

/** Asa de arrastre de la barra: el único punto que inicia el acople. */
@Composable
private fun DragHandle(
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    BasicText(
        text = "⠿",
        style = TextStyle(color = Theme.colors.textHint, fontSize = AppType.title),
        modifier = Modifier
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, amount ->
                        change.consume()
                        onDrag(amount)
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                )
            },
    )
}

/** Muestra de color de la paleta (RF-04); el borde grueso marca la selección. */
@Composable
private fun ColorSwatch(colorArgb: Int, selected: Boolean, onClick: () -> Unit) {
    val colors = Theme.colors
    Box(
        modifier = Modifier
            .padding(3.dp)
            .size(30.dp)
            .clip(CircleShape)
            .background(Color(colorArgb))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) colors.textPrimary else colors.disabled,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

/** Rueda de color (RF-04): abre el selector personalizado. Cuando el color
 *  activo no es un preset, muestra un anillo con ese color como seleccionado. */
@Composable
private fun CustomColorSwatch(penColorArgb: Int, selected: Boolean, onClick: () -> Unit) {
    val colors = Theme.colors
    val rainbow = remember {
        Brush.sweepGradient(
            List(13) { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 30f, 1f, 1f))) }
        )
    }
    Box(
        modifier = Modifier
            .padding(3.dp)
            .size(30.dp)
            .clip(CircleShape)
            .background(rainbow)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) colors.textPrimary else colors.disabled,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Centro: el color personalizado activo, o hueco neutro si no hay.
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(if (selected) Color(penColorArgb) else colors.surface),
        )
    }
}

/** Vista previa del grosor actual (RF-03): punto que crece con el slider. */
@Composable
private fun SizePreviewDot(size: Float, colorArgb: Int) {
    val colors = Theme.colors
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(colors.surface)
            .border(1.dp, colors.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size((4f + size * 1.4f).dp)
                .clip(CircleShape)
                .background(Color(colorArgb)),
        )
    }
}
