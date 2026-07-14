package com.saider.turmalin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SM-2 simplificado del repaso espaciado (v2 4.3). */
class ReviewSchedulerTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000_000L

    private fun newCard() = ReviewCard(
        id = 1L,
        page = 0,
        frontStrokeIds = listOf(10L),
        frontBbox = listOf(0f, 0f, 1f, 1f),
        dueAtMillis = now,
    )

    @Test
    fun `la progresión con GOOD es 1, 6 y luego intervalo por ease`() {
        var card = reviewCard(newCard(), ReviewGrade.GOOD, now)
        assertEquals(1, card.intervalDays)
        assertEquals(now + day, card.dueAtMillis)
        card = reviewCard(card, ReviewGrade.GOOD, now)
        assertEquals(6, card.intervalDays)
        card = reviewCard(card, ReviewGrade.GOOD, now)
        assertEquals(15, card.intervalDays) // round(6 × 2.5)
        assertEquals(3, card.reps)
    }

    @Test
    fun `AGAIN reinicia el intervalo, baja el ease y deja la tarjeta en la cola`() {
        var card = newCard()
        repeat(3) { card = reviewCard(card, ReviewGrade.GOOD, now) }
        card = reviewCard(card, ReviewGrade.AGAIN, now)
        assertEquals(0, card.intervalDays)
        assertEquals(now, card.dueAtMillis)
        assertEquals(2.3f, card.ease, 1e-4f)
        assertEquals(1, card.lapses)
    }

    @Test
    fun `el ease nunca baja del piso 1_3`() {
        var card = newCard()
        repeat(10) { card = reviewCard(card, ReviewGrade.AGAIN, now) }
        assertEquals(1.3f, card.ease, 1e-4f)
    }

    @Test
    fun `EASY crece más que GOOD y sube el ease`() {
        var good = newCard()
        var easy = newCard()
        repeat(2) {
            good = reviewCard(good, ReviewGrade.GOOD, now)
            easy = reviewCard(easy, ReviewGrade.GOOD, now)
        }
        good = reviewCard(good, ReviewGrade.GOOD, now)
        easy = reviewCard(easy, ReviewGrade.EASY, now)
        assertTrue(easy.intervalDays > good.intervalDays)
        assertTrue(easy.ease > good.ease)
    }
}
