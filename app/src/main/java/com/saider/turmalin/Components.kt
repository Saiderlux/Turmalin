package com.saider.turmalin

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import kotlin.math.roundToInt

/**
 * Sistema de componentes compartidos (heurística 4 del informe): un solo botón
 * y un solo chip para toda la app, con estado pressed visible. Sustituyen a los
 * antiguos HeaderButton/PageChip/PaperStyleChip/ToolChip/TagsDialogButton.
 */

enum class ButtonStyle { FILLED, OUTLINE, TEXT, DANGER }

/** Variación del color de fondo mientras el componente está presionado. */
private fun Color.pressed(isDark: Boolean): Color {
    val overlay = if (isDark) Color.White else Color.Black
    val amount = 0.12f
    return Color(
        red = red + (overlay.red - red) * amount,
        green = green + (overlay.green - green) * amount,
        blue = blue + (overlay.blue - blue) * amount,
        alpha = alpha.coerceAtLeast(amount),
    )
}

/** Botón estándar de la app. [FILLED] = acción principal, [OUTLINE] = secundaria,
 *  [TEXT] = acción de diálogo, [DANGER] = destructiva (outline rojo). */
@Composable
fun AppButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.OUTLINE,
    enabled: Boolean = true,
) {
    val colors = Theme.colors
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(8.dp)

    val background = when {
        !enabled -> Color.Transparent
        style == ButtonStyle.FILLED -> colors.accent
        else -> colors.surface
    }.let { if (isPressed && enabled) it.pressed(colors.isDark) else it }
    val content = when {
        !enabled -> colors.disabled
        style == ButtonStyle.FILLED -> colors.onAccent
        style == ButtonStyle.DANGER -> colors.danger
        else -> colors.textPrimary
    }
    val borderColor = when {
        !enabled -> colors.outlineVariant
        style == ButtonStyle.FILLED -> colors.accent
        style == ButtonStyle.DANGER -> colors.danger
        style == ButtonStyle.TEXT -> Color.Transparent
        else -> colors.outline
    }

    BasicText(
        text = label,
        style = TextStyle(
            color = content,
            fontSize = AppType.label,
            fontWeight = if (style == ButtonStyle.TEXT) FontWeight.Medium else FontWeight.Normal,
        ),
        modifier = modifier
            .background(if (style == ButtonStyle.TEXT) Color.Transparent else background, shape)
            .border(1.dp, borderColor, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** Chip seleccionable estándar: herramientas, presets de papel, opciones. */
@Composable
fun AppChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Theme.colors
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(8.dp)
    val background = (if (selected) colors.accent else colors.surface)
        .let { if (isPressed) it.pressed(colors.isDark) else it }
    BasicText(
        text = label,
        style = TextStyle(
            color = if (selected) colors.onAccent else colors.textPrimary,
            fontSize = AppType.label,
        ),
        modifier = modifier
            .background(background, shape)
            .border(1.dp, if (selected) colors.accent else colors.outline, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/**
 * Botón de icono de la barra de herramientas (v2 3.1): mismo lenguaje visual
 * que [AppChip] (fondo accent al seleccionar, estado pressed) pero cuadrado y
 * con un [ImageVector] en vez de texto. Mantener presionado muestra el label
 * en un popup breve — el nombre de la herramienta sigue siendo descubrible.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppIconButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = Theme.colors
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    var showLabel by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val background = (if (selected) colors.accent else colors.surface)
        .let { if (isPressed && enabled) it.pressed(colors.isDark) else it }
    val tint = when {
        !enabled -> colors.disabled
        selected -> colors.onAccent
        else -> colors.textPrimary
    }
    Box(
        modifier = modifier
            .size(38.dp)
            .background(background, shape)
            .border(1.dp, if (selected) colors.accent else colors.outline, shape)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
                onLongClick = { showLabel = true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = rememberVectorPainter(icon),
            contentDescription = label,
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier.size(22.dp),
        )
        if (showLabel) {
            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(0, with(LocalDensity.current) { -44.dp.roundToPx() }),
                onDismissRequest = { showLabel = false },
            ) {
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(1500)
                    showLabel = false
                }
                BasicText(
                    text = label,
                    style = TextStyle(color = colors.textSecondary, fontSize = AppType.body),
                    modifier = Modifier
                        .background(colors.surfaceVariant, RoundedCornerShape(16.dp))
                        .border(1.dp, colors.outlineVariant, RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** Flecha de volver, idéntica en todas las barras superiores. */
@Composable
fun BackArrow(onClick: () -> Unit) {
    BasicText(
        text = "←",
        style = TextStyle(color = Theme.colors.textPrimary, fontSize = AppType.display),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/**
 * Chip de estado pasivo (heurística 1: visibilidad del estado del sistema):
 * texto breve no interactivo — "Nota guardada", "Generando PDF…". No es el
 * componente RF-34 (no ofrece acción ni se auto-descarta con barra): es un
 * indicador de estado, el llamador decide cuándo desaparece.
 */
@Composable
fun StatusChip(text: String, modifier: Modifier = Modifier) {
    val colors = Theme.colors
    BasicText(
        text = text,
        style = TextStyle(color = colors.textSecondary, fontSize = AppType.body),
        modifier = modifier
            .background(colors.surfaceVariant, RoundedCornerShape(16.dp))
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

// --- Campo numérico compartido (RF-06a, v2 1.3) ---

/** Sanea un número tecleado: acepta coma decimal, no parseable ⇒ [fallback],
 *  siempre acotado a [range]. Función pura para testear en JVM. */
fun coerceToRange(text: String, fallback: Float, range: ClosedFloatingPointRange<Float>): Float =
    (text.trim().replace(',', '.').toFloatOrNull() ?: fallback)
        .coerceIn(range.start, range.endInclusive)

private fun formatNumber(value: Float, decimals: Int): String =
    if (decimals == 0) value.roundToInt().toString()
    else String.format(java.util.Locale.US, "%.${decimals}f", value)

/**
 * Campo numérico de un valor acotado (tamaño de página RF-06a, grosor de trazo
 * v2 1.3). El texto tecleado se conserva libre para no pelear con el usuario
 * mientras escribe; solo se proyecta al valor (coercido a [range]) al cambiar.
 * Si el valor cambia desde fuera (p. ej. el slider gemelo), el texto se
 * resincroniza.
 */
@Composable
fun NumberField(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit,
    modifier: Modifier = Modifier,
    decimals: Int = 0,
) {
    val colors = Theme.colors
    var text by remember { mutableStateOf(formatNumber(value, decimals)) }
    // Texto y valor divergen solo cuando el cambio vino de fuera: se resincroniza
    // sin resetear mientras el usuario teclea (su texto ya proyecta este valor).
    if (coerceToRange(text, value, range) != value) text = formatNumber(value, decimals)
    BasicTextField(
        value = text,
        onValueChange = {
            text = it
            onValue(coerceToRange(it, value, range))
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = TextStyle(color = colors.textPrimary, fontSize = AppType.label),
        modifier = modifier
            .border(1.dp, colors.outline, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/** Fila de ajuste con interruptor (v2 3.4): etiqueta + pista/perilla mínimas
 *  sin Material. Toda la fila es tocable. */
@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Theme.colors
    Row(
        modifier = modifier
            .clickable { onToggle(!checked) }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = label,
            style = TextStyle(color = colors.textPrimary, fontSize = AppType.label),
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(width = 46.dp, height = 26.dp)
                .background(
                    if (checked) colors.accent else colors.surfaceVariant,
                    RoundedCornerShape(13.dp),
                )
                .border(1.dp, if (checked) colors.accent else colors.outline, RoundedCornerShape(13.dp))
                .padding(3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(
                        if (checked) colors.onAccent else colors.textHint,
                        RoundedCornerShape(10.dp),
                    ),
            )
        }
    }
}

// --- Hints de primer uso (heurística 10) ---

private const val HINTS_PREFS = "first_use_hints"

/**
 * Texto guía discreto que aparece solo hasta que el usuario lo descarta (una
 * vez por instalación): hace descubribles los gestos invisibles (pan de dos
 * dedos, doble tap, arrastre de nodos, botón del S Pen, Lazo de vínculo) sin
 * construir un tutorial. Extiende el patrón de texto guía del grafo vacío.
 */
@Composable
fun FirstUseHint(hintKey: String, text: String, modifier: Modifier = Modifier) {
    val colors = Theme.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    var visible by remember {
        mutableStateOf(!hintSeen(context, hintKey))
    }
    if (!visible) return
    Row(
        modifier = modifier
            .background(colors.surfaceVariant, RoundedCornerShape(16.dp))
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = text,
            style = TextStyle(color = colors.textSecondary, fontSize = AppType.body),
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(modifier = Modifier.width(10.dp))
        BasicText(
            text = "✕",
            style = TextStyle(color = colors.textHint, fontSize = AppType.body),
            modifier = Modifier.clickable {
                markHintSeen(context, hintKey)
                visible = false
            },
        )
    }
}

fun hintSeen(context: Context, key: String): Boolean =
    context.getSharedPreferences(HINTS_PREFS, Context.MODE_PRIVATE).getBoolean(key, false)

fun markHintSeen(context: Context, key: String) {
    context.getSharedPreferences(HINTS_PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(key, true).apply()
}
