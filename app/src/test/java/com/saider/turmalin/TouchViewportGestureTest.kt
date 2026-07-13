package com.saider.turmalin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gestos táctiles de viewport (RF-09a/09b): un dedo panea/pagina/tapea, dos
 * hacen pinch, y los punteros clasificados como palma se excluyen del gesto
 * sin abortarlo ni producir saltos de viewport.
 */
class TouchViewportGestureTest {

    private val epsilon = 1e-3f

    private var pagedDirection: Int? = null
    private var tapAt: Pair<Float, Float>? = null
    private var multiTapFingers: Int? = null

    private fun gesture(viewport: CanvasViewport) = TouchViewportGesture(
        viewport = viewport,
        onPaginate = { pagedDirection = it },
        onTap = { x, y -> tapAt = x to y },
        onMultiFingerTap = { multiTapFingers = it },
    )

    // Saca el viewport de la banda de identidad para que el pan aplique.
    private fun zoomedViewport(): CanvasViewport =
        CanvasViewport().apply { pinch(0f, 0f, 2f) }

    @Test
    fun `un dedo panea con zoom activo`() {
        val viewport = zoomedViewport()
        val g = gesture(viewport)
        g.pointerDown(0, 100f, 100f, isPalm = false)
        g.move(listOf(TouchPoint(0, 100f, 100f))) // ancla
        g.move(listOf(TouchPoint(0, 140f, 130f)))
        assertEquals(40f, viewport.offsetX, epsilon)
        assertEquals(30f, viewport.offsetY, epsilon)
    }

    @Test
    fun `dos dedos hacen zoom tras superar el slop de compromiso`() {
        val viewport = zoomedViewport()
        val g = gesture(viewport)
        g.pointerDown(0, 100f, 200f, isPalm = false)
        g.pointerDown(1, 200f, 200f, isPalm = false)
        g.move(listOf(TouchPoint(0, 100f, 200f), TouchPoint(1, 200f, 200f))) // ancla
        // Separación +100px: compromete el pinch (sin aplicar aún).
        g.move(listOf(TouchPoint(0, 50f, 200f), TouchPoint(1, 250f, 200f)))
        val scaleBefore = viewport.scale
        // Ya comprometido: x1.5 de separación → zoom x1.5.
        g.move(listOf(TouchPoint(0, 0f, 200f), TouchPoint(1, 300f, 200f)))
        assertEquals(scaleBefore * 1.5f, viewport.scale, epsilon)
    }

    @Test
    fun `palma clasificada al down no mueve el viewport`() {
        val viewport = zoomedViewport()
        val g = gesture(viewport)
        val offsetBefore = viewport.offsetX
        g.pointerDown(0, 300f, 300f, isPalm = true)
        g.move(listOf(TouchPoint(0, 300f, 300f)))
        g.move(listOf(TouchPoint(0, 400f, 300f)))
        assertEquals(offsetBefore, viewport.offsetX, epsilon)
    }

    @Test
    fun `palma cancelada a mitad de gesto deja al dedo paneando sin salto`() {
        val viewport = zoomedViewport()
        val g = gesture(viewport)
        // Dedo + palma (que el sistema aún no clasifica): parecen dos dedos,
        // pero sin superar el slop de pinch no aplican nada.
        g.pointerDown(0, 100f, 100f, isPalm = false)
        g.pointerDown(1, 500f, 500f, isPalm = false)
        g.move(listOf(TouchPoint(0, 100f, 100f), TouchPoint(1, 500f, 500f)))
        val offsetXBefore = viewport.offsetX
        val offsetYBefore = viewport.offsetY
        // FLAG_CANCELED: el sistema retira la palma.
        g.pointerCanceled(1)
        // Primer move tras el cambio de punteros: re-ancla, sin delta.
        g.move(listOf(TouchPoint(0, 100f, 100f)))
        assertEquals(offsetXBefore, viewport.offsetX, epsilon)
        assertEquals(offsetYBefore, viewport.offsetY, epsilon)
        // El dedo restante sigue paneando normal.
        g.move(listOf(TouchPoint(0, 160f, 100f)))
        assertEquals(offsetXBefore + 60f, viewport.offsetX, epsilon)
        assertEquals(offsetYBefore, viewport.offsetY, epsilon)
    }

    @Test
    fun `swipe horizontal de un dedo pagina con la pagina encajada`() {
        val viewport = CanvasViewport() // identidad: encajada
        val g = gesture(viewport)
        g.pointerDown(0, 500f, 400f, isPalm = false)
        g.move(listOf(TouchPoint(0, 500f, 400f)))
        g.move(listOf(TouchPoint(0, 300f, 405f))) // -200px, dominancia horizontal
        assertEquals(1, pagedDirection)
        // Una sola página por gesto aunque siga arrastrando.
        pagedDirection = null
        g.move(listOf(TouchPoint(0, 100f, 405f)))
        assertEquals(null, pagedDirection)
        // Y encajada, el arrastre no panea.
        assertEquals(0f, viewport.offsetX, epsilon)
    }

    @Test
    fun `swipe de dedo pagina aunque haya una palma apoyada`() {
        val viewport = CanvasViewport()
        val g = gesture(viewport)
        g.pointerDown(0, 600f, 600f, isPalm = true) // mano apoyada
        g.pointerDown(1, 500f, 400f, isPalm = false)
        g.move(listOf(TouchPoint(0, 600f, 600f), TouchPoint(1, 500f, 400f)))
        g.move(listOf(TouchPoint(0, 600f, 600f), TouchPoint(1, 300f, 400f)))
        assertEquals(1, pagedDirection)
    }

