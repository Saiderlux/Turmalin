package com.saider.turmalin

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
