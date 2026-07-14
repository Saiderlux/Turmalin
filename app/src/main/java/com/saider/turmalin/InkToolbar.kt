package com.saider.turmalin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup

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

// Marcatextos (v2 1.2): más grueso que la pluma (cubre renglones enteros) y
// con su propio default amarillo — el color de subrayado por antonomasia.
val HIGHLIGHTER_SIZE_RANGE = 6f..48f
const val DEFAULT_HIGHLIGHTER_SIZE = 20f
const val DEFAULT_HIGHLIGHTER_COLOR = 0xFFFDD835.toInt()

// Familias de la pluma (v2 1.1) en la paleta: etiqueta → ordinal serializable.
val PEN_FAMILY_OPTIONS = listOf(
    "Lápiz" to FAMILY_PEN,
    "Pluma" to FAMILY_MARKER,
)

/** Borde de pantalla al que está acoplada la barra de herramientas. */
enum class DockEdge { TOP, BOTTOM, LEFT, RIGHT }

/** ¿La barra se dibuja en vertical en este borde? */
val DockEdge.isVertical: Boolean
    get() = this == DockEdge.LEFT || this == DockEdge.RIGHT

/**
 * Borde más cercano al ASA de la barra al soltarla: decide dónde se acopla.
 * El asa (⠿, esquina superior-izquierda de la barra) es donde está el dedo del
 * usuario — su intención directa. Ni el centro (una barra ancha jamás acerca
 * su centro a un lado) ni la caja (una barra a todo lo ancho "toca" ambos
 * lados a la vez y el empate sale aleatorio) sirven de señal.
 * Función pura para poder testear el snap sin Compose.
 */
