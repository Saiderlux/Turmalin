package com.saider.turmalin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariantes del viewport (RF-09a). El bug que motiva estos tests: la tinta
 * aterrizaba desplazada del pen porque input y render usaban transformaciones
 * distintas — aquí se fija el contrato de la única transformación válida.
 */
class CanvasViewportTest {

    private val epsilon = 1e-4f

    @Test
    fun `sin gestos, pantalla y documento coinciden`() {
        val viewport = CanvasViewport()
        assertEquals(100f, viewport.screenToDocumentX(100f), epsilon)
        assertEquals(250f, viewport.screenToDocumentY(250f), epsilon)
        assertTrue(viewport.isNearIdentity)
    }

    @Test
    fun `pinch mantiene fijo el punto del documento bajo el focal`() {
        val viewport = CanvasViewport()
        viewport.pan(37f, -80f)
        viewport.pinch(focalX = 400f, focalY = 600f, zoomDelta = 1.5f)

        val docXBefore = (400f - 37f) / 1f
        val docYBefore = (600f - (-80f)) / 1f
        assertEquals(docXBefore, viewport.screenToDocumentX(400f), epsilon)
        assertEquals(docYBefore, viewport.screenToDocumentY(600f), epsilon)
    }

    @Test
    fun `pinch encadenado respeta los limites de escala`() {
        val viewport = CanvasViewport()
        repeat(20) { viewport.pinch(0f, 0f, 2f) }
        assertEquals(VIEWPORT_MAX_SCALE, viewport.scale, epsilon)
        repeat(40) { viewport.pinch(0f, 0f, 0.5f) }
        assertEquals(VIEWPORT_MIN_SCALE, viewport.scale, epsilon)
    }

    @Test
    fun `pan desplaza el documento y la conversion lo refleja`() {
        val viewport = CanvasViewport()
        viewport.pinch(0f, 0f, 2f) // escala 2, offset (0,0)
        viewport.pan(100f, 50f)
        // pantalla = doc*2 + (100,50)  →  doc = (pantalla - offset) / 2
        assertEquals(150f, viewport.screenToDocumentX(400f), epsilon)
        assertEquals(75f, viewport.screenToDocumentY(200f), epsilon)
    }

    @Test
    fun `snap restaura identidad solo dentro de la banda`() {
        val nearIdentity = CanvasViewport()
        nearIdentity.pinch(500f, 500f, 1.04f)
        nearIdentity.pan(30f, -12f)
        assertTrue(nearIdentity.isNearIdentity)
        nearIdentity.snapToIdentityIfClose()
        assertEquals(1f, nearIdentity.scale, epsilon)
        assertEquals(0f, nearIdentity.offsetX, epsilon)
        assertEquals(0f, nearIdentity.offsetY, epsilon)

        val zoomed = CanvasViewport()
        zoomed.pinch(500f, 500f, 2f)
        zoomed.pan(30f, -12f)
        assertFalse(zoomed.isNearIdentity)
        zoomed.snapToIdentityIfClose()
        assertEquals(2f, zoomed.scale, epsilon)
        assertEquals(30f + 500f * (1f - 2f), zoomed.offsetX, epsilon)
    }

    @Test
    fun `roundtrip documento-pantalla-documento es identidad`() {
        val viewport = CanvasViewport()
        viewport.pinch(321f, 654f, 2.7f)
        viewport.pan(-45f, 90f)

        val docX = viewport.screenToDocumentX(800f)
        val docY = viewport.screenToDocumentY(300f)
        // pantalla = doc*scale + offset
        assertEquals(800f, docX * viewport.scale + viewport.offsetX, 1e-2f)
        assertEquals(300f, docY * viewport.scale + viewport.offsetY, 1e-2f)
    }
}