    @Test
    fun `tap de un dedo sin movimiento navega, y con arrastre no`() {
        val viewport = zoomedViewport()
        val g = gesture(viewport)
        g.pointerDown(0, 250f, 350f, isPalm = false)
        g.pointerUp(0)
        g.end()
        assertEquals(250f to 350f, tapAt)

        tapAt = null
        g.pointerDown(0, 250f, 350f, isPalm = false)
        g.move(listOf(TouchPoint(0, 250f, 350f)))
        g.move(listOf(TouchPoint(0, 320f, 350f))) // supera el slop
        g.pointerUp(0)
        g.end()
        assertEquals(null, tapAt)
    }

    @Test
    fun `tap con palma apoyada se resuelve sin esperar el fin del gesto`() {
        val viewport = zoomedViewport()
        val g = gesture(viewport)
        g.pointerDown(0, 600f, 600f, isPalm = true)
        g.pointerDown(1, 200f, 200f, isPalm = false)
        g.pointerUp(1) // ACTION_POINTER_UP: la palma sigue abajo
        assertEquals(200f to 200f, tapAt)
    }

    @Test
    fun `un contacto cancelado por el sistema nunca cuenta como tap`() {
        val viewport = zoomedViewport()
        val g = gesture(viewport)
        g.pointerDown(0, 200f, 200f, isPalm = false)
        g.pointerCanceled(0)
        g.pointerUp(0)
        g.end()
        assertEquals(null, tapAt)
    }

    @Test
    fun `al cerrar el gesto cerca de identidad el viewport se reencaja`() {
        val viewport = CanvasViewport()
        viewport.pan(10f, -10f) // resto imperceptible dentro de la banda
        val g = gesture(viewport)
        g.pointerDown(0, 100f, 100f, isPalm = false)
        g.pointerUp(0)
        g.end()
        assertTrue(viewport.isNearIdentity)
        assertEquals(0f, viewport.offsetX, epsilon)
        assertEquals(0f, viewport.offsetY, epsilon)
    }

    // --- Tap multi-dedo (v2 3.3) ---

    @Test
    fun `tap breve de dos dedos dispara el gesto con 2`() {
        val g = gesture(CanvasViewport())
        g.pointerDown(0, 100f, 100f, isPalm = false, nowMillis = 0L)
        g.pointerDown(1, 160f, 100f, isPalm = false, nowMillis = 20L)
        g.move(listOf(TouchPoint(0, 100f, 100f), TouchPoint(1, 160f, 100f)))
        g.pointerUp(0, nowMillis = 120L)
        g.pointerUp(1, nowMillis = 140L)
        assertEquals(2, multiTapFingers)
    }

    @Test
    fun `tap breve de tres dedos dispara el gesto con 3`() {
        val g = gesture(CanvasViewport())
        g.pointerDown(0, 100f, 100f, isPalm = false, nowMillis = 0L)
        g.pointerDown(1, 160f, 100f, isPalm = false, nowMillis = 10L)
        g.pointerDown(2, 220f, 100f, isPalm = false, nowMillis = 20L)
        g.pointerUp(0, nowMillis = 100L)
        g.pointerUp(1, nowMillis = 110L)
        g.pointerUp(2, nowMillis = 120L)
        assertEquals(3, multiTapFingers)
    }

    @Test
    fun `pinch comprometido no cuenta como tap de dos dedos`() {
        val viewport = zoomedViewport()
        val g = gesture(viewport)
        g.pointerDown(0, 100f, 200f, isPalm = false, nowMillis = 0L)
        g.pointerDown(1, 200f, 200f, isPalm = false, nowMillis = 10L)
        g.move(listOf(TouchPoint(0, 100f, 200f), TouchPoint(1, 200f, 200f)))
        // Separación +100px: compromete el pinch.
        g.move(listOf(TouchPoint(0, 50f, 200f), TouchPoint(1, 250f, 200f)))
        g.pointerUp(0, nowMillis = 100L)
        g.pointerUp(1, nowMillis = 110L)
        assertEquals(null, multiTapFingers)
    }

    @Test
    fun `contacto largo de dos dedos no cuenta como tap`() {
        val g = gesture(CanvasViewport())
        g.pointerDown(0, 100f, 100f, isPalm = false, nowMillis = 0L)
        g.pointerDown(1, 160f, 100f, isPalm = false, nowMillis = 10L)
        g.pointerUp(0, nowMillis = 500L)
        g.pointerUp(1, nowMillis = 520L)
        assertEquals(null, multiTapFingers)
    }

    @Test
    fun `palma cancelada mas un dedo no cuenta como tap de dos dedos`() {
        val g = gesture(CanvasViewport())
        g.pointerDown(0, 100f, 100f, isPalm = false, nowMillis = 0L)
        g.pointerDown(1, 400f, 400f, isPalm = false, nowMillis = 10L)
        // El sistema clasifica el segundo contacto como palma después del down.
        g.pointerCanceled(1)
        g.pointerUp(0, nowMillis = 100L)
        assertEquals(null, multiTapFingers)
        // Y tampoco degenera en tap simple (hubo dos contactos).
        assertEquals(null, tapAt)
    }

    @Test
    fun `el tap simple de un dedo sigue funcionando igual`() {
        val g = gesture(CanvasViewport())
        g.pointerDown(0, 100f, 100f, isPalm = false, nowMillis = 0L)
        g.pointerUp(0, nowMillis = 80L)
        assertEquals(100f to 100f, tapAt)
        assertEquals(null, multiTapFingers)
    }
}
