package com.saider.turmalin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolygonContainsTest {

    // Cuadrado (0,0)-(10,10) como lista plana x,y.
    private val square = listOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)

    @Test
    fun `punto dentro del cuadrado`() {
        assertTrue(polygonContains(square, 5f, 5f))
    }

    @Test
    fun `punto fuera del cuadrado`() {
        assertFalse(polygonContains(square, 15f, 5f))
        assertFalse(polygonContains(square, 5f, -1f))
    }

    @Test
    fun `poligono concavo respeta la muesca`() {
        // Forma de "C": la muesca (derecha-centro) queda fuera.
        val c = listOf(0f, 0f, 10f, 0f, 10f, 3f, 3f, 3f, 3f, 7f, 10f, 7f, 10f, 10f, 0f, 10f)
        assertTrue(polygonContains(c, 1f, 5f))
        assertFalse(polygonContains(c, 8f, 5f))
    }

    @Test
    fun `menos de tres vertices nunca contiene`() {
        assertFalse(polygonContains(listOf(0f, 0f, 10f, 10f), 5f, 5f))
        assertFalse(polygonContains(emptyList(), 0f, 0f))
    }
}
