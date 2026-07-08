package com.saider.turmalin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// Radio del nodo (RF-22a): crece con la cantidad de conexiones de la nota.
private const val NODE_BASE_RADIUS = 18f
private const val NODE_RADIUS_PER_LINK = 6f
private const val NODE_MAX_RADIUS = 60f

// Radio mínimo del hit-test táctil (los nodos chicos serían difíciles de tocar).
private const val NODE_HIT_RADIUS = 44f

// Arrastre mínimo (px de pantalla) para mover un nodo en vez de tocarlo.
private const val NODE_DRAG_SLOP = 16f

// Atenuación del resto del grafo cuando hay un nodo seleccionado (RF-22).
private const val DIMMED_ALPHA = 0.2f

// Ancho (en unidades de viewport.scale) de la rampa de fade de etiquetas: el
// alpha baja de 1 a 0 en esta ventana alrededor del umbral, en vez de cortar
// de golpe al cruzarlo.
private const val TEXT_FADE_WIDTH = 0.5f

private val CONNECTED_NODE_COLOR = Color(0xFF3949AB)
private val ORPHAN_NODE_COLOR = Color(0xFFB0BEC5)
private val EDGE_COLOR = Color(0xFF9FA8DA)

fun nodeRadius(degree: Int): Float =
    min(NODE_BASE_RADIUS + NODE_RADIUS_PER_LINK * degree, NODE_MAX_RADIUS)

/**
 * Vista de grafo (RF-19..22a, UC-07): cada nota es un nodo, las aristas
 * salen de graph.json. Layout dirigido por fuerzas en vivo ([GraphSimulation],
 * estilo Obsidian): los nodos se reparten solos y arrastrar uno reinicia el
 * calor para que los vecinos lo sigan. Pan con un dedo en el vacío, pinch de
 * dos dedos para zoom; arrastrar un nodo lo mueve. Tap selecciona (resalta
 * nodo + vecinos, atenúa el resto), doble tap abre la nota (RF-22).
 */
