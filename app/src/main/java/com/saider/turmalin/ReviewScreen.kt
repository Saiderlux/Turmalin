package com.saider.turmalin

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

// Ancho del render de una región de tarjeta, en px de bitmap: nítido en la
// Tab S6 Lite sin cargar bitmaps de página completa.
private const val CARD_RENDER_WIDTH_PX = 900

/**
 * Cola de repaso espaciado (v2 4.3): recorre las tarjetas vencidas del vault,
 * una a la vez — frente renderizado desde la tinta, reverso opcional tras
 * «Mostrar respuesta», y calificación Otra vez / Bien / Fácil (SM-2, ver
 * [reviewCard]). Pasiva a propósito: sin notificaciones del sistema, la cola
 * se visita desde la galería (RF-32: cero red, cero permisos nuevos).
 */
@Composable
fun ReviewScreen(
    repo: NoteRepository,
    onOpenNote: (NoteMeta) -> Unit,
    onBack: () -> Unit,
) {
    val colors = Theme.colors
    val brush = remember { defaultBlackPen() }
    // Cola de la sesión: vencidas al entrar, más antiguas primero. AGAIN
    // reencola al final de la misma sesión.
    val queue = remember {
        mutableStateListOf<Pair<NoteMeta, ReviewCard>>().apply {
            addAll(repo.dueCards(System.currentTimeMillis()))
        }
    }
    var revealed by remember { mutableStateOf(false) }
    val current = queue.firstOrNull()

    // Persiste la calificación y avanza la cola.
    fun grade(grade: ReviewGrade) {
        val (meta, card) = current ?: return
        val updated = reviewCard(card, grade, System.currentTimeMillis())
        repo.saveCards(
            meta.uuid,
            repo.loadCards(meta.uuid).map { if (it.id == card.id) updated else it },
        )
        queue.removeAt(0)
        // Otra vez: la tarjeta sigue venciendo hoy — vuelve al final de la cola.
        if (grade == ReviewGrade.AGAIN) queue.add(meta to updated)
        revealed = false
    }

    fun deleteCard() {
        val (meta, card) = current ?: return
        repo.saveCards(meta.uuid, repo.loadCards(meta.uuid).filter { it.id != card.id })
        queue.removeAll { (m, c) -> m.uuid == meta.uuid && c.id == card.id }
        revealed = false
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackArrow(onClick = onBack)
            BasicText(
                text = "Repaso",
                style = TextStyle(
                    color = colors.textPrimary,
                    fontSize = AppType.display,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            if (current != null) {
                BasicText(
                    text = "${queue.size} pendiente${if (queue.size == 1) "" else "s"}",
                    style = TextStyle(color = colors.textSecondary, fontSize = AppType.label),
                )
            }
        }

        if (current == null) {
            // Cola vacía (heurística 1): estado explícito con el próximo
            // vencimiento para que "no hay nada" no parezca un error.
            val next = remember { repo.nextDueAt() }
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                BasicText(
                    text = "Sin tarjetas pendientes",
                    style = TextStyle(color = colors.textPrimary, fontSize = AppType.title),
                )
                Spacer(modifier = Modifier.height(8.dp))
                BasicText(
                    text = next?.let {
                        "Próximo repaso: ${DateFormat.getDateInstance().format(Date(it))}"
                    } ?: "Crea tarjetas rodeando tinta con «Selección» dentro de una nota",
                    style = TextStyle(color = colors.textSecondary, fontSize = AppType.label),
                )
            }
        } else {
            val (meta, card) = current
            ReviewCardBody(
                repo = repo,
                meta = meta,
                card = card,
                brush = brush,
                revealed = revealed,
                onReveal = { revealed = true },
                onGrade = ::grade,
                onOpenNote = { onOpenNote(meta) },
                onDelete = ::deleteCard,
            )
        }
    }
}

/** Frente (y reverso revelado) de la tarjeta actual, con sus acciones. */
@Composable
private fun ReviewCardBody(
    repo: NoteRepository,
    meta: NoteMeta,
    card: ReviewCard,
    brush: androidx.ink.brush.Brush,
    revealed: Boolean,
    onReveal: () -> Unit,
    onGrade: (ReviewGrade) -> Unit,
    onOpenNote: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = Theme.colors
    val hasBack = card.backStrokeIds.isNotEmpty()

    // Render de las regiones fuera del hilo de UI; se recalcula por tarjeta.
    val front by produceState<ImageBitmap?>(initialValue = null, card.id) {
        value = withContext(Dispatchers.IO) {
            val strokes = repo.loadStrokes(meta.uuid, card.page, brush)
                .filter { it.id in card.frontStrokeIds }
            if (strokes.isEmpty()) null
            else renderRegion(strokes, card.frontBbox, CARD_RENDER_WIDTH_PX).asImageBitmap()
        }
    }
    val back by produceState<ImageBitmap?>(initialValue = null, card.id, revealed) {
        value = if (!revealed || !hasBack) null else withContext(Dispatchers.IO) {
            val strokes = repo.loadStrokes(meta.uuid, card.page, brush)
                .filter { it.id in card.backStrokeIds }
            if (strokes.isEmpty()) null
            else renderRegion(strokes, card.backBbox, CARD_RENDER_WIDTH_PX).asImageBitmap()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            text = "De «${meta.title}»",
            style = TextStyle(color = colors.textSecondary, fontSize = AppType.label),
            modifier = Modifier.padding(bottom = 12.dp),
        )
        front?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = "Frente de la tarjeta",
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (hasBack && !revealed) {
            AppButton(label = "Mostrar respuesta", onClick = onReveal, style = ButtonStyle.FILLED)
        } else {
            back?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = "Reverso de la tarjeta",
                    modifier = Modifier
                        .widthIn(max = 480.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            // Calificación SM-2 (v2 4.3): tres botones, sin escala 0-5.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppButton(label = "Otra vez", onClick = { onGrade(ReviewGrade.AGAIN) })
                AppButton(
                    label = "Bien",
                    onClick = { onGrade(ReviewGrade.GOOD) },
                    style = ButtonStyle.FILLED,
                )
                AppButton(label = "Fácil", onClick = { onGrade(ReviewGrade.EASY) })
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppButton(label = "Abrir nota", onClick = onOpenNote, style = ButtonStyle.TEXT)
            AppButton(label = "Eliminar tarjeta", onClick = onDelete, style = ButtonStyle.DANGER)
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}
