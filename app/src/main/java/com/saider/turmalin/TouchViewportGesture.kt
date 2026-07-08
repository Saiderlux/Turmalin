package com.saider.turmalin

import kotlin.math.abs
import kotlin.math.hypot

// Distancia horizontal acumulada de arrastre con UN dedo para disparar el
// cambio de página con la página encajada (RF-09a). ~1.7 cm en la Tab S6 Lite.
const val PAGE_SWIPE_THRESHOLD_PX = 160f

// Movimiento máximo (px de pantalla) para que un contacto de un dedo cuente
// como tap sobre una región linkeada, en vez de arrastre de pan.
const val LINK_TAP_SLOP_PX = 24f

// Cambio mínimo (px) de separación o de centroide antes de que dos dedos
// empiecen a aplicar zoom/pan: si el segundo "dedo" es una palma que el
// sistema todavía no cancela (el FLAG_CANCELED llega unos eventos después),
// este slop evita un brinco de viewport durante esa ventana.
const val PINCH_COMMIT_SLOP_PX = 12f

// Umbral del heurístico de palma pre-API 33 (RF-09a): touchMajor en mm por
// encima del cual un contacto se trata como palma. Un dedo ronda 5–9 mm; una
// palma apoyada lo supera con holgura. ponytail: umbral fijo conservador,
// calibrar solo si un dispositivo pre-Android-13 real rechaza dedos o deja
// pasar palmas.
const val PALM_TOUCH_MAJOR_MM = 22f

// Duración mínima de un contacto quieto para contar como long-press (borrar
// vínculo) en vez de tap (navegar al vínculo). Se resuelve al soltar: no hay
// callback a mitad de contacto, lo cual basta para un menú contextual.
const val LONG_PRESS_MILLIS = 500L

private const val NO_POINTER = -1

/** Posición de un puntero táctil presionado en un evento (px de pantalla). */
data class TouchPoint(val id: Int, val x: Float, val y: Float)

/**
 * Máquina de estados del gesto táctil de viewport (RF-09a/09b): un dedo panea
 * la página (o pagina con swipe horizontal si está encajada a ~1x), dos dedos
 * hacen pinch-to-zoom anclado al focal, y un dedo quieto que sube sin moverse
 * es un tap (navegación de links). Los punteros clasificados como palma — por
 * el sistema en API 33+ vía [pointerCanceled], o rechazados desde el down —
 * se excluyen del gesto sin abortarlo: la mano apoyada no arrastra el lienzo
 * mientras un dedo de la otra mano panea.
 *
 * No depende de MotionEvent a propósito: el listener del canvas traduce cada
 * evento a estas llamadas y la lógica queda testeable en JVM. Los cambios en
 * el conjunto de punteros activos re-anclan el gesto (sin aplicar delta) para
 * que una palma cancelada a mitad de pan no produzca saltos de viewport.
 */
