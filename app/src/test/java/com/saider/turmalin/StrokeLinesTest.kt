package com.saider.turmalin

import org.junit.Assert.assertEquals
import org.junit.Test

class StrokeLinesTest {

    @Test
    fun `sin trazos no hay lineas`() {
        assertEquals(emptyList<List<Int>>(), groupStrokesIntoLines(emptyList()))
    }

    @Test
    fun `trazos que se solapan verticalmente forman una linea`() {
        val spans = listOf(100f to 140f, 105f to 150f, 95f to 135f)
        assertEquals(1, groupStrokesIntoLines(spans).size)
    }

    @Test
    fun `renglones separados forman lineas distintas en orden de lectura`() {
        val spans = listOf(
            300f to 340f, // segundo renglón
            100f to 140f, // primer renglón
        )
        assertEquals(listOf(listOf(1), listOf(0)), groupStrokesIntoLines(spans))
    }

    @Test
    fun `el punto de la i flotando sobre la linea se une a ella`() {
        val spans = listOf(
            100f to 140f, // cuerpo de la letra
            88f to 92f, // punto de la i, con hueco chico sobre el cuerpo
            102f to 138f,
        )
        assertEquals(1, groupStrokesIntoLines(spans).size)
    }

    @Test
    fun `dentro de la linea el orden es horizontal por span vertical estable`() {
        val spans = listOf(100f to 140f, 101f to 141f)
        val lines = groupStrokesIntoLines(spans)
        assertEquals(1, lines.size)
        assertEquals(2, lines[0].size)
    }
}
