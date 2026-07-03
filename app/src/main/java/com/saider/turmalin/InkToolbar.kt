package com.saider.turmalin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Barra mínima de herramientas: pluma y las dos gomas. Sin Material a
 * propósito — la UI definitiva de herramientas llega con la Fase 6 del roadmap.
 *
 * [temporaryEraserTool] refleja el atajo del botón del S Pen (RF-05c): mientras
 * el botón está presionado se resalta la goma que el atajo está usando (la
 * última usada), aunque la selección persistente siga siendo otra herramienta.
 */
@Composable
fun InkToolbar(
    selectedTool: Tool,
    temporaryEraserTool: Tool?,
    onToolSelect: (Tool) -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlightedTool = temporaryEraserTool ?: selectedTool
    Row(modifier = modifier) {
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
