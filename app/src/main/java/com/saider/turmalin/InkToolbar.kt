package com.saider.turmalin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

// Grosores de trazo (RF-03): fino, medio, grueso.
val PEN_SIZES = listOf(2f, 4f, 8f)

/**
 * Barra de herramientas: pluma, las dos gomas y el lazo, más la paleta de color
 * y grosor (RF-03/04) que solo aparece con la pluma seleccionada. Sin Material a
 * propósito, para mantener el estilo del resto de la app.
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
    onToolSelect: (Tool) -> Unit,
    onColorSelect: (Int) -> Unit,
    onSizeSelect: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlightedTool = temporaryEraserTool ?: selectedTool
    Column(modifier = modifier) {
        Row {
            ToolChip(
                label = "Pluma",
                selected = highlightedTool == Tool.PEN,
                onClick = { onToolSelect(Tool.PEN) },
            )
            ToolChip(
                label = "Goma trazo",
                selected = highlightedTool == Tool.ERASER_STROKE,
                onClick = { onToolSelect(Tool.ERASER_STROKE) },
            )
            ToolChip(
                label = "Goma parcial",
                selected = highlightedTool == Tool.ERASER_PARTIAL,
                onClick = { onToolSelect(Tool.ERASER_PARTIAL) },
            )
            ToolChip(
                label = "Lazo",
                selected = highlightedTool == Tool.LASSO,
                onClick = { onToolSelect(Tool.LASSO) },
            )
        }
        // Paleta de color y grosor: solo relevante mientras se escribe.
        if (selectedTool == Tool.PEN) {
            Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (color in PEN_COLORS) {
                    ColorSwatch(
                        colorArgb = color,
                        selected = color == penColorArgb,
                        onClick = { onColorSelect(color) },
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                for (size in PEN_SIZES) {
                    SizeDot(
                        size = size,
                        selected = size == penSize,
                        onClick = { onSizeSelect(size) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    BasicText(
        text = label,
        style = TextStyle(
            color = if (selected) Color.White else Color.Black,
            fontSize = 16.sp,
        ),
        modifier = Modifier
            .padding(4.dp)
            .background(if (selected) Color.Black else Color.White, shape)
            .border(1.dp, Color.Black, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

/** Muestra de color de la paleta (RF-04); el borde grueso marca la selección. */
@Composable
private fun ColorSwatch(colorArgb: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(3.dp)
            .size(30.dp)
            .clip(CircleShape)
            .background(Color(colorArgb))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) Color.Black else Color(0xFFBBBBBB),
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

/** Selector de grosor (RF-03): un punto negro cuyo diámetro crece con el trazo. */
@Composable
private fun SizeDot(size: Float, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(3.dp)
            .size(30.dp)
            .clip(CircleShape)
            .background(Color.White)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) Color.Black else Color(0xFFBBBBBB),
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size((6f + size * 1.6f).dp)
                .clip(CircleShape)
                .background(Color.Black),
        )
    }
}
