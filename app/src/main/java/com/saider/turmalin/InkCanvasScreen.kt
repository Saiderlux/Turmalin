package com.saider.turmalin

import android.graphics.Matrix
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
import kotlin.math.roundToInt

// Media caja (en px de pantalla) alrededor de la punta del pen para el hit-test
// de borrado. ~2.3 mm en la Tab S6 Lite. Con zoom se convierte a espacio de
// documento (dividida por la escala) para que la goma cubra siempre la misma
// área física bajo el pen.
private const val ERASER_HALF_SIZE_PX = 20f

// MotionEvent.TOOL_TYPE_PALM está @hide en el SDK público; el valor es estable
// en AOSP (RF-09a, rama de clasificación nativa de palma).
private const val TOOL_TYPE_PALM = 5

// Color fijo reservado para el overlay de links (RF-23a): nunca formará parte
// de la paleta de plumas del usuario. Halo semitransparente ceñido a la tinta.
val LINK_OVERLAY_COLOR = Color(0x553D5AFE)

// Margen del halo del overlay sobre el grosor del trazo entintado: el halo
// mide SIEMPRE el grosor del ink + este margen, para asomar ceñido alrededor
// de la tinta a cualquier grosor de pluma — con un grosor fijo, un trazo más
// grueso que el halo lo tapaba por completo.
private const val LINK_OVERLAY_MARGIN = 10f

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

/**
 * Trazo de halo para un trazo linkeado: mismo recorrido, color reservado
 * semitransparente, grosor del ink + [LINK_OVERLAY_MARGIN]. Único constructor
 * del tinte (pantalla y PDF): el halo nunca queda tapado por tinta gruesa.
 */
