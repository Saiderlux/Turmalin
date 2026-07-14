package com.saider.turmalin

import org.junit.Assert.assertEquals
import org.junit.Test

/** Transformación afín de la selección (v2 sección 5): matemática pura. */
class StrokeTransformTest {

    private val eps = 1e-4f

    @Test
    fun `la transformación identidad no mueve el punto`() {
        val (x, y) = transformPoint(SelectionTransform(), 3f, 7f)
        assertEquals(3f, x, eps)
        assertEquals(7f, y, eps)
    }

    @Test
    fun `la traslación desplaza sin importar el pivote`() {
        val t = SelectionTransform(dx = 10f, dy = -5f, pivotX = 100f, pivotY = 100f)
        val (x, y) = transformPoint(t, 3f, 7f)
        assertEquals(13f, x, eps)
        assertEquals(2f, y, eps)
    }

    @Test
    fun `la escala aleja del pivote y el pivote queda fijo`() {
        val t = SelectionTransform(scale = 2f, pivotX = 10f, pivotY = 10f)
        val (px, py) = transformPoint(t, 10f, 10f)
        assertEquals(10f, px, eps)
        assertEquals(10f, py, eps)
        val (x, y) = transformPoint(t, 15f, 10f)
        assertEquals(20f, x, eps)
        assertEquals(10f, y, eps)
    }

    @Test
    fun `rotar 90 grados alrededor del pivote`() {
        val t = SelectionTransform(rotation = (Math.PI / 2).toFloat(), pivotX = 0f, pivotY = 0f)
        val (x, y) = transformPoint(t, 1f, 0f)
        assertEquals(0f, x, eps)
        assertEquals(1f, y, eps)
    }

    @Test
    fun `traslación y escala se componen en el orden documentado`() {
        // punto' = pivote + s·(punto − pivote) + d
        val t = SelectionTransform(dx = 1f, dy = 2f, scale = 3f, pivotX = 2f, pivotY = 2f)
        val (x, y) = transformPoint(t, 4f, 2f)
        assertEquals(2f + 3f * 2f + 1f, x, eps)
        assertEquals(2f + 0f + 2f, y, eps)
    }

    @Test
    fun `transformBbox re-envuelve las esquinas rotadas`() {
        // Caja 0,0..2,2 rotada 90° sobre su centro (1,1): queda igual.
        val t = SelectionTransform(rotation = (Math.PI / 2).toFloat(), pivotX = 1f, pivotY = 1f)
        val box = transformBbox(t, listOf(0f, 0f, 2f, 2f))
        assertEquals(0f, box[0], eps)
        assertEquals(0f, box[1], eps)
        assertEquals(2f, box[2], eps)
        assertEquals(2f, box[3], eps)
    }

    @Test
    fun `transformBbox con rotación de 45 grados crece a la diagonal`() {
        val t = SelectionTransform(rotation = (Math.PI / 4).toFloat(), pivotX = 0f, pivotY = 0f)
        val box = transformBbox(t, listOf(-1f, -1f, 1f, 1f))
        val half = kotlin.math.sqrt(2f)
        assertEquals(-half, box[0], eps)
        assertEquals(-half, box[1], eps)
        assertEquals(half, box[2], eps)
        assertEquals(half, box[3], eps)
    }
}
