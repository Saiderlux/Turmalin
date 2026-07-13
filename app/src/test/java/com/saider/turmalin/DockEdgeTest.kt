package com.saider.turmalin

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Snap de la barra acoplable: el ASA al soltar (el dedo del usuario) decide el
 * borde — ni el centro (una barra ancha jamás lo acerca a un lado) ni la caja
 * (una barra a todo lo ancho toca ambos lados y el empate sale aleatorio).
 */
class DockEdgeTest {

    private val canvas = IntSize(1000, 800)

    @Test
    fun `asa cerca de cada borde acopla a ese borde`() {
        assertEquals(DockEdge.LEFT, nearestDockEdge(Offset(50f, 400f), canvas))
        assertEquals(DockEdge.RIGHT, nearestDockEdge(Offset(950f, 400f), canvas))
        assertEquals(DockEdge.TOP, nearestDockEdge(Offset(500f, 40f), canvas))
        assertEquals(DockEdge.BOTTOM, nearestDockEdge(Offset(500f, 760f), canvas))
    }

    @Test
    fun `asa arrastrada al lado izquierdo a media altura acopla izquierda`() {
        // El gesto natural del usuario: dedo al borde, altura media — el caso
        // que el criterio del centro hacía casi imposible con la barra ancha.
        assertEquals(DockEdge.LEFT, nearestDockEdge(Offset(30f, 420f), canvas))
    }

    @Test
    fun `esquina resuelve al borde estrictamente mas cercano`() {
        // Más cerca del tope (30px) que de la izquierda (60px).
        assertEquals(DockEdge.TOP, nearestDockEdge(Offset(60f, 30f), canvas))
    }

    @Test
    fun `orientacion vertical solo en bordes laterales`() {
        assertEquals(true, DockEdge.LEFT.isVertical)
        assertEquals(true, DockEdge.RIGHT.isVertical)
        assertEquals(false, DockEdge.TOP.isVertical)
        assertEquals(false, DockEdge.BOTTOM.isVertical)
    }
}
