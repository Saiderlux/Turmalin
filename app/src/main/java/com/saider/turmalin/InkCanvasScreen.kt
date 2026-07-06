package com.saider.turmalin

import android.graphics.Matrix
import android.util.Log
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.geometry.ImmutableBox
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.input.motionprediction.MotionEventPredictor
import kotlin.math.hypot

// Media caja (en px de pantalla) alrededor de la punta del pen para el hit-test
// de borrado. ~2.3 mm en la Tab S6 Lite. Con zoom se convierte a espacio de
// documento (dividida por la escala) para que la goma cubra siempre la misma
// área física bajo el pen.
private const val ERASER_HALF_SIZE_PX = 20f

// Distancia horizontal acumulada de arrastre con dos dedos para disparar el
// cambio de página (RF-09a). ~1.7 cm en la Tab S6 Lite.
private const val PAGE_SWIPE_THRESHOLD_PX = 160f

// Color fijo reservado para el overlay de links (RF-23a): nunca formará parte
// de la paleta de plumas del usuario. Halo semitransparente ceñido a la tinta.
val LINK_OVERLAY_COLOR = Color(0x553D5AFE)

// Grosor del halo del overlay: mayor que la tinta (4f) para que asome ceñido
// alrededor de los trazos linkeados en vez de quedar tapado por ellos.
private const val LINK_OVERLAY_SIZE = 14f

// Contorno del lazo mientras se dibuja (mismo color base, opaco).
private val LASSO_STROKE_COLOR = Color(0xFF3D5AFE)

/**
 * Overlay de un link (RF-23a): los trazos linkeados YA re-teñidos con el pincel
 * de link ([tintStrokes]) para dibujarlos como halo bajo la tinta negra. El
 * hit-test del tap se hace contra estas mallas (ceñido), no contra una caja: dos
 * links con cajas solapadas ya no se confunden al tocar.
 */
data class LinkOverlay(
    val targetUuid: String,
    val tintStrokes: List<Stroke>,
)

/** Pincel del halo de link: color reservado, semitransparente, grueso. */
fun linkOverlayBrush(): Brush = Brush.createWithColorIntArgb(
    family = StockBrushes.pressurePen(StockBrushes.PressurePenVersion.LATEST),
    colorIntArgb = LINK_OVERLAY_COLOR.toArgb(),
    size = LINK_OVERLAY_SIZE,
    epsilon = 0.1f,
)

// Movimiento máximo (px de pantalla) para que un contacto de un dedo cuente
// como tap sobre una región linkeada, en vez de gesto abortado.
private const val LINK_TAP_SLOP_PX = 24f

/** Ray casting: ¿el punto (x, y) cae dentro del polígono plano [x0,y0,x1,y1,…]? */
fun polygonContains(polygon: List<Float>, x: Float, y: Float): Boolean {
    val n = polygon.size / 2
    if (n < 3) return false
    var inside = false
    var j = n - 1
    for (i in 0 until n) {
        val xi = polygon[2 * i]
        val yi = polygon[2 * i + 1]
        val xj = polygon[2 * j]
        val yj = polygon[2 * j + 1]
        if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
            inside = !inside
        }
        j = i
    }
    return inside
}

// Polígono plano → Path cerrado para dibujar el overlay/lazo.
private fun polygonToPath(points: List<Float>): Path {
    val path = Path()
    if (points.size < 4) return path
    path.moveTo(points[0], points[1])
    for (i in 1 until points.size / 2) path.lineTo(points[2 * i], points[2 * i + 1])
    path.close()
    return path
}

// Pincel de escritura (RF-03/04): tinta sensible a presión con el color y grosor
// de la paleta. La familia (pressurePen) y epsilon son constantes del MVP; color
// y grosor se eligen en la barra y se persisten por trazo en ink.bin.
fun penBrush(colorIntArgb: Int, size: Float): Brush = Brush.createWithColorIntArgb(
    family = StockBrushes.pressurePen(StockBrushes.PressurePenVersion.LATEST),
    colorIntArgb = colorIntArgb,
    size = size,
    epsilon = 0.1f,
)

// Pluma negra por defecto: nota nueva y reposición de las partes constantes del
// pincel (familia y epsilon) al leer ink.bin.
fun defaultBlackPen(): Brush = penBrush(0xFF000000.toInt(), 4f)

