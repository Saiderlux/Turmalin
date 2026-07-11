package com.saider.turmalin

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Iconos de la barra de herramientas (v2 3.1) como ImageVector propios, sin
 * dependencia de material-icons (el set core no trae goma/lazo y el extended
 * pesa de más para 6 iconos). Path data de Material Icons y Material Design
 * Icons (ambos Apache 2.0), viewport 24×24. El fill negro es placeholder: el
 * tinte real lo aplica [AppIconButton] según selección/estado.
 */
object AppIcons {
    // Lápiz (material "edit"): la herramienta de escribir.
    val Pen = icon(
        "pen",
        "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c0.39-0.39 " +
            "0.39-1.02 0-1.41l-2.34-2.34c-0.39-0.39-1.02-0.39-1.41 0l-1.83 1.83 " +
            "3.75 3.75 1.83-1.83z",
    )

    // Goma de trazo completo (mdi "eraser").
    val EraserStroke = icon(
        "eraser-stroke",
        "M16.24 3.56l4.95 4.94c0.78 0.79 0.78 2.05 0 2.84L12 20.53c-1.56 1.56-4.09 " +
            "1.56-5.66 0L2.81 17c-0.78-0.79-0.78-2.05 0-2.84l10.6-10.6c0.79-0.78 " +
            "2.05-0.78 2.83 0M4.22 15.58l3.54 3.53c0.78 0.79 2.04 0.79 2.83 0l3.53-3.53" +
            "-4.95-4.95-4.95 4.95z",
    )

    // Goma parcial (mdi "eraser-variant"): la línea inferior sugiere borrar
    // SOBRE el trazo, no el trazo entero — distinción frente a EraserStroke.
    val EraserPartial = icon(
        "eraser-partial",
        "M15.14 3c-0.51 0-1.02 0.19-1.41 0.59L2.59 14.73c-0.78 0.77-0.78 2.04 0 " +
            "2.83L5.03 20h7.66l8.72-8.73c0.79-0.77 0.79-2.04 0-2.83l-4.85-4.85" +
            "c-0.39-0.4-0.91-0.59-1.42-0.59M17 18l-2 2h7v-2",
    )

    // Lazo de vínculo (material "link"): el icono comunica el propósito (crear
    // vínculos), no el gesto — reserva espacio visual para el lasso de edición
    // de v2 (sección 5 del plan), que deberá ser inconfundible frente a este.
    val Link = icon(
        "link",
        "M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7" +
            "c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 " +
            "3.1 3.1s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z",
    )

    // Deshacer/rehacer (material "undo"/"redo", RF-37).
    val Undo = icon(
        "undo",
        "M12.5 8c-2.65 0-5.05 0.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 " +
            "5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-0.78C21.08 11.03 17.15 8 12.5 8z",
    )
    val Redo = icon(
        "redo",
        "M18.4 10.6C16.55 8.99 14.15 8 11.5 8c-4.65 0-8.58 3.03-9.96 7.22L3.9 16" +
            "c1.05-3.19 4.05-5.5 7.6-5.5 1.95 0 3.73 0.72 5.12 1.88L13 16h9V7l-3.6 3.6z",
    )
}

private fun icon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
        .addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
        .build()
