package com.saider.turmalin

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

/**
 * Tokens de color de la app. Sin Material a propósito (decisión del proyecto):
 * el theming es un CompositionLocal propio sobre foundation. Toda pantalla debe
 * leer colores de aquí — nunca Color.White/Black ni grises hardcodeados.
 */
data class AppColors(
    val isDark: Boolean,
    /** Fondo de pantalla completa. */
    val background: Color,
    /** Superficies elevadas: diálogos, sheets, popups, tarjetas. */
    val surface: Color,
    /** Superficie alterna: tarjetas de cuaderno, chips de tag, panel del grafo. */
    val surfaceVariant: Color,
    /** Texto y contenido principal. */
    val textPrimary: Color,
    /** Texto secundario: fechas, contadores, subtítulos. */
    val textSecondary: Color,
    /** Placeholders y hints. */
    val textHint: Color,
    /** Contenido deshabilitado. */
    val disabled: Color,
    /** Bordes de campos y botones outline. */
    val outline: Color,
    /** Bordes tenues: tarjetas, separadores. */
    val outlineVariant: Color,
    /** Relleno de la acción principal (botón filled, chip seleccionado). */
    val accent: Color,
    /** Contenido sobre [accent]. */
    val onAccent: Color,
    /** Acciones destructivas. */
    val danger: Color,
    /** Scrim de sheets. */
    val scrim: Color,
    /** Fondo gris del lienzo alrededor de la hoja (la hoja sigue blanca:
     *  la tinta es WYSIWYG con el PDF y la paleta incluye tinta blanca). */
    val canvasBackdrop: Color,
)

val LightAppColors = AppColors(
    isDark = false,
    background = Color.White,
    surface = Color.White,
    surfaceVariant = Color(0xFFF2F2F2),
    textPrimary = Color.Black,
    textSecondary = Color(0xFF757575),
    textHint = Color(0xFF9E9E9E),
    disabled = Color(0xFFBDBDBD),
    outline = Color(0xFFCCCCCC),
    outlineVariant = Color(0xFFE0E0E0),
    accent = Color.Black,
    onAccent = Color.White,
    danger = Color(0xFFB00020),
    scrim = Color(0x66000000),
    canvasBackdrop = Color(0xFFEDEDED),
)

val DarkAppColors = AppColors(
    isDark = true,
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    surfaceVariant = Color(0xFF2A2A2A),
    textPrimary = Color(0xFFECECEC),
    textSecondary = Color(0xFFA8A8A8),
    textHint = Color(0xFF808080),
    disabled = Color(0xFF5A5A5A),
    outline = Color(0xFF4A4A4A),
    outlineVariant = Color(0xFF333333),
    accent = Color(0xFFECECEC),
    onAccent = Color(0xFF121212),
    danger = Color(0xFFEF5350),
    scrim = Color(0x99000000),
    canvasBackdrop = Color(0xFF262626),
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }

/**
 * Escala tipográfica única de la app: 12/14/16/20/24. Ningún composable debe
 * usar un tamaño de fuente fuera de esta escala.
 */
object AppType {
    /** Fechas, contadores, texto de apoyo. */
    val caption = 12.sp
    /** Texto secundario, hints, avisos. */
    val body = 14.sp
    /** Texto de contenido, botones, campos. */
    val label = 16.sp
    /** Títulos de sección, diálogos y pantallas secundarias. */
    val title = 20.sp
    /** Título de la pantalla principal. */
    val display = 24.sp
}

/** Tema de la app: paleta clara u oscura según el sistema. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkAppColors else LightAppColors
    CompositionLocalProvider(LocalAppColors provides colors, content = content)
}

/** Acceso corto a los tokens del tema activo. */
object Theme {
    val colors: AppColors
        @Composable get() = LocalAppColors.current
}
