package com.saider.turmalin

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.GregorianCalendar

class PdfFileNameTest {

    private fun millisFor(year: Int, month: Int, day: Int): Long =
        GregorianCalendar().apply {
            clear()
            set(year, month - 1, day)
        }.timeInMillis

    @Test
    fun `usa el titulo y la fecha`() {
        assertEquals(
            "Ideas 2026-07-07.pdf",
            suggestedPdfFileName("Ideas", millisFor(2026, 7, 7)),
        )
    }

    @Test
    fun `sanitiza caracteres invalidos en nombres de archivo`() {
        assertEquals(
            "a_b_c 2026-07-07.pdf",
            suggestedPdfFileName("a/b:c", millisFor(2026, 7, 7)),
        )
    }

    @Test
    fun `titulo en blanco cae a nota`() {
        assertEquals(
            "nota 2026-07-07.pdf",
            suggestedPdfFileName("   ", millisFor(2026, 7, 7)),
        )
    }
}
