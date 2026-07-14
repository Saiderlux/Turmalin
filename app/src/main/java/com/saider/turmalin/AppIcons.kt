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

    // Marcatextos (mdi "marker", v2 1.2): rotulador inclinado.
    val Highlighter = icon(
        "highlighter",
        "M18.5 1.15c-0.53 0-1.04 0.19-1.43 0.58l-5.81 5.82 5.65 5.65 5.82-5.81" +
            "c0.77-0.78 0.77-2.04 0-2.83l-2.84-2.83c-0.39-0.39-0.89-0.58-1.39-0.58" +
            "M10.3 8.5l-5.96 5.96c-0.78 0.78-0.78 2.04 0.02 2.85-1.22 1.23-2.46 " +
            "2.46-3.69 3.69h5.66l0.86-0.86c0.78 0.76 2.03 0.75 2.81-0.02l5.95-5.96" +
            "-5.65-5.66z",
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

    // Selección (v2 sección 5): recuadro punteado (marching ants) con cursor —
    // dibujado a mano para que sea inconfundible frente al Lazo de vínculo.
    val Select = icon(
        "select",
        "M3 3h3v2H3zM8 3h3v2H8zM13 3h3v2h-3zM18 3h3v2h-3zM3 8h2v3H3zM19 8h2v3" +
            "h-2zM3 13h2v3H3zM3 18h3v2H3zM8 19h3v2H8zM13 13l8 3.5-3.5 1-1 3.5z",
    )

    // Vista de tabla / cuadrícula (material "view_list" y "grid_view", v2 4.2):
    // el botón muestra la vista a la que se cambiaría.
    val ViewList = icon(
        "view-list",
        "M4 14h4v-4H4v4zm0 5h4v-4H4v4zM4 9h4V5H4v4zm5 5h12v-4H9v4zm0 5h12v-4H9" +
            "v4zM9 5v4h12V5H9z",
    )
    val GridView = icon(
        "grid-view",
        "M3 3v8h8V3H3zm6 6H5V5h4v4zm-6 4v8h8v-8H3zm6 6H5v-4h4v4zm4-16v8h8V3h-8z" +
            "m6 6h-4V5h4v4zm-6 4v8h8v-8h-8zm6 6h-4v-4h4v4z",
    )

    // Ajustes de la app (material "settings", v2 3.4).
    val Settings = icon(
        "settings",
        "M19.14 12.94c0.04-0.3 0.06-0.61 0.06-0.94 0-0.32-0.02-0.64-0.07-0.94" +
            "l2.03-1.58c0.18-0.14 0.23-0.41 0.12-0.61l-1.92-3.32c-0.12-0.22-0.37" +
            "-0.29-0.59-0.22l-2.39 0.96c-0.5-0.38-1.03-0.7-1.62-0.94L14.4 2.81" +
            "c-0.04-0.24-0.24-0.41-0.48-0.41h-3.84c-0.24 0-0.43 0.17-0.47 0.41" +
            "L9.25 5.35C8.66 5.59 8.12 5.92 7.63 6.29L5.24 5.33c-0.22-0.08-0.47 " +
            "0-0.59 0.22L2.74 8.87c-0.12 0.21-0.08 0.47 0.12 0.61l2.03 1.58" +
            "c-0.05 0.3-0.09 0.63-0.09 0.94s0.02 0.64 0.07 0.94l-2.03 1.58" +
            "c-0.18 0.14-0.23 0.41-0.12 0.61l1.92 3.32c0.12 0.22 0.37 0.29 0.59 " +
            "0.22l2.39-0.96c0.5 0.38 1.03 0.7 1.62 0.94l0.36 2.54c0.05 0.24 0.24 " +
            "0.41 0.48 0.41h3.84c0.24 0 0.44-0.17 0.47-0.41l0.36-2.54c0.59-0.24 " +
            "1.13-0.56 1.62-0.94l2.39 0.96c0.22 0.08 0.47 0 0.59-0.22l1.92-3.32" +
            "c0.12-0.22 0.07-0.47-0.12-0.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62" +
            "-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z",
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