fun linkTintStroke(inkStroke: Stroke): Stroke = Stroke(
    Brush.createWithColorIntArgb(
        family = StockBrushes.pressurePen(StockBrushes.PressurePenVersion.LATEST),
        colorIntArgb = LINK_OVERLAY_COLOR.toArgb(),
        size = inkStroke.brush.size + LINK_OVERLAY_MARGIN,
        epsilon = 0.1f,
    ),
    inkStroke.inputs,
)

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
 * para las gomas. El viewport se manipula con el tacto — un dedo panea, dos
 * hacen pinch (RF-09a, ver [TouchViewportGesture]) — y nunca mientras el
 * S Pen está apoyado o en rango de hover.
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
    // Fondo de página (RF-06): capa de render bajo la tinta, nunca ink real.
    background: PaperBackground = PaperBackground(),
    // Tamaño de página (RF-06a): la hoja se dibuja como guía visual; el pan es
    // libre y la tinta puede salirse del borde.
    pageSize: PageSize = DEFAULT_PAGE_SIZE,
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
    // Long-press de un dedo sobre la tinta de un link: menú contextual para
    // eliminar el vínculo deliberadamente (complementa RF-05a/b).
    onLinkLongPress: (targetUuid: String) -> Unit = {},
    // Trazos cuyo ID desaparece de la página por la goma (borrado total). El
    // borrado parcial NO los emite: sus piezas heredan el ID (RF-05a/b).
    onStrokesErased: (List<IdStroke>) -> Unit = {},
    // Historial deshacer/rehacer de ink (RF-37): en memoria, por sesión de
    // página. El dueño (NoteScreen) lo reinicia al cambiar de página.
    history: UndoHistory<List<IdStroke>> = remember { UndoHistory() },
) {
    val strokeRenderer = remember { CanvasStrokeRenderer.create() }

    // Vía rememberUpdatedState porque se invoca desde closures del factory de
    // AndroidView, que solo corre una vez y capturaría una lambda vieja.
    val currentOnInkModified = rememberUpdatedState(onInkModified)
    val currentOnSwipePage = rememberUpdatedState(onSwipePage)
    val currentLinkOverlays = rememberUpdatedState(linkOverlays)
    val currentOnLassoComplete = rememberUpdatedState(onLassoComplete)
    val currentOnLinkTap = rememberUpdatedState(onLinkTap)
    val currentOnLinkLongPress = rememberUpdatedState(onLinkLongPress)
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
    val penSize = remember { mutableStateOf(DEFAULT_PEN_SIZE) }
    // Selector de color personalizado (RF-04) abierto desde la rueda de la barra.
    var showColorPicker by remember { mutableStateOf(false) }

    // Centrado inicial (RF-09b): al conocerse el tamaño real del lienzo por
    // primera vez, encaja la hoja centrada en vez de dejarla pegada al origen
    // documento (0,0) en la esquina superior izquierda.
    var centeredOnce by remember { mutableStateOf(false) }

    // Barra de herramientas acoplable: borde actual, offset del arrastre en
    // curso y centro real de la barra (para decidir el borde al soltar).
    var dockEdge by rememberSaveable { mutableStateOf(DockEdge.TOP) }
    var toolbarDragOffset by remember { mutableStateOf(Offset.Zero) }
    var toolbarCenter by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Palm rejection local al canvas (ver listener del FrameLayout): el resto
    // de la pantalla (título, chips, controles de página) sí responde al dedo.
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Sin esto, la hoja (PageBackgroundLayer, dibujada con el offset del
            // viewport) y la tinta pintan fuera de estos límites al hacer pan —
            // Compose no clipea un Canvas a su tamaño por default — y quedan
            // encima de la barra superior de la nota.
            .clipToBounds()
            .onSizeChanged { size ->
                canvasSize = size
                if (!centeredOnce && size.width > 0 && size.height > 0) {
                    centeredOnce = true
                    viewport.centerPage(
                        pageSize.widthDoc(),
                        pageSize.heightDoc(),
                        size.width.toFloat(),
                        size.height.toFloat(),
                    )
                }
            }
            // Fondo gris del lienzo (token del tema); la hoja blanca de la
            // página se dibuja encima (RF-06a) y sigue blanca en tema oscuro.
            .background(Theme.colors.canvasBackdrop)
            // Los gestos táctiles de viewport (RF-09a) NO se manejan aquí: viven
            // en el setOnTouchListener del FrameLayout de la capa wet, porque la
            // clasificación nativa de palma (FLAG_CANCELED, toolType, touchMajor)
            // solo existe en el MotionEvent crudo y los PointerId de Compose no
            // mapean a los pointer id del MotionEvent. Un único punto de entrada
            // para todo el input del canvas.
    ) {
      // Hoja de página + fondo (RF-06/RF-06a): capa de render bajo la tinta. Usa
      // el mismo viewport que la tinta, así hoja, líneas/cuadrícula y trazos
      // panean y hacen zoom sin desalinearse. No es ink: intocable por lazo/goma.
      PageBackgroundLayer(pageSize = pageSize, background = background, viewport = viewport)

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
                                // RF-37: el estado previo al trazo nuevo entra al historial.
                                if (finished.isNotEmpty()) history.commit(strokes.toList())
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
                // Devuelve true si algo cambió (para el historial, RF-37).
                fun eraseStrokeAt(x: Float, y: Float, halfSize: Float): Boolean {
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
                    return removed.isNotEmpty()
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
                // Devuelve true si algo cambió (para el historial, RF-37).
                fun erasePartialAt(x: Float, y: Float, halfSize: Float): Boolean {
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
                    return changed
                }

                // Estado de la página al empezar el gesto de goma vigente, para
                // el historial (RF-37): un gesto completo (pen down→up) es UN
                // paso, no uno por evento de movimiento. Se captura perezoso en
                // el primer evento que de verdad muta la tinta.
                var eraseGestureBefore: List<IdStroke>? = null

                // Recibe coordenadas de pantalla (las del MotionEvent) y las
                // convierte a documento; la caja de la goma también se escala
                // para cubrir la misma área física bajo el pen a cualquier zoom.
                fun eraseAt(tool: Tool, screenX: Float, screenY: Float) {
                    val x = viewport.screenToDocumentX(screenX)
                    val y = viewport.screenToDocumentY(screenY)
                    val halfSize = ERASER_HALF_SIZE_PX / viewport.scale
                    val before = if (eraseGestureBefore == null) strokes.toList() else null
                    val changed = when (tool) {
                        Tool.ERASER_STROKE -> eraseStrokeAt(x, y, halfSize)
                        Tool.ERASER_PARTIAL -> erasePartialAt(x, y, halfSize)
                        Tool.PEN, Tool.LASSO -> false
                    }
                    if (changed && before != null) eraseGestureBefore = before
                }

                // Cierra el gesto de goma: si borró algo, el estado previo entra
                // al historial como un único paso (RF-37).
                fun commitEraseGesture() {
                    eraseGestureBefore?.let { history.commit(it) }
                    eraseGestureBefore = null
                }

                // Añade el punto del MotionEvent (pantalla) al lazo en curso,
                // convertido a coordenadas de documento.
                fun addLassoPoint(screenX: Float, screenY: Float) {
                    lassoPoints.add(viewport.screenToDocumentX(screenX))
                    lassoPoints.add(viewport.screenToDocumentY(screenY))
                }

                // ── Gestos táctiles de viewport (RF-09a/09b) ──
                // Un dedo panea/pagina/tapea, dos hacen pinch; la palma se
                // descarta por clasificación nativa (API 33+) o por área de
                // contacto (pre-33). Ver TouchViewportGesture.

                // True mientras el S Pen está en rango de hover sobre el canvas:
                // capa adicional de seguridad (RF-09b) — con la mano escribiendo,
                // ningún contacto táctil mueve el viewport.
                var stylusInRange = false

                // Umbral del heurístico pre-API 33 en px físicos de este panel.
                val palmTouchMajorPx =
                    PALM_TOUCH_MAJOR_MM * context.resources.displayMetrics.xdpi / 25.4f

                // Hit-test de un punto de pantalla contra la TINTA de los links.
                // Se prueba contra la malla de los trazos (ceñido), no contra
                // una caja — así dos links con cajas solapadas no se confunden.
                fun linkAt(screenX: Float, screenY: Float): LinkOverlay? {
                    val x = viewport.screenToDocumentX(screenX)
                    val y = viewport.screenToDocumentY(screenY)
                    val slop = LINK_TAP_SLOP_PX / viewport.scale
                    val hitBox = ImmutableBox.fromTwoPoints(
                        x - slop, y - slop, x + slop, y + slop,
                    )
                    return currentLinkOverlays.value.firstOrNull { overlay ->
                        overlay.tintStrokes.any {
                            it.shape.computeCoverageIsGreaterThan(hitBox, 0f)
                        }
                    }
                }

                val touchGesture = TouchViewportGesture(
                    viewport = viewport,
                    onPaginate = { direction -> currentOnSwipePage.value(direction) },
                    // Tap sobre un link: navegar al destino. Long-press: menú
                    // contextual para eliminar el vínculo.
                    onTap = { x, y ->
                        linkAt(x, y)?.let { currentOnLinkTap.value(it.targetUuid) }
                    },
                    onLongPress = { x, y ->
                        linkAt(x, y)?.let { currentOnLinkLongPress.value(it.targetUuid) }
                    },
                )

                // ¿Este puntero es palma al momento del down? Algunos stacks lo
                // reportan directo como toolType (TOOL_TYPE_PALM sigue @hide en
                // el SDK, valor estable 5 en AOSP); antes de API 33 se estima
                // por área de contacto. En API 33 exacto no hay señal en el
                // down: la clasificación del sistema llega después vía
                // FLAG_CANCELED/ACTION_CANCEL.
                fun isPalmPointer(ev: MotionEvent, index: Int): Boolean = when {
                    ev.getToolType(index) == TOOL_TYPE_PALM -> true
                    Build.VERSION.SDK_INT < 33 ->
                        ev.getTouchMajor(index) >= palmTouchMajorPx
                    else -> false
                }

                fun handleViewportTouch(ev: MotionEvent) {
                    val stylusActive = stylusIsDown.value || stylusInRange
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                            if (stylusActive) return
                            val i = ev.actionIndex
                            touchGesture.pointerDown(
                                ev.getPointerId(i),
                                ev.getX(i),
                                ev.getY(i),
                                isPalmPointer(ev, i),
                            )
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (stylusActive) {
                                // Pen apoyado o en hover: el gesto se abandona entero.
                                touchGesture.end()
                                return
                            }
                            if (Build.VERSION.SDK_INT < 33) {
                                // Reclasificación heurística: una palma puede
                                // aterrizar con área de dedo y crecer después.
                                for (i in 0 until ev.pointerCount) {
                                    if (ev.getTouchMajor(i) >= palmTouchMajorPx) {
                                        touchGesture.pointerCanceled(ev.getPointerId(i))
                                    }
                                }
                            }
                            touchGesture.move(
                                (0 until ev.pointerCount).map { i ->
                                    TouchPoint(ev.getPointerId(i), ev.getX(i), ev.getY(i))
                                }
                            )
                        }
                        MotionEvent.ACTION_POINTER_UP -> {
                            // API 33+: FLAG_CANCELED marca el puntero que el
                            // sistema retira por ser palma (no un dedo que sube).
                            val id = ev.getPointerId(ev.actionIndex)
                            val canceledAsPalm = Build.VERSION.SDK_INT >= 33 &&
                                (ev.flags and MotionEvent.FLAG_CANCELED) != 0
                            if (canceledAsPalm) {
                                touchGesture.pointerCanceled(id)
                            } else {
                                touchGesture.pointerUp(id)
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!stylusActive) {
                                touchGesture.pointerUp(ev.getPointerId(ev.actionIndex))
                            }
                            touchGesture.end()
                        }
                        // Cancelación del gesto completo: en API 33+ es como el
                        // sistema descarta un contacto único ya clasificado palma.
                        MotionEvent.ACTION_CANCEL -> touchGesture.end()
                    }
                }

                val frame = FrameLayout(context).apply {
                    setOnTouchListener { view, event ->
                        // Único punto de entrada del input del canvas: el S Pen
                        // dibuja o borra; los streams de dedo/palma van al gesto
                        // de viewport (RF-09a) con la palma descartada por la
                        // clasificación del sistema, y JAMÁS a
                        // InProgressStrokesView, cuya contabilidad de punteros no
                        // puede corromperse. Los controles en Compose (título,
                        // chips, páginas) nunca pasan por este listener y sí
                        // aceptan dedo. En Samsung el S Pen es un dispositivo de
                        // entrada aparte: nunca comparte MotionEvent con los
                        // dedos, basta revisar el toolType del stream.
                        val toolType = event.getToolType(0)
                        if (toolType != MotionEvent.TOOL_TYPE_STYLUS &&
                            toolType != MotionEvent.TOOL_TYPE_ERASER
                        ) {
                            handleViewportTouch(event)
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
                                commitEraseGesture()
                                temporaryEraserTool.value = null
                                stylusIsDown.value = false
                                true
                            }
                            MotionEvent.ACTION_CANCEL -> {
                                currentStrokeId?.let { strokesView.cancelStroke(it, event) }
                                currentStrokeId = null
                                lassoPoints.clear()
                                // Los borrados ya aplicados no se revierten por el
                                // cancel: el paso del historial se cierra igual.
                                commitEraseGesture()
                                temporaryEraserTool.value = null
                                stylusIsDown.value = false
                                true
                            }
                            else -> false
                        }
                    }
                    // S Pen en rango de hover (RF-09b): mientras la punta flota
                    // sobre el canvas, el tacto queda inerte — la mano ya está
                    // en posición de escritura. El evento no se consume.
                    setOnHoverListener { _, ev ->
                        val hoverTool = ev.getToolType(0)
                        if (hoverTool == MotionEvent.TOOL_TYPE_STYLUS ||
                            hoverTool == MotionEvent.TOOL_TYPE_ERASER
                        ) {
                            when (ev.actionMasked) {
                                MotionEvent.ACTION_HOVER_ENTER,
                                MotionEvent.ACTION_HOVER_MOVE,
                                -> stylusInRange = true
                                MotionEvent.ACTION_HOVER_EXIT -> stylusInRange = false
                            }
                        }
                        false
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
                            commitEraseGesture()
                            temporaryEraserTool.value = null
                        }
                    }
                }

                frame
            },
            onRelease = { eraserRouter?.handler = null },
        )

        // Barra de herramientas acoplable encima del canvas, fuera del
        // graphicsLayer de viewport para que no escale ni se desplace con el
        // pan/zoom (los taps le llegan primero). El asa la arrastra; al soltar
        // se acopla al borde más cercano y se orienta según el borde.
        val dockAlignment = when (dockEdge) {
            DockEdge.TOP -> Alignment.TopCenter
            DockEdge.BOTTOM -> Alignment.BottomCenter
            DockEdge.LEFT -> Alignment.CenterStart
            DockEdge.RIGHT -> Alignment.CenterEnd
        }
        InkToolbar(
            selectedTool = selectedTool.value,
            temporaryEraserTool = temporaryEraserTool.value,
            penColorArgb = penColorArgb.value,
            penSize = penSize.value,
            canUndo = history.canUndo,
            canRedo = history.canRedo,
            // RF-37: deshacer/rehacer restauran la instantánea y encienden el
            // dirty flag de tinta (el contenido de la página sí cambió).
            onUndo = {
                history.undo(strokes.toList())?.let { previous ->
                    strokes.clear()
                    strokes.addAll(previous)
                    onInkModified()
                }
            },
            onRedo = {
                history.redo(strokes.toList())?.let { next ->
                    strokes.clear()
                    strokes.addAll(next)
                    onInkModified()
                }
            },
            onToolSelect = { tool ->
                selectedTool.value = tool
                // El atajo del botón del S Pen solo recuerda gomas (RF-05c).
                if (tool == Tool.ERASER_STROKE || tool == Tool.ERASER_PARTIAL) {
                    lastEraserTool.value = tool
                }
            },
            onColorSelect = { penColorArgb.value = it },
            onSizeSelect = { penSize.value = it },
            onOpenColorPicker = { showColorPicker = true },
            vertical = dockEdge.isVertical,
            onDrag = { delta -> toolbarDragOffset += delta },
            onDragEnd = {
                if (canvasSize.width > 0) {
                    dockEdge = nearestDockEdge(toolbarCenter, canvasSize)
                }
                toolbarDragOffset = Offset.Zero
            },
            modifier = Modifier
                .align(dockAlignment)
                .padding(12.dp)
                .offset {
                    IntOffset(
                        toolbarDragOffset.x.roundToInt(),
                        toolbarDragOffset.y.roundToInt(),
                    )
                }
                .onGloballyPositioned { coords ->
                    val pos = coords.positionInParent()
                    toolbarCenter = Offset(
                        pos.x + coords.size.width / 2f,
                        pos.y + coords.size.height / 2f,
                    )
                },
        )

        // RF-04: selector personalizado; el color elegido queda activo en la
        // pluma (los presets siguen disponibles como accesos rápidos).
        if (showColorPicker) {
            ColorPickerDialog(
                initialArgb = penColorArgb.value,
                onConfirm = { argb ->
                    penColorArgb.value = argb
                    showColorPicker = false
                },
                onDismiss = { showColorPicker = false },
            )
        }
    }
}