/**
 * Pantalla única del prototipo: canvas de escritura a pantalla completa con
 * pluma, goma de trazo, goma parcial, y el atajo de goma temporal del botón
 * del S Pen (RF-05c, usa la última goma seleccionada).
 *
 * Capa "wet" (trazo en curso): [InProgressStrokesView] de la Ink API, hospedado
 * directamente vía [AndroidView] siguiendo el patrón oficial de integración por
 * View. Se usa la API de View en vez del composable InProgressStrokes porque
 * esta expone [InProgressStrokesView.useHighLatencyRenderHelper], necesario para
 * diagnosticar el render front-buffer en este dispositivo (ver [wetHighLatency]).
 *
 * Capa "dry" (trazos terminados): Canvas de Compose con [CanvasStrokeRenderer],
 * debajo de la wet. El handoff wet→dry ocurre en onStrokesFinished. Las gomas
 * operan solo sobre la capa dry: la de trazo elimina trazos completos cuya
 * malla intersecta la caja alrededor del punto de contacto; la parcial corta el
 * trazo al nivel de sus puntos de entrada y reconstruye los segmentos
 * sobrevivientes como trazos nuevos.
 *
 * Espacios de coordenadas (RF-09a): los trazos viven en coordenadas de
 * DOCUMENTO, estables ante pan/zoom. [CanvasViewport] es la única fuente de
 * verdad de la transformación documento→pantalla: la capa dry la aplica como
 * graphicsLayer (visual, nunca toca la geometría de los trazos), y la captura
 * (wet + gomas) vive en espacio de pantalla y convierte con su inversa —
 * `motionEventToWorldTransform` para la pluma, conversión de punto y radio
 * para las gomas. El viewport se manipula solo con gestos de DOS dedos, y
 * nunca mientras el S Pen está apoyado.
 *
 * @param wetHighLatency si es true (default), la capa wet usa el render helper
 *   clásico por frame (V21, multi-buffered). Con false usa el de baja latencia
 *   con buffer único (V33), que se compone por HWC mutando el buffer in-place —
 *   un esquema que el hardware composer de la Tab S6 Lite no re-escanea al panel
 *   físico: el trazo en vivo no se ve en el vidrio (o aparece de golpe a mitad
 *   de trazo) aunque sí aparece en screencap/screenrecord (composición por GPU).
 *   Reevaluar V33 como default cuando se pruebe en otro hardware.
 */