class TouchViewportGesture(
    private val viewport: CanvasViewport,
    private val onPaginate: (direction: Int) -> Unit,
    private val onTap: (screenX: Float, screenY: Float) -> Unit,
    // Contacto quieto de al menos LONG_PRESS_MILLIS: menú contextual (eliminar
    // vínculo). Se distingue del tap por duración, al soltar el dedo.
    private val onLongPress: (screenX: Float, screenY: Float) -> Unit = { _, _ -> },
) {
    // Punteros aceptados (no palma) actualmente presionados.
    private val pressed = LinkedHashSet<Int>()

    // Dedo candidato a tap: el primero del gesto, mientras no haya un segundo
    // dedo, movimiento más allá del slop, paginación ni rechazo como palma.
    private var firstDownId = NO_POINTER
    private var firstDownX = 0f
    private var firstDownY = 0f
    private var firstDownAtMillis = 0L
    private var firstDownRejected = false
    private var sawMultiple = false
    private var movedBeyondSlop = false

    // Una página por gesto: tras disparar onPaginate el resto del arrastre es inerte.
    private var paged = false
    private var accumSwipeX = 0f
    private var accumSwipeY = 0f

    // Anclas del delta entre eventos. lastIds detecta cambios en el conjunto
    // activo (dedo que entra/sale, palma cancelada) para re-anclar sin salto.
    private var lastIds: List<Int> = emptyList()
    private var prevX = 0f
    private var prevY = 0f
    private var prevDistance = 0f

    // Compromiso de pinch: dos dedos no aplican zoom/pan hasta moverse más de
    // PINCH_COMMIT_SLOP_PX desde su ancla (ver comentario de la constante).
    private var pinchCommitted = false
    private var anchorCentroidX = 0f
    private var anchorCentroidY = 0f
    private var anchorDistance = 0f

    fun pointerDown(id: Int, x: Float, y: Float, isPalm: Boolean, nowMillis: Long = System.currentTimeMillis()) {
        if (isPalm) return
        pressed.add(id)
        if (pressed.size >= 2) sawMultiple = true
        if (firstDownId == NO_POINTER && pressed.size == 1) {
            // Primer dedo del gesto (o dedo nuevo con solo la palma apoyada):
            // arranca limpio como candidato a tap/swipe.
            firstDownId = id
            firstDownX = x
            firstDownY = y
            firstDownAtMillis = nowMillis
            firstDownRejected = false
            sawMultiple = false
            movedBeyondSlop = false
            paged = false
            accumSwipeX = 0f
            accumSwipeY = 0f
        }
    }

    /** El sistema (o el heurístico de área) canceló este puntero como palma. */
    fun pointerCanceled(id: Int) {
        pressed.remove(id)
        if (id == firstDownId) firstDownRejected = true
    }

    fun pointerUp(id: Int, nowMillis: Long = System.currentTimeMillis()) {
        if (!pressed.remove(id)) return
        if (id == firstDownId) {
            if (!sawMultiple && !movedBeyondSlop && !paged && !firstDownRejected) {
                // El tap/long-press se resuelve aquí y no al final del gesto: con
                // la palma apoyada el ACTION_UP del sistema no llega hasta levantarla.
                if (nowMillis - firstDownAtMillis >= LONG_PRESS_MILLIS) {
                    onLongPress(firstDownX, firstDownY)
                } else {
                    onTap(firstDownX, firstDownY)
                }
            }
            firstDownId = NO_POINTER
        }
    }

    /** Todos los punteros presionados del evento actual (la máquina filtra los suyos). */
    fun move(points: List<TouchPoint>) {
        val active = points.filter { it.id in pressed }
        if (active.isEmpty()) {
            lastIds = emptyList()
            return
        }
        val ids = active.map { it.id }
        val idsChanged = ids != lastIds
        lastIds = ids

        if (active.size >= 2) {
            val a = active[0]
            val b = active[1]
            val centroidX = (a.x + b.x) / 2f
            val centroidY = (a.y + b.y) / 2f
            val distance = hypot(a.x - b.x, a.y - b.y)
            if (idsChanged) {
                pinchCommitted = false
                anchorCentroidX = centroidX
                anchorCentroidY = centroidY
                anchorDistance = distance
            } else if (!pinchCommitted) {
                pinchCommitted =
                    abs(distance - anchorDistance) > PINCH_COMMIT_SLOP_PX ||
                        hypot(centroidX - anchorCentroidX, centroidY - anchorCentroidY) >
                        PINCH_COMMIT_SLOP_PX
            } else {
                if (prevDistance > 0f && distance > 0f) {
                    viewport.pinch(centroidX, centroidY, distance / prevDistance)
                }
                viewport.pan(centroidX - prevX, centroidY - prevY)
            }
            prevX = centroidX
            prevY = centroidY
            prevDistance = distance
        } else {
            val p = active[0]
            if (p.id == firstDownId &&
                hypot(p.x - firstDownX, p.y - firstDownY) > LINK_TAP_SLOP_PX
            ) {
                movedBeyondSlop = true
            }
            if (idsChanged) {
                // Re-ancla (dedo que quedó solo tras pinch o palma cancelada):
                // este evento no aplica delta.
                prevX = p.x
                prevY = p.y
                prevDistance = 0f
                return
            }
            val dx = p.x - prevX
            val dy = p.y - prevY
            if (viewport.isNearIdentity) {
                // Página encajada: el dedo acumula para paginar (solo arrastre
                // con dominancia horizontal); no hay pan — el snap del cierre
                // limpia cualquier resto imperceptible.
                accumSwipeX += dx
                accumSwipeY += dy
                if (!paged &&
                    abs(accumSwipeX) > 2f * abs(accumSwipeY) &&
                    abs(accumSwipeX) > PAGE_SWIPE_THRESHOLD_PX
                ) {
                    paged = true
                    onPaginate(if (accumSwipeX < 0) 1 else -1)
                }
            } else {
                viewport.pan(dx, dy)
            }
            prevX = p.x
            prevY = p.y
            prevDistance = 0f
        }
    }

    /** Fin del gesto del sistema (ACTION_UP/ACTION_CANCEL o S Pen activo). */
    fun end() {
        pressed.clear()
        firstDownId = NO_POINTER
        firstDownRejected = false
        sawMultiple = false
        movedBeyondSlop = false
        paged = false
        accumSwipeX = 0f
        accumSwipeY = 0f
        lastIds = emptyList()
        prevDistance = 0f
        pinchCommitted = false
        viewport.snapToIdentityIfClose()
    }
}
