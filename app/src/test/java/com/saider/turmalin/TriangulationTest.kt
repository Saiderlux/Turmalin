package com.saider.turmalin

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TriangulationTest {

    // Área total de una triangulación (suma de áreas de sus triángulos).
    private fun totalArea(poly: List<Offset>, tris: List<Triple<Int, Int, Int>>): Float =
        tris.sumOf { triangleArea(poly[it.first], poly[it.second], poly[it.third]).toDouble() }
            .toFloat()

    @Test
    fun `menos de tres vertices no se triangula`() {
        assertTrue(triangulatePolygon(emptyList()).isEmpty())
        assertTrue(triangulatePolygon(listOf(Offset(0f, 0f), Offset(1f, 1f))).isEmpty())
    }

    @Test
    fun `un triangulo da un triangulo con su area`() {
        val poly = listOf(Offset(0f, 0f), Offset(10f, 0f), Offset(0f, 10f))
        val tris = triangulatePolygon(poly)
        assertEquals(1, tris.size)
        assertEquals(50f, totalArea(poly, tris), 1e-3f)
    }

    @Test
    fun `un cuadrado convexo da dos triangulos que cubren toda el area`() {
        val poly = listOf(
            Offset(0f, 0f), Offset(10f, 0f), Offset(10f, 10f), Offset(0f, 10f),
        )
        val tris = triangulatePolygon(poly)
        assertEquals(2, tris.size)
        assertEquals(100f, totalArea(poly, tris), 1e-3f)
    }

    @Test
    fun `un poligono concavo en L se triangula sin exceder su area`() {
        // "L": área = 100 - 25 (la muesca superior-derecha) = 75.
        val poly = listOf(
            Offset(0f, 0f), Offset(10f, 0f), Offset(10f, 5f),
            Offset(5f, 5f), Offset(5f, 10f), Offset(0f, 10f),
        )
        val tris = triangulatePolygon(poly)
        // Un polígono simple de n vértices se triangula en exactamente n-2.
        assertEquals(poly.size - 2, tris.size)
        // La suma de áreas iguala el área del polígono (shoelace) — prueba de que
        // los triángulos teselan el interior sin solaparse ni salirse.
        val polyArea = abs(polygonSignedArea(poly))
        assertEquals(polyArea, totalArea(poly, tris), 1e-2f)
        assertEquals(75f, polyArea, 1e-2f)
    }

    @Test
    fun `una flecha concava mantiene el area total`() {
        // Punta de flecha con muesca (cóncava, no estelar): reto para fan.
        val poly = listOf(
            Offset(0f, 0f), Offset(10f, 5f), Offset(0f, 10f), Offset(3f, 5f),
        )
        val tris = triangulatePolygon(poly)
        assertEquals(poly.size - 2, tris.size)
        assertEquals(abs(polygonSignedArea(poly)), totalArea(poly, tris), 1e-2f)
    }

    @Test
    fun `orientacion horaria se triangula igual que antihoraria`() {
        val ccw = listOf(Offset(0f, 0f), Offset(10f, 0f), Offset(10f, 10f), Offset(0f, 10f))
        val cw = ccw.reversed()
        assertEquals(
            abs(polygonSignedArea(ccw)),
            totalArea(cw, triangulatePolygon(cw)),
            1e-3f,
        )
    }

    @Test
    fun `pointInTriangle distingue dentro y fuera`() {
        val a = Offset(0f, 0f)
        val b = Offset(10f, 0f)
        val c = Offset(0f, 10f)
        assertTrue(pointInTriangle(Offset(1f, 1f), a, b, c))
        assertTrue(!pointInTriangle(Offset(9f, 9f), a, b, c))
    }
}
