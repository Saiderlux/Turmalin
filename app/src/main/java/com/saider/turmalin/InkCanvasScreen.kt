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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
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

// Media caja (en px de pantalla) alrededor de la punta del pen para el hit-test
// de borrado. ~2.3 mm en la Tab S6 Lite.
private const val ERASER_HALF_SIZE_PX = 20f

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
    wetHighLatency: Boolean = true,
    eraserRouter: StylusEraserRouter? = null,
) {
    val finishedStrokes = remember { mutableStateListOf<Stroke>() }
    val strokeRenderer = remember { CanvasStrokeRenderer.create() }

    // Identidad: los trazos se capturan y se dibujan en coordenadas de pantalla,
    // sin zoom ni scroll en esta fase.
    val identityTransform = remember { Matrix() }

    // Selección persistente de la barra (RF-05c forma 1).
    val selectedTool = remember { mutableStateOf(Tool.PEN) }

    // Goma que usa el atajo del botón: la última seleccionada en la barra.
    val lastEraserTool = remember { mutableStateOf(Tool.ERASER_STROKE) }

    // Atajo temporal: no nulo solo mientras el botón del S Pen está presionado
    // con contacto en pantalla (RF-05c forma 2). No modifica selectedTool: al
    // soltar el botón, la herramienta vigente vuelve a ser la de la barra.
    val temporaryEraserTool = remember { mutableStateOf<Tool?>(null) }

    // Brush fijo: tinta negra sensible a presión, pensada para escritura.
    val blackPen = remember {
        Brush.createWithColorIntArgb(
            family = StockBrushes.pressurePen(StockBrushes.PressurePenVersion.LATEST),
            colorIntArgb = 0xFF000000.toInt(),
            size = 4f,
            epsilon = 0.1f,
        )
    }

    // El filtrado de dedo/palma sucede en MainActivity.dispatchTouchEvent: aquí
    // solo llegan streams del S Pen (ver comentario en MainActivity).
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
    ) {
        // Capa dry: trazos terminados, redibujados en cada recomposición.
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawIntoCanvas { canvas ->
                for (stroke in finishedStrokes) {
                    strokeRenderer.draw(
                        canvas = canvas.nativeCanvas,
                        stroke = stroke,
                        strokeToScreenTransform = identityTransform,
                    )
                }
            }
        }

        // Capa wet: InProgressStrokesView + listener táctil, patrón oficial de la
        // Ink API para Views. El listener solo ve el S Pen (un único puntero).
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
                                strokes: Map<InProgressStrokeId, Stroke>
                            ) {
                                // Handoff wet→dry: primero al estado que redibuja la
                                // capa dry, después liberar de la wet (mismo orden que
                                // documenta la librería para evitar parpadeo).
                                finishedStrokes.addAll(strokes.values)
                                removeFinishedStrokes(strokes.keys)
                            }
                        }
                    )
                }
                val predictor = MotionEventPredictor.newInstance(strokesView)
                var currentPointerId = MotionEvent.INVALID_POINTER_ID
                var currentStrokeId: InProgressStrokeId? = null
                // Scratch reutilizable para leer puntos de entrada sin asignar.
                val scratchInput = StrokeInput()

                fun eraserBoxAt(x: Float, y: Float) = ImmutableBox.fromTwoPoints(
                    x - ERASER_HALF_SIZE_PX,
                    y - ERASER_HALF_SIZE_PX,
                    x + ERASER_HALF_SIZE_PX,
                    y + ERASER_HALF_SIZE_PX,
                )

                // Goma de trazo: elimina de la capa dry todo trazo cuya malla
                // toque la caja centrada en (x, y).
                fun eraseStrokeAt(x: Float, y: Float) {
                    val box = eraserBoxAt(x, y)
                    finishedStrokes.removeAll { stroke ->
                        stroke.shape.computeCoverageIsGreaterThan(box, 0f)
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
                fun splitStroke(stroke: Stroke, cx: Float, cy: Float): List<Stroke>? {
                    val inputs = stroke.inputs
                    val radiusSq = ERASER_HALF_SIZE_PX * ERASER_HALF_SIZE_PX
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
                // reemplazándolos in situ por sus segmentos sobrevivientes.
                fun erasePartialAt(x: Float, y: Float) {
                    val box = eraserBoxAt(x, y)
                    for (i in finishedStrokes.indices.reversed()) {
                        val stroke = finishedStrokes[i]
                        // Pre-filtro barato por malla antes de recorrer puntos.
                        if (!stroke.shape.computeCoverageIsGreaterThan(box, 0f)) continue
                        val pieces = splitStroke(stroke, x, y) ?: continue
                        finishedStrokes.removeAt(i)
                        finishedStrokes.addAll(i, pieces)
                    }
                }

                fun eraseAt(tool: Tool, x: Float, y: Float) {
                    when (tool) {
                        Tool.ERASER_STROKE -> eraseStrokeAt(x, y)
                        Tool.ERASER_PARTIAL -> erasePartialAt(x, y)
                        Tool.PEN -> Unit
                    }
                }

                val frame = FrameLayout(context).apply {
                    setOnTouchListener { view, event ->
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
                                currentPointerId = event.getPointerId(event.actionIndex)
                                if (effectiveTool == Tool.PEN) {
                                    currentStrokeId =
                                        strokesView.startStroke(event, currentPointerId, blackPen)
                                } else {
                                    eraseAt(effectiveTool, event.x, event.y)
                                }
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                if (effectiveTool == Tool.PEN) {
                                    // Goma→pluma a mitad de contacto (botón soltado):
                                    // la tinta empieza en la posición actual.
                                    val strokeId = currentStrokeId
                                        ?: strokesView
                                            .startStroke(event, currentPointerId, blackPen)
                                            .also { currentStrokeId = it }
                                    strokesView.addToStroke(
                                        event,
                                        currentPointerId,
                                        strokeId,
                                        predictor.predict(),
                                    )
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
                                temporaryEraserTool.value = null
                                true
                            }
                            MotionEvent.ACTION_CANCEL -> {
                                currentStrokeId?.let { strokesView.cancelStroke(it, event) }
                                currentStrokeId = null
                                temporaryEraserTool.value = null
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

        // Barra de herramientas encima del canvas (los taps le llegan primero).
        InkToolbar(
            selectedTool = selectedTool.value,
            temporaryEraserTool = temporaryEraserTool.value,
            onToolSelect = { tool ->
                selectedTool.value = tool
                if (tool != Tool.PEN) lastEraserTool.value = tool
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
        )
    }
}
