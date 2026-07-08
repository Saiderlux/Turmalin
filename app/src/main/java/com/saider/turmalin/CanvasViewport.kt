package com.saider.turmalin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

// Rango de zoom del viewport (RF-09a).
const val VIEWPORT_MIN_SCALE = 0.5f
const val VIEWPORT_MAX_SCALE = 4f

// Banda alrededor de 1x dentro de la cual el viewport se considera "página
// encajada": al terminar un gesto ahí, escala y offset se restauran a la
// identidad, y solo en ese estado el swipe de dos dedos pagina (RF-09a).
const val VIEWPORT_SNAP_BAND = 0.05f

/**
 * Viewport del canvas (RF-09a): única fuente de verdad de la transformación
 * visual documento→pantalla, con convención `pantalla = documento * scale +
 * offset` (origen arriba-izquierda, sin rotación).
 *
 * Los trazos de la capa de ink viven siempre en coordenadas de documento; el
 * viewport nunca toca esa geometría. Todo consumidor debe usar este mismo
 * estado: la capa dry lo aplica visualmente (graphicsLayer con pivote 0,0), la
 * wet lo recibe como matriz motionEvent→mundo, y las gomas convierten con
 * [screenToDocumentX]/[screenToDocumentY]. Si render e input usaran matrices
 * distintas, la tinta aterrizaría desplazada del pen — el bug que motivó esta
 * clase.
 *
 * Respaldado por snapshot state: leerlo en composición/capa re-renderiza solo.
 */
class CanvasViewport(
    private val minScale: Float = VIEWPORT_MIN_SCALE,
    private val maxScale: Float = VIEWPORT_MAX_SCALE,
    private val snapBand: Float = VIEWPORT_SNAP_BAND,
) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offsetX by mutableFloatStateOf(0f)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set

    // Offset de "página encajada" (RF-09b): no es (0,0) sino el que centra la
    // hoja en el área visible, fijado una vez por [centerPage] al conocerse el
    // tamaño del lienzo. snapToIdentityIfClose() reencaja aquí, no al origen.
    private var homeOffsetX = 0f
    private var homeOffsetY = 0f

    /** Página encajada (o casi): estado en el que el swipe de dos dedos pagina
     *  en vez de hacer pan. */
    val isNearIdentity: Boolean
        get() = abs(scale - 1f) <= snapBand

    fun pan(dx: Float, dy: Float) {
        offsetX += dx
        offsetY += dy
    }

    /**
     * Zoom multiplicativo anclado al punto focal (px de pantalla): el punto del
     * documento que está bajo el focal sigue bajo él después del zoom.
     */
    fun pinch(focalX: Float, focalY: Float, zoomDelta: Float) {
        val newScale = (scale * zoomDelta).coerceIn(minScale, maxScale)
        val applied = newScale / scale
        offsetX = focalX - applied * (focalX - offsetX)
        offsetY = focalY - applied * (focalY - offsetY)
        scale = newScale
    }

    /**
     * Cierre de gesto: si el zoom quedó dentro de la banda de 1x, encaja la
     * página exacta (identidad) para que el estado de paginación sea
     * predecible y no se acumulen restos de pan/zoom imperceptibles.
     */
    fun snapToIdentityIfClose() {
        if (isNearIdentity) {
            scale = 1f
            offsetX = homeOffsetX
            offsetY = homeOffsetY
        }
    }

    /**
     * Centra el rect de página (ancho×alto en coordenadas de documento) dentro
     * de un lienzo de [screenW]×[screenH] px: fija el offset "encajado" (home)
     * y lo aplica de inmediato. Se llama una vez, al conocerse el tamaño real
     * del canvas al abrir la nota — el origen documento no debe asumirse pegado
     * a la esquina superior izquierda del viewport.
     */
    fun centerPage(pageWidthDoc: Float, pageHeightDoc: Float, screenW: Float, screenH: Float) {
        homeOffsetX = (screenW - pageWidthDoc * scale) / 2f
        homeOffsetY = (screenH - pageHeightDoc * scale) / 2f
        offsetX = homeOffsetX
        offsetY = homeOffsetY
    }

    /**
     * Encuadra una caja de mundo [minX,minY]–[maxX,maxY] dentro de un lienzo de
     * [screenW]×[screenH] con [padding] px de margen: centra la caja y elige la
     * mayor escala que la deja entrar (sin pasar de 1x — no acerca grafos chicos)
     * acotada al rango del viewport. Lo usa la vista de grafo para que todos los
     * nodos, huérfanos incluidos, entren al abrir.
     */
    fun fit(
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
        screenW: Float,
        screenH: Float,
        padding: Float,
    ) {
        val w = (maxX - minX).coerceAtLeast(1f)
        val h = (maxY - minY).coerceAtLeast(1f)
        val s = minOf((screenW - 2 * padding) / w, (screenH - 2 * padding) / h)
            .coerceIn(minScale, 1f)
        scale = s
        offsetX = screenW / 2f - s * (minX + maxX) / 2f
        offsetY = screenH / 2f - s * (minY + maxY) / 2f
    }

    fun screenToDocumentX(screenX: Float): Float = (screenX - offsetX) / scale

    fun screenToDocumentY(screenY: Float): Float = (screenY - offsetY) / scale
}