fun nearestDockEdge(handleCorner: Offset, canvas: IntSize): DockEdge {
    val distances = mapOf(
        DockEdge.LEFT to handleCorner.x,
        DockEdge.RIGHT to canvas.width - handleCorner.x,
        DockEdge.TOP to handleCorner.y,
        DockEdge.BOTTOM to canvas.height - handleCorner.y,
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
    // Color, grosor y rango de la herramienta que tinta ACTIVA (pluma o
    // marcatextos, v2 1.2): el llamador enruta al estado correcto.
    penColorArgb: Int,
    penSize: Float,
    sizeRange: ClosedFloatingPointRange<Float>,
    // Familia de la pluma (v2 1.1); solo visible con la pluma seleccionada.
    penFamilyOrdinal: Int,
    onFamilySelect: (Int) -> Unit,
    // Lápices pineados (v2 1.4): toque activa, long-press quita, "+" guarda
    // la configuración vigente.
    pins: List<PenPin>,
    onPinSelect: (PenPin) -> Unit,
    onPinAdd: () -> Unit,
    onPinRemove: (PenPin) -> Unit,
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

    // Iconos en vez de texto (v2 3.1): menos ancho ocupado en la Tab S6 Lite;
    // el label sigue disponible con long-press (ver [AppIconButton]).
    val tools: @Composable () -> Unit = {
        DragHandle(onDrag = onDrag, onDragEnd = onDragEnd)
        AppIconButton(
            icon = AppIcons.Pen,
            label = "Pluma",
            selected = highlightedTool == Tool.PEN,
            onClick = { onToolSelect(Tool.PEN) },
        )
        AppIconButton(
            icon = AppIcons.Highlighter,
            label = "Marcatextos",
            selected = highlightedTool == Tool.HIGHLIGHTER,
            onClick = { onToolSelect(Tool.HIGHLIGHTER) },
        )
        AppIconButton(
            icon = AppIcons.EraserStroke,
            label = "Goma trazo",
            selected = highlightedTool == Tool.ERASER_STROKE,
            onClick = { onToolSelect(Tool.ERASER_STROKE) },
        )
        AppIconButton(
            icon = AppIcons.EraserPartial,
            label = "Goma parcial",
            selected = highlightedTool == Tool.ERASER_PARTIAL,
            onClick = { onToolSelect(Tool.ERASER_PARTIAL) },
        )
        AppIconButton(
            icon = AppIcons.Link,
            label = "Lazo de vínculo",
            selected = highlightedTool == Tool.LASSO,
            onClick = { onToolSelect(Tool.LASSO) },
        )
        AppIconButton(
            icon = AppIcons.Select,
            label = "Selección",
            selected = highlightedTool == Tool.SELECT,
            onClick = { onToolSelect(Tool.SELECT) },
        )
        AppIconButton(
            icon = AppIcons.Undo,
            label = "Deshacer",
            selected = false,
            enabled = canUndo,
            onClick = onUndo,
        )
        AppIconButton(
            icon = AppIcons.Redo,
            label = "Rehacer",
            selected = false,
            enabled = canRedo,
            onClick = onRedo,
        )
        // Pines (v2 1.4): siempre visibles para cambiar de lápiz favorito con
        // cualquier herramienta activa. ¿El pin coincide con la config vigente?
        // penColorArgb/penSize ya son los de la herramienta activa.
        for (pin in pins) {
            val pinIsHighlighter = pin.familyOrdinal == FAMILY_HIGHLIGHTER
            val matchesTool =
                pinIsHighlighter == (selectedTool == Tool.HIGHLIGHTER) &&
                    (pinIsHighlighter || pin.familyOrdinal == penFamilyOrdinal)
            PinButton(
                pin = pin,
                selected = selectedTool.drawsInk && matchesTool &&
                    pin.colorArgb == penColorArgb && pin.size == penSize,
                onSelect = { onPinSelect(pin) },
                onRemove = { onPinRemove(pin) },
            )
        }
        if (selectedTool.drawsInk) {
            AddPinButton(onClick = onPinAdd)
        }
    }

    // Familia de la pluma (v2 1.1): chips Lápiz/Pluma, solo con la pluma activa
    // (el marcatextos es herramienta aparte, no una familia de la pluma).
    val familyChips: @Composable () -> Unit = {
        for ((label, ordinal) in PEN_FAMILY_OPTIONS) {
            AppChip(
                label = label,
                selected = ordinal == penFamilyOrdinal,
                onClick = { onFamilySelect(ordinal) },
                modifier = Modifier.padding(2.dp),
            )
        }
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

    // Controles de grosor (RF-03 + v2 1.3): slider continuo + campo numérico
    // para repetir un valor exacto entre sesiones.
    val sizeControls: @Composable () -> Unit = {
        Box(modifier = Modifier.width(160.dp).padding(start = 6.dp)) {
            GraphSlider(
                label = "Grosor",
                value = penSize,
                range = sizeRange,
                onChange = onSizeSelect,
                onCommit = {},
            )
        }
        NumberField(
            value = penSize,
            range = sizeRange,
            onValue = onSizeSelect,
            decimals = 1,
            modifier = Modifier.width(56.dp).padding(start = 6.dp),
        )
    }

    // Barra horizontal: los controles van inline junto a la vista previa.
    val sizeSlider: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SizePreviewWedge(size = penSize, colorArgb = penColorArgb, sizeRange = sizeRange)
            sizeControls()
        }
    }

    // Barra vertical: el slider de 160dp agigantaría la barra — se colapsa tras
    // el punto de vista previa, que al tocarse despliega los controles en un
    // popup horizontal junto a la barra.
    val sizeButton: @Composable () -> Unit = {
        var showSlider by remember { mutableStateOf(false) }
        Box {
            Box(modifier = Modifier.clickable { showSlider = true }) {
                SizePreviewWedge(size = penSize, colorArgb = penColorArgb, sizeRange = sizeRange)
            }
            if (showSlider) {
                Popup(onDismissRequest = { showSlider = false }) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(colors.surface, RoundedCornerShape(12.dp))
                            .border(1.dp, colors.outlineVariant, RoundedCornerShape(12.dp))
                            .padding(8.dp),
                    ) {
                        SizePreviewWedge(size = penSize, colorArgb = penColorArgb, sizeRange = sizeRange)
                        sizeControls()
                    }
                }
            }
        }
    }

    val container = Modifier
        .background(colors.surface, RoundedCornerShape(12.dp))
        .border(1.dp, colors.outlineVariant, RoundedCornerShape(12.dp))
        .padding(6.dp)

    if (vertical) {
        Row(modifier = modifier.then(container)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { tools() }
            if (selectedTool.drawsInk) {
                Column(
                    modifier = Modifier.padding(start = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (selectedTool == Tool.PEN) familyChips()
                    palette()
                    sizeButton()
                }
            }
        }
    } else {
        Column(modifier = modifier.then(container)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { tools() }
            if (selectedTool.drawsInk) {
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selectedTool == Tool.PEN) familyChips()
                    palette()
                    Box(modifier = Modifier.width(10.dp))
                    sizeSlider()
                }
            }
        }
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