@Composable
fun InkCanvasScreen(
    strokes: SnapshotStateList<IdStroke>,
    wetHighLatency: Boolean = true,
    eraserRouter: StylusEraserRouter? = null,
    onSwipePage: (direction: Int) -> Unit = {},
    // Notifica cada modificación real de la capa de tinta (trazo nuevo o
    // borrado). Cargar página o mover el viewport NO cuentan: es el dirty
    // flag que decide si el cierre de la nota dispara OCR (RF-24, RNF-02).
    onInkModified: () -> Unit = {},
    // Overlays de los links de la página visible (RF-23a): halo ceñido a la
    // tinta linkeada; un tap de un dedo dentro de su bbox navega al destino.
    linkOverlays: List<LinkOverlay> = emptyList(),
    // Lazo (RF-17): entrega el polígono cerrado (coordenadas de documento,
    // lista plana x,y) al levantar el S Pen con la herramienta activa.
    onLassoComplete: (List<Float>) -> Unit = {},
    onLinkTap: (targetUuid: String) -> Unit = {},
    // Trazos cuyo ID desaparece de la página por la goma (borrado total). El
    // borrado parcial NO los emite: sus piezas heredan el ID (RF-05a/b).
    onStrokesErased: (List<IdStroke>) -> Unit = {},
) {
    val strokeRenderer = remember { CanvasStrokeRenderer.create() }

    // Vía rememberUpdatedState porque se invoca desde closures del factory de
    // AndroidView, que solo corre una vez y capturaría una lambda vieja.
    val currentOnInkModified = rememberUpdatedState(onInkModified)
    val currentLinkOverlays = rememberUpdatedState(linkOverlays)
    val currentOnLassoComplete = rememberUpdatedState(onLassoComplete)
    val currentOnLinkTap = rememberUpdatedState(onLinkTap)
    val currentOnStrokesErased = rememberUpdatedState(onStrokesErased)

    // Polilínea del lazo en curso (coordenadas de documento, plana x,y).
    val lassoPoints = remember { mutableStateListOf<Float>() }

    // Identidad: la capa dry dibuja en coordenadas de documento y el
    // graphicsLayer del viewport aplica documento→pantalla por encima, así que
    // la matriz de trazado del renderer queda en identidad.
    val identityTransform = remember { Matrix() }

    // Viewport táctil (RF-09a): única fuente de verdad del pan/zoom. Los trazos
    // se capturan y almacenan SIEMPRE en coordenadas de documento: la captura
    // (wet) vive fuera del graphicsLayer, en espacio de pantalla, y convierte
    // con la inversa de este viewport (motionEventToWorldTransform de la Ink
    // API); si input y render usaran transformaciones distintas, la tinta
    // aterrizaría desplazada del pen.
    val viewport = remember { CanvasViewport() }

    // True mientras el S Pen tiene contacto con el canvas: los gestos táctiles
    // de viewport se abandonan por completo en ese lapso (la palma que
    // acompaña a la escritura no debe mover el lienzo — RF-02).
    val stylusIsDown = remember { mutableStateOf(false) }

    // Selección persistente de la barra (RF-05c forma 1).
    val selectedTool = remember { mutableStateOf(Tool.PEN) }

    // Goma que usa el atajo del botón: la última seleccionada en la barra.
    val lastEraserTool = remember { mutableStateOf(Tool.ERASER_STROKE) }

    // Atajo temporal: no nulo solo mientras el botón del S Pen está presionado
    // con contacto en pantalla (RF-05c forma 2). No modifica selectedTool: al
    // soltar el botón, la herramienta vigente vuelve a ser la de la barra.
    val temporaryEraserTool = remember { mutableStateOf<Tool?>(null) }

    // Color y grosor de la pluma (RF-03/04): se eligen en la barra y definen el
    // pincel de cada trazo nuevo. Se leen (.value) al iniciar el trazo dentro del
    // listener y viajan con el Stroke terminado hasta ink.bin.
    val penColorArgb = remember { mutableStateOf(PEN_COLORS.first()) }
    val penSize = remember { mutableStateOf(PEN_SIZES[1]) }

    // Palm rejection local al canvas (ver listener del FrameLayout): el resto
    // de la pantalla (título, chips, controles de página) sí responde al dedo.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            // Gestos táctiles de viewport (RF-09a), siempre con DOS dedos:
            // pinch-to-zoom anclado al focal, pan con la página ampliada, y
            // swipe horizontal para paginar con la página encajada (~1x). Un
            // solo contacto táctil es inerte a propósito: la palma que descansa
            // junto al pen no debe arrastrar el lienzo (RF-02). Si el primer
            // contacto del gesto no es Touch (p. ej. el S Pen), se sale sin
            // consumir nada: el evento sigue su curso hacia la capa wet, que ya
            // filtra por toolType para el trazado.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.type != PointerType.Touch) return@awaitEachGesture

                    var previousDistance = 0f
                    var previousCentroid: Offset? = null
                    var accumSwipeX = 0f
                    var accumSwipeY = 0f
                    // Para detectar el tap de un dedo sobre una región linkeada
                    // (UC-05): un solo contacto, sin arrastre, que termina en UP.
                    var sawSecondPointer = false
                    var movedBeyondSlop = false
                    var endedByRelease = false

                    while (true) {
                        val event = awaitPointerEvent()
                        // S Pen apoyado: el gesto táctil se abandona entero.
                        if (stylusIsDown.value) break
                        val pressed = event.changes
                            .filter { it.type == PointerType.Touch && it.pressed }
                        if (pressed.isEmpty()) {
                            endedByRelease = true
                            break
                        }

                        if (pressed.size >= 2) {
                            sawSecondPointer = true
                            val a = pressed[0]
                            val b = pressed[1]
                            val centroid = (a.position + b.position) / 2f
                            val distance = (a.position - b.position).getDistance()
                            if (previousDistance > 0f && distance > 0f) {
                                viewport.pinch(
                                    focalX = centroid.x,
                                    focalY = centroid.y,
                                    zoomDelta = distance / previousDistance,
                                )
                            }
                            val pan = previousCentroid?.let { centroid - it } ?: Offset.Zero
                            previousDistance = distance
                            previousCentroid = centroid

                            if (viewport.isNearIdentity) {
                                // Página encajada: el arrastre acumula para
                                // paginar (solo si domina el eje horizontal);
                                // no hay pan — el snap del cierre limpia
                                // cualquier resto imperceptible.
                                accumSwipeX += pan.x
                                accumSwipeY += pan.y
                                val horizontalDominant =
                                    kotlin.math.abs(accumSwipeX) >
                                        2f * kotlin.math.abs(accumSwipeY)
                                if (horizontalDominant &&
                                    kotlin.math.abs(accumSwipeX) > PAGE_SWIPE_THRESHOLD_PX
                                ) {
                                    onSwipePage(if (accumSwipeX < 0) 1 else -1)
                                    pressed.forEach { it.consume() }
                                    // Una página por gesto: se abandona el resto.
                                    break
                                }
                            } else {
                                viewport.pan(pan.x, pan.y)
                            }
                            pressed.forEach { it.consume() }
                        } else {
                            // Un solo contacto (dedo suelto o palma): inerte para
                            // el viewport; solo se mide si se movió, para decidir
                            // si al soltar cuenta como tap sobre un link.
                            if ((pressed[0].position - down.position).getDistance() >
                                LINK_TAP_SLOP_PX
                            ) {
                                movedBeyondSlop = true
                            }
                            previousDistance = 0f
                            previousCentroid = null
                        }
                    }
                    // Tap de un dedo sobre la TINTA de un link: navegar a la nota
                    // destino. Se prueba contra la malla de los trazos (ceñido),
                    // no contra una caja — así dos links con cajas solapadas no se
                    // confunden. La palma se descarta porque casi siempre se mueve,
                    // dura junto al pen (stylusIsDown) o no cae sobre un trazo.
                    if (endedByRelease && !sawSecondPointer && !movedBeyondSlop) {
                        val x = viewport.screenToDocumentX(down.position.x)
                        val y = viewport.screenToDocumentY(down.position.y)
                        val slop = LINK_TAP_SLOP_PX / viewport.scale
                        val hitBox = ImmutableBox.fromTwoPoints(
                            x - slop, y - slop, x + slop, y + slop,
                        )
                        currentLinkOverlays.value
                            .firstOrNull { overlay ->
                                overlay.tintStrokes.any {
                                    it.shape.computeCoverageIsGreaterThan(hitBox, 0f)
                                }
                            }
                            ?.let { currentOnLinkTap.value(it.targetUuid) }
                    }
                    viewport.snapToIdentityIfClose()
                }
            },
    ) {
      // Capa dry (trazos terminados, en coordenadas de documento) dentro del
      // graphicsLayer que aplica documento→pantalla. Pivote en (0,0) para que
      // la transformación visual sea exactamente `pantalla = doc*scale+offset`,
      // la misma convención de CanvasViewport — si el pivote fuera el centro
      // (default), input y render divergirían.
      Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = viewport.scale
                scaleY = viewport.scale
                translationX = viewport.offsetX
                translationY = viewport.offsetY
                transformOrigin = TransformOrigin(0f, 0f)
            },
      ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawIntoCanvas { canvas ->
                // Overlay de links (RF-23a) PRIMERO, debajo de la tinta: re-dibujo
                // de los trazos linkeados con el pincel de link (halo ceñido). El
                // ink real se dibuja encima, intacto (RF-23).
                for (overlay in linkOverlays) {
                    for (tint in overlay.tintStrokes) {
                        strokeRenderer.draw(
                            canvas = canvas.nativeCanvas,
                            stroke = tint,
                            strokeToScreenTransform = identityTransform,
                        )
                    }
                }
                for (item in strokes) {
                    strokeRenderer.draw(
                        canvas = canvas.nativeCanvas,
                        stroke = item.stroke,
                        strokeToScreenTransform = identityTransform,
                    )
                }
            }
            // Lazo en curso: contorno punteado, grosor constante en pantalla.
            if (lassoPoints.size >= 4) {
                drawPath(
                    polygonToPath(lassoPoints.toList()),
                    color = LASSO_STROKE_COLOR,
                    style = DrawStroke(
                        width = 3f / viewport.scale,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)),
                    ),
                )
            }
        }
      }

        // Capa wet: InProgressStrokesView + listener táctil, patrón oficial de
        // la Ink API para Views. Vive FUERA del graphicsLayer, en espacio de
        // pantalla: Compose no aplica la inversa de la escala del layer a los
        // MotionEvent de vistas interop (solo corrige traslación), así que
        // dentro del layer el trazo aterrizaría desplazado del pen en cuanto
        // hubiera zoom. La conversión pantalla→documento se hace explícita vía
        // motionEventToWorldTransform en startStroke; el render wet dibuja bajo
        // el pen y el handoff a dry entrega el trazo ya en coordenadas de
        // documento.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val strokesView = InProgressStrokesView(context).apply {
                    // Debe fijarse antes de eagerInit(): decide el render helper.
                    useHighLatencyRenderHelper = wetHighLatency
                    eagerInit()
                    addFinishedStrokesListener(
                        object : InProgressStrokesFinishedListener {
                            override fun onStrokesFinished(
                                finished: Map<InProgressStrokeId, Stroke>
                            ) {
                                // Handoff wet→dry: primero al estado que redibuja la
                                // capa dry, después liberar de la wet (mismo orden que
                                // documenta la librería para evitar parpadeo). Cada
                                // trazo nuevo recibe su ID estable aquí.
                                strokes.addAll(finished.values.map { IdStroke(newStrokeId(), it) })
                                removeFinishedStrokes(finished.keys)
                                if (finished.isNotEmpty()) currentOnInkModified.value()
                            }
                        }
                    )
                }
                val predictor = MotionEventPredictor.newInstance(strokesView)
                var currentPointerId = MotionEvent.INVALID_POINTER_ID
                var currentStrokeId: InProgressStrokeId? = null

                // Velocidad del evento anterior, para la compuerta de predicción
                // (ver rama ACTION_MOVE de la pluma).
                var lastSpeedPxPerMs = 0f
                var lastEventX = 0f
                var lastEventY = 0f
                var lastEventTime = 0L
                // Scratch reutilizable para leer puntos de entrada sin asignar.
                val scratchInput = StrokeInput()

                // Inversa del viewport en el instante actual: convierte los
                // MotionEvent (espacio de pantalla) a coordenadas de documento.
                // Se pasa a startStroke como motionEventToWorldTransform; la Ink
                // API la captura por trazo, y como los gestos de viewport se
                // bloquean mientras el pen está apoyado, no cambia a mitad de
                // trazo.
                fun motionEventToDocumentTransform() = Matrix().apply {
                    setTranslate(-viewport.offsetX, -viewport.offsetY)
                    postScale(1f / viewport.scale, 1f / viewport.scale)
                }

                fun eraserBoxAt(x: Float, y: Float, halfSize: Float) =
                    ImmutableBox.fromTwoPoints(
                        x - halfSize,
                        y - halfSize,
                        x + halfSize,
                        y + halfSize,
                    )

                // Goma de trazo: elimina de la capa dry todo trazo cuya malla
                // toque la caja centrada en (x, y), en coordenadas de documento.
                // Los IDs borrados se emiten para que el link pierda esos trazos.
                fun eraseStrokeAt(x: Float, y: Float, halfSize: Float) {
                    val box = eraserBoxAt(x, y, halfSize)
                    val removed = ArrayList<IdStroke>()
                    strokes.removeAll { item ->
                        val hit = item.stroke.shape.computeCoverageIsGreaterThan(box, 0f)
                        if (hit) removed.add(item)
                        hit
                    }
                    if (removed.isNotEmpty()) {
                        currentOnInkModified.value()
                        currentOnStrokesErased.value(removed)
                    }
                }

                // Distancia² del punto (px, py) al segmento (ax, ay)→(bx, by).
                fun distSqToSegment(
                    px: Float, py: Float,
                    ax: Float, ay: Float,
                    bx: Float, by: Float,
                ): Float {
                    val abx = bx - ax
                    val aby = by - ay
                    val abLenSq = abx * abx + aby * aby
                    val t = if (abLenSq == 0f) {
                        0f
                    } else {
                        (((px - ax) * abx + (py - ay) * aby) / abLenSq).coerceIn(0f, 1f)
                    }
                    val dx = px - (ax + t * abx)
                    val dy = py - (ay + t * aby)
                    return dx * dx + dy * dy
                }

                // Corta un trazo con la goma centrada en (cx, cy): elimina los
                // puntos de entrada dentro del radio y además rompe la unión entre
                // puntos consecutivos cuyo segmento pasa por la goma — necesario
                // porque en trazos rápidos los puntos quedan más separados que el
                // radio de la goma. Devuelve los segmentos sobrevivientes como
                // trazos nuevos, o null si no hubo cambios.
                fun splitStroke(
                    stroke: Stroke,
                    cx: Float,
                    cy: Float,
                    halfSize: Float,
                ): List<Stroke>? {
                    val inputs = stroke.inputs
                    val radiusSq = halfSize * halfSize
                    val pieces = mutableListOf<Stroke>()
                    var currentBatch = MutableStrokeInputBatch()
                    var currentCount = 0
                    var changed = false
                    var prevX = 0f
                    var prevY = 0f

                    fun closeCurrentPiece() {
                        // Segmentos de un solo punto no dibujan nada útil: se descartan.
                        if (currentCount >= 2) pieces.add(Stroke(stroke.brush, currentBatch))
                        currentBatch = MutableStrokeInputBatch()
                        currentCount = 0
                    }

                    for (i in 0 until inputs.size) {
                        inputs.populate(i, scratchInput)
                        val x = scratchInput.x
                        val y = scratchInput.y
                        val dx = x - cx
                        val dy = y - cy
                        if (dx * dx + dy * dy <= radiusSq) {
                            // Punto dentro de la goma: se elimina.
                            changed = true
                            closeCurrentPiece()
                        } else {
                            if (currentCount > 0 &&
                                distSqToSegment(cx, cy, prevX, prevY, x, y) <= radiusSq
                            ) {
                                // La goma cruza entre el punto anterior y este: se
                                // rompe la unión aunque ambos puntos sobrevivan.
                                changed = true
                                closeCurrentPiece()
                            }
                            currentBatch.add(scratchInput)
                            currentCount++
                            prevX = x
                            prevY = y
                        }
                    }
                    if (!changed) return null
                    closeCurrentPiece()
                    return pieces
                }

                // Goma parcial: recorta los trazos que intersectan la caja,
                // reemplazándolos in situ por sus segmentos sobrevivientes. Las
                // piezas HEREDAN el ID del padre → el link no se pierde por un
                // recorte parcial. Solo si el trazo se borra entero (sin piezas)
                // su ID desaparece y se emite para el borrado de link (RF-05a/b).
                fun erasePartialAt(x: Float, y: Float, halfSize: Float) {
                    val box = eraserBoxAt(x, y, halfSize)
                    var changed = false
                    val removed = ArrayList<IdStroke>()
                    for (i in strokes.indices.reversed()) {
                        val item = strokes[i]
                        // Pre-filtro barato por malla antes de recorrer puntos.
                        if (!item.stroke.shape.computeCoverageIsGreaterThan(box, 0f)) continue
                        val pieces = splitStroke(item.stroke, x, y, halfSize) ?: continue
                        strokes.removeAt(i)
                        if (pieces.isEmpty()) {
                            removed.add(item)
                        } else {
                            strokes.addAll(i, pieces.map { IdStroke(item.id, it) })
                        }
                        changed = true
                    }
                    if (changed) currentOnInkModified.value()
                    if (removed.isNotEmpty()) currentOnStrokesErased.value(removed)
                }

                // Recibe coordenadas de pantalla (las del MotionEvent) y las
                // convierte a documento; la caja de la goma también se escala
                // para cubrir la misma área física bajo el pen a cualquier zoom.
                fun eraseAt(tool: Tool, screenX: Float, screenY: Float) {
                    val x = viewport.screenToDocumentX(screenX)
                    val y = viewport.screenToDocumentY(screenY)
                    val halfSize = ERASER_HALF_SIZE_PX / viewport.scale
                    when (tool) {
                        Tool.ERASER_STROKE -> eraseStrokeAt(x, y, halfSize)
                        Tool.ERASER_PARTIAL -> erasePartialAt(x, y, halfSize)
                        Tool.PEN, Tool.LASSO -> Unit
                    }
                }

                // Añade el punto del MotionEvent (pantalla) al lazo en curso,
                // convertido a coordenadas de documento.
                fun addLassoPoint(screenX: Float, screenY: Float) {
                    lassoPoints.add(viewport.screenToDocumentX(screenX))
                    lassoPoints.add(viewport.screenToDocumentY(screenY))
                }

                val frame = FrameLayout(context).apply {
                    setOnTouchListener { view, event ->
                        // Palm rejection (RF-02), acotada al canvas: solo el S Pen
                        // dibuja o borra. Los streams de dedo/palma que caen sobre
                        // el canvas se consumen aquí sin efecto; los controles en
                        // Compose (título, chips, páginas) nunca pasan por este
                        // listener y sí aceptan dedo. En Samsung el S Pen es un
                        // dispositivo de entrada aparte: nunca comparte MotionEvent
                        // con los dedos, basta revisar el toolType del stream. Al
                        // no entregarse jamás punteros de dedo a InProgressStrokesView,
                        // tampoco puede corromperse su contabilidad de punteros.
                        val toolType = event.getToolType(0)
                        if (toolType != MotionEvent.TOOL_TYPE_STYLUS &&
                            toolType != MotionEvent.TOOL_TYPE_ERASER
                        ) {
                            return@setOnTouchListener true
                        }

                        predictor.record(event)

                        // RF-05c: goma temporal mientras el botón físico del stylus
                        // está presionado, sin SDK de Samsung. Además de
                        // BUTTON_STYLUS_PRIMARY se aceptan BUTTON_SECONDARY (mapeo
                        // legado de pens EMR en varios dispositivos, que reportan el
                        // botón lateral como "click derecho") y TOOL_TYPE_ERASER
                        // (digitalizadores que lo reportan como herramienta de
                        // borrado) — las tres son semántica estándar de Android.
                        val buttonEraser =
                            event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY) ||
                                event.isButtonPressed(MotionEvent.BUTTON_SECONDARY) ||
                                event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
                        val newTemporary = if (buttonEraser) lastEraserTool.value else null
                        if (newTemporary != temporaryEraserTool.value) {
                            // Log de transición: evidencia para diagnóstico remoto
                            // (adb logcat -s Turmalin).
                            Log.d(
                                "Turmalin",
                                "Atajo goma S Pen: $buttonEraser " +
                                    "(buttonState=${event.buttonState}, " +
                                    "toolType=${event.getToolType(0)})",
                            )
                            temporaryEraserTool.value = newTemporary
                        }
                        val effectiveTool =
                            if (buttonEraser) lastEraserTool.value else selectedTool.value

                        // One UI entrega el contacto del S Pen con botón presionado
                        // usando códigos de acción propietarios heredados (211=down,
                        // 212=up, 213=move, 214=cancel) dentro del MotionEvent
                        // estándar — verificado con la instrumentación de dispatch en
                        // este dispositivo. Se normalizan a las acciones estándar; no
                        // requiere ningún SDK de Samsung.
                        val action = when (event.actionMasked) {
                            211 -> MotionEvent.ACTION_DOWN
                            212 -> MotionEvent.ACTION_UP
                            213 -> MotionEvent.ACTION_MOVE
                            214 -> MotionEvent.ACTION_CANCEL
                            else -> event.actionMasked
                        }

                        when (action) {
                            MotionEvent.ACTION_DOWN -> {
                                // Entrega de eventos sin buffering del frame: clave
                                // para la latencia mínima.
                                view.requestUnbufferedDispatch(event)
                                stylusIsDown.value = true
                                currentPointerId = event.getPointerId(event.actionIndex)
                                lastSpeedPxPerMs = 0f
                                lastEventX = event.x
                                lastEventY = event.y
                                lastEventTime = event.eventTime
                                when (effectiveTool) {
                                    Tool.PEN -> currentStrokeId = strokesView.startStroke(
                                        event,
                                        currentPointerId,
                                        penBrush(penColorArgb.value, penSize.value),
                                        motionEventToDocumentTransform(),
                                    )
                                    // Lazo (RF-17): el pen no produce tinta,
                                    // solo acumula el polígono de selección.
                                    Tool.LASSO -> {
                                        lassoPoints.clear()
                                        addLassoPoint(event.x, event.y)
                                    }
                                    else -> eraseAt(effectiveTool, event.x, event.y)
                                }
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                if (effectiveTool == Tool.PEN) {
                                    // Goma→pluma a mitad de contacto (botón soltado):
                                    // la tinta empieza en la posición actual.
                                    val strokeId = currentStrokeId
                                        ?: strokesView
                                            .startStroke(
                                                event,
                                                currentPointerId,
                                                penBrush(penColorArgb.value, penSize.value),
                                                motionEventToDocumentTransform(),
                                            )
                                            .also { currentStrokeId = it }
                                    // Compuerta anti-rebote: al desacelerar bruscamente
                                    // (final de trazo rápido, esquinas) la extrapolación
                                    // del predictor se pasa de largo y luego se retrae al
                                    // confirmarse los puntos reales — el "rebote" visual.
                                    // Se suprime la predicción solo en esos tramos; en
                                    // movimiento sostenido se conserva su baja latencia.
                                    val dt = (event.eventTime - lastEventTime).coerceAtLeast(1L)
                                    val speed = hypot(
                                        event.x - lastEventX,
                                        event.y - lastEventY,
                                    ) / dt
                                    val prediction =
                                        if (lastSpeedPxPerMs > 0f &&
                                            speed < lastSpeedPxPerMs * 0.7f
                                        ) {
                                            null
                                        } else {
                                            predictor.predict()
                                        }
                                    lastSpeedPxPerMs = speed
                                    lastEventX = event.x
                                    lastEventY = event.y
                                    lastEventTime = event.eventTime
                                    strokesView.addToStroke(
                                        event,
                                        currentPointerId,
                                        strokeId,
                                        prediction,
                                    )
                                } else if (effectiveTool == Tool.LASSO) {
                                    // Botón de goma soltado a mitad de lazo: se
                                    // sigue acumulando el polígono; cualquier
                                    // tinta en curso se cancela.
                                    currentStrokeId?.let {
                                        strokesView.cancelStroke(it, event)
                                        currentStrokeId = null
                                    }
                                    for (h in 0 until event.historySize) {
                                        addLassoPoint(
                                            event.getHistoricalX(h),
                                            event.getHistoricalY(h),
                                        )
                                    }
                                    addLassoPoint(event.x, event.y)
                                } else {
                                    // Pluma→goma a mitad de contacto: el trazo de tinta
                                    // en curso se cancela y desde aquí se borra.
                                    currentStrokeId?.let {
                                        strokesView.cancelStroke(it, event)
                                        currentStrokeId = null
                                    }
                                    // Incluir puntos históricos del batch para no dejar
                                    // huecos al mover rápido.
                                    for (h in 0 until event.historySize) {
                                        eraseAt(
                                            effectiveTool,
                                            event.getHistoricalX(h),
                                            event.getHistoricalY(h),
                                        )
                                    }
                                    eraseAt(effectiveTool, event.x, event.y)
                                }
                                true
                            }
                            MotionEvent.ACTION_UP -> {
                                currentStrokeId?.let {
                                    strokesView.finishStroke(event, currentPointerId, it)
                                }
                                currentStrokeId = null
                                // Pen up con lazo: se cierra el polígono y se
                                // entrega (mínimo 3 vértices para tener área).
                                if (lassoPoints.size >= 6) {
                                    currentOnLassoComplete.value(lassoPoints.toList())
                                }
                                lassoPoints.clear()
                                temporaryEraserTool.value = null
                                stylusIsDown.value = false
                                true
                            }
                            MotionEvent.ACTION_CANCEL -> {
                                currentStrokeId?.let { strokesView.cancelStroke(it, event) }
                                currentStrokeId = null
                                lassoPoints.clear()
                                temporaryEraserTool.value = null
                                stylusIsDown.value = false
                                true
                            }
                            else -> false
                        }
                    }
                    addView(
                        strokesView,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }

                // RF-05c: la Activity intercepta el stream de goma en dispatch
                // (ver MainActivity) y lo entrega aquí completo. Las coordenadas
                // vienen en espacio de ventana: se convierten al espacio local del
                // canvas (difieren por la barra de estado).
                eraserRouter?.handler = { ev ->
                    val action = when (ev.actionMasked) {
                        211 -> MotionEvent.ACTION_DOWN
                        212 -> MotionEvent.ACTION_UP
                        213 -> MotionEvent.ACTION_MOVE
                        214 -> MotionEvent.ACTION_CANCEL
                        else -> ev.actionMasked
                    }
                    when (action) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                            val tool = lastEraserTool.value
                            temporaryEraserTool.value = tool
                            val loc = IntArray(2)
                            frame.getLocationInWindow(loc)
                            for (h in 0 until ev.historySize) {
                                eraseAt(
                                    tool,
                                    ev.getHistoricalX(h) - loc[0],
                                    ev.getHistoricalY(h) - loc[1],
                                )
                            }
                            eraseAt(tool, ev.x - loc[0], ev.y - loc[1])
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            temporaryEraserTool.value = null
                        }
                    }
                }

                frame
            },
            onRelease = { eraserRouter?.handler = null },
        )

        // Barra de herramientas encima del canvas, fuera del graphicsLayer de
        // viewport para que no escale ni se desplace con el pan/zoom (los taps
        // le llegan primero).
        InkToolbar(
            selectedTool = selectedTool.value,
            temporaryEraserTool = temporaryEraserTool.value,
            penColorArgb = penColorArgb.value,
            penSize = penSize.value,
            onToolSelect = { tool ->
                selectedTool.value = tool
                // El atajo del botón del S Pen solo recuerda gomas (RF-05c).
                if (tool == Tool.ERASER_STROKE || tool == Tool.ERASER_PARTIAL) {
                    lastEraserTool.value = tool
                }
            },
            onColorSelect = { penColorArgb.value = it },
            onSizeSelect = { penSize.value = it },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
        )
    }
}
