package com.saider.turmalin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

/**
 * Componente único de notificación no bloqueante (RF-34). Todos los casos de
 * sugerencia o deshacer (título, dividir nota, link eliminado, futuros) usan
 * este mismo componente — no crear variantes ad hoc.
 *
 * Aparece en la esquina inferior, muestra una barra de progreso con el tiempo
 * restante, ofrece una única acción contextual y se descarta solo al agotarse
 * el tiempo. Nunca bloquea el canvas ni intercepta gestos de ink.
 */
@Composable
fun TransientNotice(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    durationMillis: Int = 4500,
) {
    var remainingFraction by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(Unit) {
        val startMillis = System.currentTimeMillis()
        while (true) {
            kotlinx.coroutines.delay(50)
            val elapsed = System.currentTimeMillis() - startMillis
            val fraction = 1f - elapsed.toFloat() / durationMillis
            if (fraction <= 0f) {
                onDismiss()
                break
            }
            remainingFraction = fraction
        }
    }

    Column(
        modifier = modifier
            .padding(16.dp)
            .width(320.dp)
            .zIndex(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xEE222222)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = message,
                style = TextStyle(color = Color.White, fontSize = 14.sp),
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(12.dp))
            BasicText(
                text = actionLabel,
                style = TextStyle(
                    color = Color(0xFF80CBC4),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = Modifier.clickable(onClick = onAction),
            )
        }
        // Barra de progreso del tiempo restante antes del auto-descarte.
        Box(
            modifier = Modifier
                .fillMaxWidth(remainingFraction)
                .height(3.dp)
                .background(Color(0xFF80CBC4)),
        )
    }
}