@Composable
fun GraphScreen(
    repo: NoteRepository,
    onOpenNote: (NoteMeta) -> Unit,
    onBack: () -> Unit,
) {
    val notes = remember { repo.listNotes() }
    // Grafo activo (RF-36): las aristas hacia/desde notas en papelera no se
    // renderizan — el filtro garantiza que ambos extremos existan en el layout.
    var graph by remember { mutableStateOf(repo.activeGraph()) }
    var selectedNodeUuid by remember { mutableStateOf<String?>(null) }
    // Ajustes del grafo (perillas de fuerzas/render) y visibilidad del panel.
    var settings by remember { mutableStateOf(repo.loadGraphSettings()) }
    var showPanel by remember { mutableStateOf(false) }
    fun updateSettings(next: GraphSettings) { settings = next }
    fun commitSettings() { repo.saveGraphSettings(settings) }

    BackHandler(onBack = onBack)

    val colors = Theme.colors

    Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackArrow(onClick = onBack)
            BasicText(
                text = "Grafo",
                style = TextStyle(
                    color = colors.textPrimary,
                    fontSize = AppType.title,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(modifier = Modifier.weight(1f))
            // Abre/cierra el panel de ajustes del grafo (estilo Obsidian).
            AppButton(label = "Ajustes", onClick = { showPanel = !showPanel })
        }

        if (notes.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText(
                    text = "El grafo aparecerá cuando existan notas",
                    style = TextStyle(color = colors.textHint, fontSize = AppType.label),
                )
            }
            return@Column
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val width = constraints.maxWidth.toFloat()
            val height = constraints.maxHeight.toFloat()

            // Grado por nodo = salientes + entrantes (RF-22a radio, RF-20 huérfano).
            // Los links son dirigidos, así que se cuentan ambas direcciones.
            val incoming = remember(graph) {
                HashMap<String, Int>().apply {
                    for ((_, outs) in graph) for (b in outs) merge(b, 1, Int::plus)
                }
            }
            val degrees = notes.associate { note ->
                note.uuid to graph[note.uuid].orEmpty().size + (incoming[note.uuid] ?: 0)
            }

            // Simulación en vivo; sobrevive a cambios del grafo (solo se le
            // actualizan las aristas) para no teletransportar todo al crear un
            // link. Se recrea únicamente si cambia el conjunto de notas o el tamaño.
            val ids = remember(notes) { notes.map { it.uuid } }
            val edges = graph.flatMap { (a, bs) -> bs.map { a to it } }
            // Centro del plano = centro inicial de pantalla; el grafo se despliega
            // a su alrededor sin caja (plano infinito, se explora con pan/zoom).
            val sim = remember(ids, width, height) {
                GraphSimulation(ids, edges, width / 2f, height / 2f)
            }

            // frame se incrementa cada paso de físicas para forzar el redibujo
            // (las posiciones viven en arrays planos, no en estado observable).
            var frame by remember { mutableIntStateOf(0) }
            // kick reinicia el loop cuando ya se había enfriado (p. ej. al empezar
            // a arrastrar tras estar quieto).
            var kick by remember { mutableIntStateOf(0) }
            // Auto-encuadre: mientras está activo, la vista sigue al grafo para
            // dejarlo enmarcado al abrir (huérfanos incluidos). El primer toque
            // del usuario lo desactiva y le cede el pan/zoom.
            var autoFit by remember { mutableStateOf(true) }

            fun pos(uuid: String) = Offset(sim.xOf(uuid), sim.yOf(uuid))

            // Zoom del grafo baja de 0.5x (min del canvas): un vault grande puede
            // necesitar alejarse bastante para entrar entero.
            val viewport = remember { CanvasViewport(minScale = 0.15f) }
            fun screenToWorld(p: Offset) = Offset(
                viewport.screenToDocumentX(p.x),
                viewport.screenToDocumentY(p.y),
            )

            // Escala de referencia ("hogar"): la que deja el auto-encuadre, es
            // decir la vista con la que se abre el grafo. El fade de texto se
            // mide relativo a ella (ver más abajo) porque viewport.scale en
            // crudo depende del tamaño del vault — un vault grande encuadra a
            // 0.2x y uno chico a 1.5x, así que un umbral en escala absoluta
            // necesitaría un zoom distinto (a veces absurdo) según cuántas
            // notas haya. Relativo al hogar, 1.0 siempre significa "la vista
            // con la que abriste el grafo", sin importar el tamaño del vault.
            var homeScale by remember { mutableStateOf(1f) }

            // Encuadra todos los nodos (con su radio y la etiqueta) en pantalla.
            fun fitToGraph() {
                if (notes.isEmpty()) return
                var minX = Float.POSITIVE_INFINITY
                var minY = Float.POSITIVE_INFINITY
                var maxX = Float.NEGATIVE_INFINITY
                var maxY = Float.NEGATIVE_INFINITY
                for (note in notes) {
                    val p = pos(note.uuid)
                    val r = nodeRadius(degrees[note.uuid] ?: 0)
                    minX = minOf(minX, p.x - r)
                    minY = minOf(minY, p.y - r)
                    maxX = maxOf(maxX, p.x + r)
                    maxY = maxOf(maxY, p.y + r + 40f) // etiqueta bajo el nodo
                }
                viewport.fit(minX, minY, maxX, maxY, width, height, 100f)
                homeScale = viewport.scale
            }

            // Perillas de Forces → simulación. Un cambio reactiva el layout
            // (kick) para que el grafo se reacomode aunque estuviera congelado.
            // Las de Display (tamaño, grosor, fade, flechas) solo redibujan.
            LaunchedEffect(
                sim,
                settings.centerGravity,
                settings.repulsionStrength,
                settings.linkStrength,
                settings.idealDistance,
            ) {
                sim.centerGravity = settings.centerGravity
                sim.repulsionStrength = settings.repulsionStrength
                sim.linkStrength = settings.linkStrength
                sim.idealDistance = settings.idealDistance
                kick++
            }

            LaunchedEffect(sim, graph, kick) {
                sim.setEdges(graph.flatMap { (a, bs) -> bs.map { a to it } })
                sim.reheat()
                while (true) {
                    withFrameNanos { }
                    sim.step()
                    if (autoFit) fitToGraph()
                    frame++
                    if (sim.settled) break
                }
            }

            fun nodeAt(world: Offset, except: String? = null): NoteMeta? =
                notes.lastOrNull { note ->
                    note.uuid != except &&
                        (pos(note.uuid) - world).getDistance() <=
                        maxOf(nodeRadius(degrees[note.uuid] ?: 0), NODE_HIT_RADIUS)
                }

            // Doble tap: dos taps sobre el mismo nodo dentro de esta ventana.
            // Se detecta a mano (no con detectTapGestures) para no competir con
            // el detector de arrastre en el mismo Canvas — dos pointerInput
            // rivales se roban el gesto y el drag nunca dispara.
            var lastTapUuid by remember { mutableStateOf<String?>(null) }
            var lastTapAtMillis by remember { mutableStateOf(0L) }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    // Un único gestor para todo (RF-22): tap selecciona,
                    // doble tap abre, arrastrar un nodo lo mueve; un dedo en
                    // el vacío hace pan y dos dedos hacen pinch.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val grabbed = nodeAt(screenToWorld(down.position))?.uuid
                            var dragging = false
                            var moved = false
                            var previousCentroid: Offset? = null
                            var previousDistance = 0f
                            var lastSingle = down.position
                            // Al soltar un dedo del pellizco, `pressed[0]` ya
                            // no es el centroide de 2 dedos que veníamos
                            // siguiendo — es solo uno de ellos. Sin este flag,
                            // el primer frame en 1 dedo calcula el delta
                            // contra el `lastSingle` viejo (nunca actualizado
                            // durante el pellizco) y el pan salta de golpe
                            // toda la distancia recorrida por el gesto entero
                            // ("rebote" al soltar). Se re-ancla sin aplicar
                            // pan ese primer frame.
                            var wasMultiTouch = false

                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break

                                if (pressed.size >= 2) {
                                    moved = true
                                    autoFit = false // el usuario toma el control de la vista
                                    // Pinch-to-zoom + pan de dos dedos.
                                    val a = pressed[0].position
                                    val b = pressed[1].position
                                    val centroid = (a + b) / 2f
                                    val distance = (a - b).getDistance()
                                    if (previousDistance > 0f && distance > 0f) {
                                        viewport.pinch(
                                            focalX = centroid.x,
                                            focalY = centroid.y,
                                            zoomDelta = distance / previousDistance,
                                        )
                                    }
                                    previousCentroid?.let {
                                        viewport.pan(centroid.x - it.x, centroid.y - it.y)
                                    }
                                    previousDistance = distance
                                    previousCentroid = centroid
                                    wasMultiTouch = true
                                    pressed.forEach { it.consume() }
                                } else {
                                    previousDistance = 0f
                                    previousCentroid = null
                                    val position = pressed[0].position
                                    if (wasMultiTouch) {
                                        // Recién se soltó un dedo del pellizco:
                                        // re-ancla sin pan este frame (ver
                                        // comentario de `wasMultiTouch` arriba).
                                        wasMultiTouch = false
                                        lastSingle = position
                                        continue
                                    }
                                    if ((position - down.position).getDistance() >
                                        NODE_DRAG_SLOP
                                    ) {
                                        moved = true
                                        autoFit = false // pan o arrastre: el usuario controla la vista
                                    }
                                    if (grabbed != null) {
                                        if (moved && !dragging) {
                                            // Empieza el arrastre: fija el nodo y
                                            // reaviva las físicas (los vecinos lo
                                            // seguirán por tensión).
                                            dragging = true
                                            sim.pin(grabbed)
                                            kick++
                                        }
                                        if (dragging) {
                                            val w = screenToWorld(position)
                                            sim.setPosition(grabbed, w.x, w.y)
                                            pressed[0].consume()
                                        }
                                    } else if (moved) {
                                        // Pan de un dedo sobre el vacío.
                                        val delta = position - lastSingle
                                        viewport.pan(delta.x, delta.y)
                                    }
                                    lastSingle = position
                                }
                            }

                            when {
                                grabbed != null && dragging -> {
                                    // Se suelta el nodo; las físicas lo reacomodan.
                                    sim.unpin()
                                }
                                // Toque limpio sobre un nodo: doble tap abre
                                // (RF-22), si no selecciona/deselecciona.
                                !moved && grabbed != null -> {
                                    val now = System.currentTimeMillis()
                                    if (grabbed == lastTapUuid &&
                                        now - lastTapAtMillis < 300
                                    ) {
                                        lastTapUuid = null
                                        lastTapAtMillis = 0
                                        notes.find { it.uuid == grabbed }?.let(onOpenNote)
                                    } else {
                                        selectedNodeUuid =
                                            if (grabbed == selectedNodeUuid) null else grabbed
                                        lastTapUuid = grabbed
                                        lastTapAtMillis = now
                                    }
                                }
                                // Toque en el vacío: limpia la selección.
                                !moved -> selectedNodeUuid = null
                            }
                        }
                    },
            ) {
                frame // lee el estado del frame para redibujar en cada paso
                withTransform({
                    translate(viewport.offsetX, viewport.offsetY)
                    scale(viewport.scale, viewport.scale, pivot = Offset.Zero)
                }) {
                    val selected = selectedNodeUuid
                    // Vecinos del seleccionado en AMBAS direcciones (RF-22): a
                    // quién apunta y quién lo apunta.
                    val neighbors = selected?.let { sel ->
                        buildSet {
                            add(sel)
                            graph[sel]?.let { addAll(it) }
                            for ((a, outs) in graph) if (sel in outs) add(a)
                        }
                    }

                    // Aristas dirigidas a→b (sin dedup: la dirección importa; un
                    // par mutuo dibuja dos líneas con puntas opuestas).
                    for ((a, connected) in graph) {
                        for (b in connected) {
                            val highlighted =
                                neighbors == null || a == selected || b == selected
                            val edgeColor = EDGE_COLOR.copy(
                                alpha = if (highlighted) 1f else DIMMED_ALPHA,
                            )
                            val start = pos(a)
                            val end = pos(b)
                            drawLine(
                                color = edgeColor,
                                start = start,
                                end = end,
                                strokeWidth = settings.linkThickness,
                            )
                            if (settings.arrows) {
                                val d = end - start
                                val len = d.getDistance()
                                if (len > 0f) {
                                    val ux = d.x / len
                                    val uy = d.y / len
                                    // La punta se posa en el borde del nodo destino.
                                    val rb = nodeRadius(degrees[b] ?: 0) * settings.nodeSize
                                    val tip = Offset(end.x - ux * rb, end.y - uy * rb)
                                    val ah = 10f + settings.linkThickness * 2f
                                    for (sign in intArrayOf(1, -1)) {
                                        val angle = 0.5 * sign
                                        val ca = cos(angle).toFloat()
                                        val sa = sin(angle).toFloat()
                                        val bx = -ux
                                        val by = -uy
                                        drawLine(
                                            color = edgeColor,
                                            start = tip,
                                            end = Offset(
                                                tip.x + (bx * ca - by * sa) * ah,
                                                tip.y + (bx * sa + by * ca) * ah,
                                            ),
                                            strokeWidth = settings.linkThickness,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Nodos + títulos.
                    drawIntoCanvas { canvas ->
                        val labelPaint = android.graphics.Paint().apply {
                            textSize = 26f
                            isAntiAlias = true
                            textAlign = android.graphics.Paint.Align.CENTER
                        }
                        for (note in notes) {
                            val degree = degrees[note.uuid] ?: 0
                            val highlighted =
                                neighbors == null || note.uuid in neighbors
                            val alpha = if (highlighted) 1f else DIMMED_ALPHA
                            val center = pos(note.uuid)
                            val radius = nodeRadius(degree) * settings.nodeSize
                            // RF-20: huérfanos en color atenuado diferenciado.
                            val color =
                                if (degree == 0) ORPHAN_NODE_COLOR else CONNECTED_NODE_COLOR
                            drawCircle(
                                color = color.copy(alpha = alpha),
                                radius = radius,
                                center = center,
                            )
                            if (note.uuid == selected) {
                                drawCircle(
                                    color = colors.textPrimary,
                                    radius = radius + 5f,
                                    center = center,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                                        width = 3f,
                                    ),
                                )
                            }
                            // Text fade (RF: Display): rampa suave de alpha en
                            // vez de mostrar/ocultar de golpe al cruzar el
                            // umbral — un corte binario no se "aclara", salta.
                            // Medido relativo a homeScale (la vista de
                            // apertura), no en escala absoluta: así el umbral
                            // significa lo mismo sin importar cuán grande sea
                            // el vault ni cuánto haya encuadrado el auto-fit.
                            val relativeZoom = viewport.scale / homeScale.coerceAtLeast(0.001f)
                            val fadeT = if (TEXT_FADE_WIDTH <= 0f) {
                                if (relativeZoom >= settings.textFadeThreshold) 1f else 0f
                            } else {
                                (
                                    (relativeZoom - settings.textFadeThreshold) /
                                        TEXT_FADE_WIDTH
                                ).coerceIn(0f, 1f)
                            }
                            val labelAlpha = alpha * fadeT
                            if (labelAlpha > 0.01f) {
                                // Color de etiqueta del tema (legible también
                                // en tema oscuro), con el alpha del fade.
                                labelPaint.color =
                                    ((labelAlpha * 255).toInt() shl 24) or
                                        (colors.textPrimary.toArgb() and 0x00FFFFFF)
                                canvas.nativeCanvas.drawText(
                                    note.title.take(18),
                                    center.x,
                                    center.y + radius + 30f,
                                    labelPaint,
                                )
                            }
                        }
                    }
                }
            }

            // Heurística 10: los gestos del grafo no son descubribles; texto
            // guía discreto solo hasta que el usuario lo descarta.
            FirstUseHint(
                hintKey = "graph_gestures",
                text = "Toca un nodo para resaltarlo · doble toque abre la nota · " +
                    "arrastra un nodo para reacomodarlo · " +
                    "un dedo mueve la vista, dos hacen zoom",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp),
            )

            if (showPanel) {
                GraphPanel(
                    settings = settings,
                    onChange = ::updateSettings,
                    onCommit = ::commitSettings,
                    // sim.kick() reacomoda de verdad aunque el grafo ya esté
                    // asentado (reheat() solo no movería nada en equilibrio);
                    // kick++ reactiva el loop de físicas para reproducirlo.
                    onAnimate = { sim.kick(); kick++ },
                    onClose = { showPanel = false },
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
    }
}

private val PANEL_ACCENT = Color(0xFF3949AB)

/**
 * Panel de ajustes del grafo (estilo Obsidian): secciones Visualización y
 * Fuerzas con las perillas de [GraphSettings]. Los cambios se aplican en vivo
 * vía [onChange] y se persisten al soltar cada control vía [onCommit].
 */
@Composable
private fun GraphPanel(
    settings: GraphSettings,
    onChange: (GraphSettings) -> Unit,
    onCommit: () -> Unit,
    onAnimate: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Theme.colors
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(280.dp)
            .background(colors.surfaceVariant)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            BasicText(
                text = "Ajustes",
                style = TextStyle(
                    color = colors.textPrimary,
                    fontSize = AppType.label,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Spacer(modifier = Modifier.weight(1f))
            BasicText(
                text = "✕",
                style = TextStyle(color = colors.textPrimary, fontSize = AppType.label),
                modifier = Modifier.clickable(onClick = onClose).padding(4.dp),
            )
        }

        PanelSection("Visualización")
        GraphSlider("Tamaño de nodo", settings.nodeSize, 0.5f..2.5f,
            { onChange(settings.copy(nodeSize = it)) }, onCommit)
        GraphSlider("Grosor de vínculo", settings.linkThickness, 1f..8f,
            { onChange(settings.copy(linkThickness = it)) }, onCommit)
        GraphSlider("Zoom para mostrar títulos", settings.textFadeThreshold, 0f..2f,
            { onChange(settings.copy(textFadeThreshold = it)) }, onCommit)
        GraphToggle("Flechas", settings.arrows) {
            onChange(settings.copy(arrows = it)); onCommit()
        }
        PanelButton("Reacomodar", onAnimate)

        PanelSection("Fuerzas")
        GraphSlider("Atracción al centro", settings.centerGravity, 0f..0.5f,
            { onChange(settings.copy(centerGravity = it)) }, onCommit)
        GraphSlider("Repulsión entre nodos", settings.repulsionStrength, 0.2f..3f,
            { onChange(settings.copy(repulsionStrength = it)) }, onCommit)
        GraphSlider("Tensión de vínculos", settings.linkStrength, 0.2f..3f,
            { onChange(settings.copy(linkStrength = it)) }, onCommit)
        GraphSlider("Distancia de vínculos", settings.idealDistance, 60f..400f,
            { onChange(settings.copy(idealDistance = it)) }, onCommit)
    }
}

@Composable
private fun PanelSection(title: String) {
    BasicText(
        text = title,
        style = TextStyle(
            color = Theme.colors.textSecondary,
            fontSize = AppType.caption,
            fontWeight = FontWeight.Medium,
        ),
        modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
    )
}

@Composable
private fun PanelButton(label: String, onClick: () -> Unit) {
    BasicText(
        text = label,
        style = TextStyle(color = Color.White, fontSize = AppType.body),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(PANEL_ACCENT, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    )
}

@Composable
private fun GraphToggle(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    val colors = Theme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        BasicText(label, style = TextStyle(color = colors.textPrimary, fontSize = AppType.body))
        Spacer(modifier = Modifier.weight(1f))
        BasicText(
            text = if (checked) "Sí" else "No",
            style = TextStyle(color = Color.White, fontSize = AppType.caption),
            modifier = Modifier
                .background(
                    if (checked) PANEL_ACCENT else colors.disabled,
                    RoundedCornerShape(10.dp),
                )
                .clickable { onToggle(!checked) }
                .padding(horizontal = 14.dp, vertical = 5.dp),
        )
    }
}

/**
 * Slider sobre `foundation` (sin material3): pista + thumb arrastrable que mapea
 * la posición táctil al rango. [onChange] va en vivo; [onCommit] al soltar.
 */
@Composable
internal fun GraphSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit,
) {
    val colors = Theme.colors
    val span = range.endInclusive - range.start
    val frac = ((value - range.start) / span).coerceIn(0f, 1f)
    // El pointerInput de abajo no se relanza entre arrastres (su key, `range`,
    // no cambia): sin esto seguiría llamando para siempre a los `onChange`/
    // `onCommit` de la PRIMERA composición, que cierran sobre el `settings` de
    // ese momento — cada slider pisaría los cambios que hicieron los demás.
    val currentOnChange = rememberUpdatedState(onChange)
    val currentOnCommit = rememberUpdatedState(onCommit)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            BasicText(label, style = TextStyle(color = colors.textPrimary, fontSize = AppType.caption))
            Spacer(modifier = Modifier.weight(1f))
            BasicText(
                text = "%.2f".format(value),
                style = TextStyle(color = colors.textSecondary, fontSize = AppType.caption),
            )
        }
        var widthPx by remember { mutableStateOf(1f) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(range) {
                    fun emit(x: Float) =
                        currentOnChange.value(range.start + (x / widthPx).coerceIn(0f, 1f) * span)
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        emit(down.position.x)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.pressed } ?: break
                            emit(change.position.x)
                            change.consume()
                        }
                        currentOnCommit.value()
                    }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cy = size.height / 2f
                drawLine(
                    color = colors.outline,
                    start = Offset(0f, cy),
                    end = Offset(size.width, cy),
                    strokeWidth = 5f,
                )
                drawLine(
                    color = PANEL_ACCENT,
                    start = Offset(0f, cy),
                    end = Offset(size.width * frac, cy),
                    strokeWidth = 5f,
                )
                drawCircle(
                    color = PANEL_ACCENT,
                    radius = 11f,
                    center = Offset(size.width * frac, cy),
                )
            }
        }
    }
}