/**
 * Vista previa del grosor actual (RF-03): cuña diagonal que empieza delgada y
 * termina gruesa — el lenguaje visual estándar de "grosor de trazo". Botón
 * cuadrado a propósito: el círculo anterior se confundía con una muestra más
 * de la paleta de colores. El extremo grueso escala con el valor del slider.
 */
@Composable
private fun SizePreviewWedge(size: Float, colorArgb: Int, sizeRange: ClosedFloatingPointRange<Float>) {
    val colors = Theme.colors
    Box(
        modifier = Modifier
            .size(30.dp)
            .background(colors.surface, RoundedCornerShape(8.dp))
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(20.dp)) {
            drawSizeWedge(size, sizeRange, colorArgb)
        }
    }
}

/** Cuña diagonal delgada→gruesa cuyo extremo escala con [value] dentro de
 *  [range]. Compartida por la vista previa de grosor y los pines (v2 1.4). */
private fun DrawScope.drawSizeWedge(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    colorArgb: Int,
) {
    val w = size.width
    val h = size.height
    // Fracción del rango, con mínimo visible para que a grosor 1 la cuña siga
    // leyéndose como cuña y no como línea.
    val frac = ((value - range.start) /
        (range.endInclusive - range.start)).coerceIn(0.12f, 1f)
    val thick = h * 0.7f * frac
    val path = Path().apply {
        moveTo(0f, h * 0.9f)                  // punta delgada abajo-izq
        lineTo(w, (h * 0.25f - thick / 2f).coerceAtLeast(0f))
        lineTo(w, h * 0.25f + thick / 2f)
        close()
    }
    drawPath(path, Color(colorArgb))
}

/**
 * Pin de lápiz favorito (v2 1.4): cuña con el color y grosor del pin (los del
 * marcatextos, translúcidos como su tinta). Toque = activar; long-press =
 * quitar el pin. El borde accent marca el pin que coincide con la
 * configuración vigente.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PinButton(
    pin: PenPin,
    selected: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = Theme.colors
    val highlighter = pin.familyOrdinal == FAMILY_HIGHLIGHTER
    val range = if (highlighter) HIGHLIGHTER_SIZE_RANGE else PEN_SIZE_RANGE
    val argb = if (highlighter) {
        (pin.colorArgb and 0x00FFFFFF) or (HIGHLIGHTER_ALPHA shl 24)
    } else {
        pin.colorArgb
    }
    Box(
        modifier = Modifier
            .padding(3.dp)
            .size(30.dp)
            .background(colors.surface, RoundedCornerShape(8.dp))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) colors.accent else colors.outline,
                shape = RoundedCornerShape(8.dp),
            )
            .combinedClickable(onClick = onSelect, onLongClick = onRemove),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(20.dp)) {
            drawSizeWedge(pin.size, range, argb)
        }
    }
}

/** Añade la configuración vigente como pin (v2 1.4). */
@Composable
private fun AddPinButton(onClick: () -> Unit) {
    val colors = Theme.colors
    Box(
        modifier = Modifier
            .padding(3.dp)
            .size(30.dp)
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = "+",
            style = TextStyle(color = colors.textHint, fontSize = AppType.title),
        )
    }
}
