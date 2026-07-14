package com.saider.turmalin

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Tarjeta de repaso espaciado (v2 4.3): una región de tinta (frente) y
 * opcionalmente otra (reverso) referenciadas por IDs de trazo estables, igual
 * que [LinkRegion] — sobreviven a la goma parcial y al lasso de edición. Los
 * bbox son cachés para render/hit-test y se recalculan al guardar la nota.
 * Vive en `annotations.json` (clave `cards`); el ink jamás se toca (RF-23).
 */
data class ReviewCard(
    val id: Long,
    val page: Int,
    val frontStrokeIds: List<Long>,
    val frontBbox: List<Float>,
    // Vacíos = tarjeta de solo-frente (se muestra y se califica el recuerdo).
    val backStrokeIds: List<Long> = emptyList(),
    val backBbox: List<Float> = emptyList(),
    // Estado SM-2. Una tarjeta nueva vence de inmediato (entra a la cola de hoy).
    val ease: Float = 2.5f,
    val intervalDays: Int = 0,
    val dueAtMillis: Long,
    val reps: Int = 0,
    val lapses: Int = 0,
)

/** Calificación del repaso: tres botones, mapeados a SM-2 simplificado. */
enum class ReviewGrade { AGAIN, GOOD, EASY }

private const val DAY_MILLIS = 24L * 60 * 60 * 1000
private const val MIN_EASE = 1.3f

/**
 * SM-2 simplificado (v2 4.3), función pura: devuelve la tarjeta con su estado
 * de repaso avanzado según la calificación.
 *
 * - AGAIN: lapso — el ease baja 0.2 (piso 1.3), el intervalo se reinicia y la
 *   tarjeta se queda en la cola de hoy (due = now).
 * - GOOD: progresión clásica 1 → 6 → round(intervalo × ease) días.
 * - EASY: como GOOD con ease +0.15 y un bonus de ×1.3 sobre el intervalo.
 */
fun reviewCard(card: ReviewCard, grade: ReviewGrade, nowMillis: Long): ReviewCard = when (grade) {
    ReviewGrade.AGAIN -> card.copy(
        ease = max(MIN_EASE, card.ease - 0.2f),
        intervalDays = 0,
        dueAtMillis = nowMillis,
        reps = card.reps + 1,
        lapses = card.lapses + 1,
    )
    ReviewGrade.GOOD, ReviewGrade.EASY -> {
        val ease = if (grade == ReviewGrade.EASY) card.ease + 0.15f else card.ease
        val bonus = if (grade == ReviewGrade.EASY) 1.3f else 1f
        val interval = when {
            card.intervalDays <= 0 -> 1
            card.intervalDays == 1 -> 6
            else -> (card.intervalDays * ease * bonus).roundToInt()
        }
        card.copy(
            ease = ease,
            intervalDays = interval,
            dueAtMillis = nowMillis + interval * DAY_MILLIS,
            reps = card.reps + 1,
        )
    }
}
