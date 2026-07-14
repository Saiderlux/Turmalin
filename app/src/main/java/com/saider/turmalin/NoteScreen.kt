package com.saider.turmalin

import android.content.Intent
import android.graphics.pdf.PdfDocument
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.ink.strokes.Stroke
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Resultado de generar el PDF en memoria (RF-28): distingue causa para poder
 *  sugerir remedio (heurística 9). */
private sealed interface PdfBuild {
    data object Empty : PdfBuild
    data object Failed : PdfBuild
    data class Ready(val document: PdfDocument) : PdfBuild
}

/**
 * Pantalla de una nota abierta: barra superior (volver + título editable) y el
 * canvas de ink debajo.
 *
 * RF-07/RF-08: la nota abre directo al canvas con título "Sin título" editable
 * en cualquier momento — el teclado solo aparece si el usuario toca el título,
 * nunca antes de poder escribir con el pen.
 *
 * Guardado: al volver a la galería (botón/gesto atrás) y en ON_STOP (app a
 * segundo plano), sobre el vault vía [NoteRepository].
 */
@Composable
fun NoteScreen(
    meta: NoteMeta,
    repo: NoteRepository,
    wetHighLatency: Boolean,
    eraserRouter: StylusEraserRouter?,
    // Ajustes globales (v2 3.4): gestos rápidos y atajo del botón del S Pen.
    appSettings: AppSettings,
    focusTitleOnOpen: Boolean,
    // Al cerrar entrega el meta guardado y si la tinta cambió en la sesión
    // (dirty flag): decide si se dispara el OCR (RF-24). Editar solo el
    // título o navegar páginas no marca la tinta como sucia.
    onClose: (NoteMeta, inkChanged: Boolean) -> Unit,
    // Seguir un link (región linkeada o backlink): cierra esta nota igual que
    // onClose y navega a la nota destino por su uuid.
    onFollowLink: (NoteMeta, inkChanged: Boolean, targetUuid: String) -> Unit,
    // RF-36: eliminar la nota (mueve a la papelera, reversible) — entrega el
    // meta guardado, igual que onClose, para no perder la sesión en curso.
    onDeleteNote: (NoteMeta) -> Unit,
) {
    val colors = Theme.colors
    val brush = remember { defaultBlackPen() }
    // Lápices pineados (v2 1.4): compartidos entre notas vía settings.json.
    val pins = remember { mutableStateListOf<PenPin>().apply { addAll(repo.loadPins()) } }
    // RF-09a: se restaura la última página abierta (coerción por si el meta
    // viene de una versión sin páginas o quedó inconsistente).
    val initialPage = remember { meta.lastPageIndex.coerceIn(0, meta.pageCount - 1) }
    var pageCount by remember { mutableStateOf(meta.pageCount) }
    var currentPage by remember { mutableStateOf(initialPage) }
    val initialLoaded = remember { repo.loadStrokes(meta.uuid, initialPage, brush) }
    val strokes = remember { mutableStateListOf<IdStroke>().apply { addAll(initialLoaded) } }
    // Historial deshacer/rehacer de ink (RF-37): solo en memoria, nace vacío al
    // abrir la nota y se reinicia al cambiar de página.
    // ponytail: historial por página visitada — deshacer a través de páginas
    // requeriría recargar y navegar páginas ya persistidas; añadir si el uso
    // real lo pide.
    val inkHistory = remember { UndoHistory<List<IdStroke>>() }
    // Versión más completa conocida de cada trazo en esta sesión (por ID), para
    // restaurar un link al deshacer su borrado. Se llena al abrir la página y al
    // crear un link (nunca se sobrescribe con piezas recortadas), así "Deshacer"
    // devuelve la palabra entera, no el último fragmento.
    val fullStrokeById = remember {
        mutableStateMapOf<Long, IdStroke>().apply { putAll(initialLoaded.associateBy { it.id }) }
    }
    // El placeholder "Sin título" nunca entra al campo: para notas sin titular
    // el campo inicia vacío y el placeholder es solo el hint de la decoración.
    // Mutarlo al enfocar pierde contra el snapshot con que arranca la sesión del
    // IME (verificado en dispositivo: el primer commit del teclado restaura el
    // texto previo y lo escrito se anexa).
    var titleField by remember {
        mutableStateOf(
            TextFieldValue(if (meta.title == DEFAULT_NOTE_TITLE) "" else meta.title)
        )
    }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // Dirty flag de tinta: true solo si hubo trazo nuevo o borrado real.
    var inkChanged by remember { mutableStateOf(false) }
    // RF-35: true solo si un link se creó o eliminó de verdad en la sesión —
    // junto con inkChanged y el título/tags, decide si modifiedAtMillis avanza.
    var linksChanged by remember { mutableStateOf(false) }

    // Links de región de esta nota (RF-17) y sus backlinks (RF-23b): notas que
    // apuntan a esta (referencias entrantes, consulta invertida de graph.json).
    // Crear un link NO enciende el dirty flag: el ink no cambia.
    val links = remember {
        mutableStateListOf<LinkRegion>().apply { addAll(repo.loadRegionLinks(meta.uuid)) }
    }
    var connections by remember { mutableStateOf(repo.loadBacklinks(meta.uuid)) }
    // Tags manuales de la nota (RF-16); editar tags no ensucia la tinta.
    var tags by remember { mutableStateOf(meta.tags) }
    var showTags by remember { mutableStateOf(false) }
    // Menú de overflow de la barra superior: acciones no esenciales (ajustes,
    // tags, PDF, eliminar) fuera del primer nivel (heurística 8).
    var showMenu by remember { mutableStateOf(false) }
    // Fondo de página (RF-06): editable desde el menú de ajustes de la nota, no
    // desde la barra de dibujo. El cambio se refleja en vivo en el canvas.
    var paper by remember { mutableStateOf(meta.paper) }
    // Tamaño de página POR PÁGINA (RF-06a): el menú de ajustes edita solo la
    // página actual. Lista alineada a pageCount desde el arranque.
    val pageSizes = remember {
        mutableStateListOf<PageSize>().apply {
            repeat(meta.pageCount) { add(meta.pageSizeOf(it)) }
        }
    }
    var showSettings by remember { mutableStateOf(false) }
    // Diálogo de nombre al guardar la plantilla (v2 2.3).
    var showSaveTemplate by remember { mutableStateOf(false) }
    // Aviso informativo no bloqueante (RF-34): p. ej. selección ya linkeada.
    var infoNotice by remember { mutableStateOf<String?>(null) }
    // Progreso de exportación visible (heurística 1): "Generando PDF…",
    // "Guardando PDF…"; null = sin exportación en curso.
    var exportStatus by remember { mutableStateOf<String?>(null) }
    // Vínculo bajo long-press, pendiente de confirmación de borrado (paso 9).
    var confirmDeleteLink by remember { mutableStateOf<LinkRegion?>(null) }
    // Alcance para la exportación a PDF en background (UC-10).
    val exportScope = rememberCoroutineScope()
    val context = LocalContext.current
    // PDF ya generado en memoria, a la espera de que el usuario elija dónde
    // guardarlo con el selector de documentos del sistema (RF-28).
    var pendingPdfDocument by remember { mutableStateOf<PdfDocument?>(null) }
    // SAF (RF-28): el usuario elige nombre y ubicación con ACTION_CREATE_DOCUMENT
    // en vez de guardar silenciosamente en Descargas.
    val createPdfDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        val document = pendingPdfDocument
        pendingPdfDocument = null
        if (document == null) return@rememberLauncherForActivityResult
        if (uri == null) {
            // El usuario canceló el selector: no es un error, silencio.
            document.close()
            exportStatus = null
            return@rememberLauncherForActivityResult
        }
        exportScope.launch {
            exportStatus = "Guardando PDF…"
            val ok = withContext(Dispatchers.IO) {
                val written = repo.writePdf(document, uri)
                document.close()
                written
            }
            exportStatus = null
            infoNotice = if (ok) {
                "PDF guardado"
            } else {
                // Causa: destino no escribible (URI inválida, sin espacio,
                // carpeta removida) — con remedio (heurística 9).
                "No se pudo escribir el PDF en ese destino — elige otra carpeta o revisa el espacio libre"
            }
            // Tras exportar con éxito, ofrece compartirlo directo (RF-28).
            if (ok) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Compartir PDF"))
            }
        }
    }
    // Trazos seleccionados por el lazo, pendientes de destino (UC-05): no nulo =
    // sheet de búsqueda abierto.
    var pendingSelection by remember { mutableStateOf<List<IdStroke>?>(null) }
    var showBacklinks by remember { mutableStateOf(false) }
    // Link recién borrado (por la goma RF-05a/b, o deliberadamente con
    // long-press), ofrecido para deshacer con el mismo aviso estándar.
    var linkUndo by remember { mutableStateOf<DeletedLink?>(null) }

    // Tarjetas de repaso de la nota (v2 4.3), en annotations.json junto a los
    // links. Crear/repasar tarjetas no ensucia la tinta.
    val cards = remember {
        mutableStateListOf<ReviewCard>().apply { addAll(repo.loadCards(meta.uuid)) }
    }
    // Selección entregada por «Tarjeta», pendiente de elegir tipo (diálogo).
    var cardSelection by remember { mutableStateOf<List<IdStroke>?>(null) }
    // Frente ya elegido, esperando el lazo del reverso.
    var pendingCardFront by remember { mutableStateOf<List<IdStroke>?>(null) }
    // Tarjeta invalidada por la goma, ofrecida para deshacer (aviso RF-34).
    var cardUndo by remember { mutableStateOf<DeletedCard?>(null) }

    // Alta de una tarjeta (v2 4.3): vence de inmediato (cola de hoy).
    fun addCard(front: List<IdStroke>, back: List<IdStroke>) {
        cards.add(
            ReviewCard(
                id = newStrokeId(),
                page = currentPage,
                frontStrokeIds = front.map { it.id },
                frontBbox = strokesBoundingBox(front),
                backStrokeIds = back.map { it.id },
                backBbox = if (back.isEmpty()) emptyList() else strokesBoundingBox(back),
                dueAtMillis = System.currentTimeMillis(),
            )
        )
        repo.saveCards(meta.uuid, cards)
        infoNotice = "Tarjeta creada — aparecerá en el repaso de la galería"
    }

    // Overlays de los links de la página actual (RF-23a): halo ceñido re-tiñendo
    // los trazos linkeados vivos. Si un link no resuelve trazos (todos borrados),
    // no se dibuja — la goma ya debería haberlo eliminado (RF-05b).
    val linkOverlays = links.filter { it.page == currentPage }.mapNotNull { link ->
        val targets = strokes.filter { it.id in link.strokeIds }
        if (targets.isEmpty()) return@mapNotNull null
        LinkOverlay(
            targetUuid = link.targetUuid,
            tintStrokes = targets.map { linkTintStroke(it.stroke) },
        )
    }

    // RF-05a/b: la goma borró trazos (IDs desaparecidos). El link no encoge su
    // conjunto de IDs (se conserva el del momento de creación para poder
    // restaurarlo entero); muere solo cuando NINGUNO de sus trazos sigue vivo, y
    // solo si este borrado lo tocó. Al morir se elimina de annotations.json y
    // graph.json y se ofrece deshacer, restaurando los trazos completos.
    fun onStrokesErased(removed: List<IdStroke>) {
        if (removed.isEmpty()) return
        val removedIds = removed.mapTo(HashSet()) { it.id }
        for (link in links.toList()) {
            if (link.strokeIds.none { it in removedIds }) continue
            if (strokes.any { it.id in link.strokeIds }) continue // sigue vivo
            links.remove(link)
            linksChanged = true
            repo.saveRegionLinks(meta.uuid, links)
            repo.removeGraphLinkIfOrphan(meta.uuid, link.targetUuid)
            connections = repo.loadBacklinks(meta.uuid)
            val restore = link.strokeIds.mapNotNull { fullStrokeById[it] }.distinct()
            linkUndo = DeletedLink(link, restore)
        }
        // v2 4.3: misma regla que los links — la tarjeta muere solo cuando
        // NINGÚN trazo de su frente sigue vivo, con deshacer que restaura los
        // trazos completos.
        for (card in cards.toList()) {
            if (card.page != currentPage) continue
            if (card.frontStrokeIds.none { it in removedIds }) continue
            if (strokes.any { it.id in card.frontStrokeIds }) continue
            cards.remove(card)
            repo.saveCards(meta.uuid, cards)
            val restore = card.frontStrokeIds.mapNotNull { fullStrokeById[it] }.distinct()
            cardUndo = DeletedCard(card, restore)
        }
    }

    // Borrado deliberado de un vínculo (paso 9 de la fase 2): quita el link de
    // annotations.json y graph.json SIN tocar la tinta (RF-23), y ofrece
    // deshacer con el mismo aviso estándar de RF-05b.
    fun deleteLink(link: LinkRegion) {
        links.remove(link)
        linksChanged = true
        repo.saveRegionLinks(meta.uuid, links)
        repo.removeGraphLinkIfOrphan(meta.uuid, link.targetUuid)
        connections = repo.loadBacklinks(meta.uuid)
        // Sin trazos que restaurar: la tinta quedó intacta.
        linkUndo = DeletedLink(link, emptyList())
    }

    fun snapshotMeta(): NoteMeta {
        val newTitle = titleField.text.trim().ifBlank { DEFAULT_NOTE_TITLE }
        val changed = noteContentChanged(meta, newTitle, tags, inkChanged, linksChanged)
        return meta.copy(
            title = newTitle,
            modifiedAtMillis = if (changed) System.currentTimeMillis() else meta.modifiedAtMillis,
            pageCount = pageCount,
            lastPageIndex = currentPage,
            tags = tags,
            paper = paper,
            pageSizes = pageSizes.toList(),
        )
    }

    // Recarga la página destino en el lienzo y re-snapshotea la versión completa
    // de sus trazos (para deshacer). Los links son de la nota, no de la página.
    fun loadPageInto(target: Int) {
        val loaded = repo.loadStrokes(meta.uuid, target, brush)
        strokes.clear()
        strokes.addAll(loaded)
        fullStrokeById.clear()
        fullStrokeById.putAll(loaded.associateBy { it.id })
        inkHistory.clear()
    }

    // Recalcula las bbox cacheadas de links y tarjetas desde sus trazos vivos
    // (tras recortes de goma parcial o el lasso de edición), conservando sus
    // conjuntos de IDs de creación. Trazos de otra página no están cargados:
    // sus cachés se conservan tal cual.
    fun persistAnnotations() {
        repo.saveRegionLinks(
            meta.uuid,
            links.map { link ->
                val live = strokes.filter { it.id in link.strokeIds }
                if (live.isEmpty()) link else link.copy(bbox = strokesBoundingBox(live))
            },
        )
        repo.saveCards(
            meta.uuid,
            cards.map { card ->
                val front = strokes.filter { it.id in card.frontStrokeIds }
                val back = strokes.filter { it.id in card.backStrokeIds }
                var updated = card
                if (front.isNotEmpty()) {
                    updated = updated.copy(frontBbox = strokesBoundingBox(front))
                }
                if (back.isNotEmpty()) {
                    updated = updated.copy(backBbox = strokesBoundingBox(back))
                }
                updated
            },
        )
    }

    fun save(): NoteMeta {
        val updated = snapshotMeta()
        repo.saveStrokes(meta.uuid, currentPage, strokes)
        persistAnnotations()
        repo.saveMeta(updated)
        return updated
    }

    // RF-09a: cambiar de página guarda la actual antes de cargar la destino.
    fun switchToPage(target: Int) {
        if (target == currentPage || target !in 0 until pageCount) return
        repo.saveStrokes(meta.uuid, currentPage, strokes)
        persistAnnotations()
        // Una captura de tarjeta a medias no cruza páginas (v2 4.3).
        pendingCardFront = null
        currentPage = target
        loadPageInto(target)
        repo.saveMeta(snapshotMeta())
    }

    // RF-09: añadir página con botón explícito; la nueva queda al final y se
    // navega a ella de inmediato.
    fun addPage() {
        repo.saveStrokes(meta.uuid, currentPage, strokes)
        persistAnnotations()
        pageCount += 1
        // RF-06a: página nueva nace Carta retrato, no hereda de la actual.
        pageSizes.add(DEFAULT_PAGE_SIZE)
        currentPage = pageCount - 1
        strokes.clear()
        fullStrokeById.clear()
        inkHistory.clear()
        repo.saveMeta(snapshotMeta())
    }

    // UC-10/RF-28: genera el PDF vectorial (ink + overlays de link) y abre el
    // selector de documentos del sistema. Guarda primero para volcar la página
    // abierta. Errores con causa y remedio (heurística 9); progreso visible.
    fun exportPdf() {
        val exported = save()
        exportScope.launch {
            exportStatus = "Generando PDF…"
            val result = withContext(Dispatchers.IO) {
                val isEmpty = (0 until exported.pageCount).all { page ->
                    repo.loadStrokes(exported.uuid, page, brush).isEmpty()
                }
                when {
                    isEmpty -> PdfBuild.Empty
                    else -> repo.buildPdf(exported)
                        ?.let { PdfBuild.Ready(it) }
                        ?: PdfBuild.Failed
                }
            }
            when (result) {
                is PdfBuild.Empty -> {
                    exportStatus = null
                    infoNotice = "La nota está vacía — escribe algo antes de exportar"
                }
                is PdfBuild.Failed -> {
                    exportStatus = null
                    infoNotice = "No se pudo generar el PDF — vuelve a intentarlo"
                }
                is PdfBuild.Ready -> {
                    pendingPdfDocument = result.document
                    createPdfDocumentLauncher.launch(
                        suggestedPdfFileName(exported.title, System.currentTimeMillis())
                    )
                }
            }
        }
    }

    // Volver: primero cierra el sheet abierto; sin sheets, guarda y sale.
    BackHandler {
        when {
            pendingSelection != null -> pendingSelection = null
            showBacklinks -> showBacklinks = false
            else -> onClose(save(), inkChanged)
        }
    }

    // App a segundo plano: guardar sin cerrar (los datos nunca dependen de un
    // cierre limpio).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) save()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (focusTitleOnOpen) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
      Column(modifier = Modifier.fillMaxSize()) {
        // Barra superior compacta (v2 3.2): cede altura al canvas — flecha y
        // paddings reducidos, contador de vínculos como pill discreto.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackArrow(onClick = { onClose(save(), inkChanged) }, compact = true)
            BasicTextField(
                value = titleField,
                onValueChange = { titleField = it },
                textStyle = TextStyle(
                    color = colors.textPrimary,
                    fontSize = AppType.label,
                    fontWeight = FontWeight.Medium,
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                decorationBox = { innerTextField ->
                    Box {
                        if (titleField.text.isEmpty()) {
                            BasicText(
                                text = DEFAULT_NOTE_TITLE,
                                style = TextStyle(
                                    color = colors.textHint,
                                    fontSize = AppType.label,
                                    fontWeight = FontWeight.Medium,
                                ),
                            )
                        }
                        innerTextField()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
            // RF-23b: contador de referencias de la nota, con label en vez del
            // símbolo ⇄ (heurística 2); se abre por toque (no swipe) para no
            // comprometer el palm rejection. Pill discreto (v2 3.2): informa
            // más de lo que compite con el canvas.
            BasicText(
                text = "Vínculos: ${connections.size}",
                style = TextStyle(color = colors.textSecondary, fontSize = AppType.body),
                modifier = Modifier
                    .padding(end = 8.dp)
                    .background(colors.surfaceVariant, RoundedCornerShape(14.dp))
                    .border(1.dp, colors.outlineVariant, RoundedCornerShape(14.dp))
                    .clickable { showBacklinks = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            // Overflow (heurística 8): acciones no esenciales fuera del primer
            // nivel — ajustes de página, tags, exportar, eliminar.
            NoteOverflowMenu(
                expanded = showMenu,
                tagCount = tags.size,
                onExpand = { showMenu = true },
                onDismiss = { showMenu = false },
                onSettings = { showMenu = false; showSettings = true },
                onTags = { showMenu = false; showTags = true },
                onExportPdf = { showMenu = false; exportPdf() },
                onDelete = { showMenu = false; onDeleteNote(save()) },
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            InkCanvasScreen(
                strokes = strokes,
                history = inkHistory,
                wetHighLatency = wetHighLatency,
                eraserRouter = eraserRouter,
                appSettings = appSettings,
                pins = pins,
                onPinsChange = { changed ->
                    pins.clear()
                    pins.addAll(changed)
                    repo.savePins(changed)
                },
                background = paper,
                pageSize = pageSizes.getOrElse(currentPage) { DEFAULT_PAGE_SIZE },
                onSwipePage = { direction -> switchToPage(currentPage + direction) },
                onInkModified = { inkChanged = true },
                linkOverlays = linkOverlays,
                // RF-17: al cerrar el lazo, seleccionamos por cobertura de área
                // los trazos dentro. Si algún trazo ya pertenece a un link, se
                // bloquea: un trazo apunta a lo sumo a un destino (aviso RF-34).
                // Lazo vacío: feedback en vez de silencio (heurística 9).
                onLassoComplete = { polygon ->
                    val selected = strokesInLasso(strokes, polygon)
                    val alreadyLinked = selected.any { s ->
                        links.any { it.page == currentPage && s.id in it.strokeIds }
                    }
                    when {
                        selected.isEmpty() ->
                            infoNotice = "El lazo quedó vacío — rodea por completo los trazos a vincular"
                        alreadyLinked -> infoNotice = "Esta selección ya tiene un link"
                        else -> pendingSelection = selected
                    }
                },
                // Avisos de la herramienta Selección (v2 sección 5), con el
                // mismo componente estándar RF-34.
                onSelectionNotice = { infoNotice = it },
                // Tarjetas de repaso (v2 4.3): la acción «Tarjeta» de la
                // selección abre el diálogo de tipo; con un frente pendiente,
                // el siguiente lazo captura el reverso.
                onCreateCard = { selected -> cardSelection = selected },
                cardBackCapture = pendingCardFront != null,
                onCardBackSelected = { back ->
                    val front = pendingCardFront
                    pendingCardFront = null
                    if (front != null) addCard(front, back)
                },
                onLinkTap = { targetUuid ->
                    onFollowLink(save(), inkChanged, targetUuid)
                },
                // Long-press sobre el halo: opción de eliminar el vínculo.
                // ponytail: dos links de la misma página al mismo destino se
                // resuelven al primero; distinguir por trazos si alguna vez pasa.
                onLinkLongPress = { targetUuid ->
                    confirmDeleteLink = links.firstOrNull {
                        it.page == currentPage && it.targetUuid == targetUuid
                    }
                },
                onStrokesErased = { removed -> onStrokesErased(removed) },
            )
            // Heurística 10: gestos del canvas no descubribles, solo primer uso.
            FirstUseHint(
                hintKey = "canvas_gestures",
                text = "El S Pen escribe · un dedo mueve la página, dos hacen zoom · " +
                    "el botón del S Pen borra · «Lazo de vínculo» conecta con otra nota · " +
                    "«Selección» mueve trazos · mantén presionado un vínculo para eliminarlo",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp),
            )
            PageControls(
                currentPage = currentPage,
                pageCount = pageCount,
                onPrev = { switchToPage(currentPage - 1) },
                onNext = { switchToPage(currentPage + 1) },
                onAdd = { addPage() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp),
            )
            // Progreso de exportación (heurística 1): pasivo, no bloquea la
            // escritura mientras el PDF se genera en background.
            exportStatus?.let { status ->
                StatusChip(
                    text = status,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                )
            }
        }
      }

      // UC-05 paso 3: trazos seleccionados → búsqueda de la nota destino.
      pendingSelection?.let { selected ->
        LinkTargetSheet(
            repo = repo,
            originUuid = meta.uuid,
            onSelect = { target ->
                val region = LinkRegion(
                    targetUuid = target.uuid,
                    page = currentPage,
                    strokeIds = selected.map { it.id },
                    bbox = strokesBoundingBox(selected),
                )
                repo.addRegionLink(meta.uuid, region)
                links.add(region)
                linksChanged = true
                // Snapshot de los trazos linkeados en su estado de creación, para
                // restaurarlos completos si luego se borran (Deshacer).
                selected.forEach { fullStrokeById[it.id] = it }
                connections = repo.loadBacklinks(meta.uuid)
                pendingSelection = null
            },
            onDismiss = { pendingSelection = null },
        )
      }

      // RF-16: editor de tags por teclado; guardar persiste de inmediato para
      // que la búsqueda de la galería los indexe al volver.
      if (showTags) {
        TagsDialog(
            initial = tags,
            onConfirm = { updated ->
                tags = updated
                repo.saveMeta(snapshotMeta())
                showTags = false
            },
            onCancel = { showTags = false },
        )
      }

      // RF-06: ajustes de la nota (fondo de página). El cambio se aplica en vivo
      // al canvas y se persiste al cerrar el diálogo.
      if (showSettings) {
        NoteSettingsDialog(
            paper = paper,
            onPaperChange = { paper = it },
            // RF-06a: el tamaño/orientación se edita para la PÁGINA ACTUAL.
            pageSize = pageSizes.getOrElse(currentPage) { DEFAULT_PAGE_SIZE },
            onPageSizeChange = { pageSizes[currentPage] = it },
            pageLabel = "página ${currentPage + 1}/$pageCount",
            onSaveTemplate = { showSaveTemplate = true },
            onDismiss = {
                repo.saveMeta(snapshotMeta())
                showSettings = false
            },
        )
      }

      // v2 2.3: guarda la combinación vigente (fondo + tamaño de la página
      // actual) como plantilla con nombre, reutilizable desde el FAB.
      if (showSaveTemplate) {
        TextInputDialog(
            title = "Guardar plantilla",
            confirmLabel = "Guardar",
            onConfirm = { name ->
                repo.createTemplate(
                    name = name,
                    paper = paper,
                    pageSize = pageSizes.getOrElse(currentPage) { DEFAULT_PAGE_SIZE },
                )
                showSaveTemplate = false
            },
            onDismiss = { showSaveTemplate = false },
        )
      }

      // RF-23b: panel de referencias por toque en el badge.
      if (showBacklinks) {
        BacklinksSheet(
            repo = repo,
            connections = connections,
            onOpen = { target -> onFollowLink(save(), inkChanged, target.uuid) },
            onDismiss = { showBacklinks = false },
        )
      }

      // Confirmación de borrado deliberado de vínculo (paso 9): el long-press
      // podría ser accidental y el destino no es visible en el halo.
      confirmDeleteLink?.let { link ->
        val targetTitle = remember(link.targetUuid) {
            repo.listNotes().find { it.uuid == link.targetUuid }?.title ?: "otra nota"
        }
        Dialog(onDismissRequest = { confirmDeleteLink = null }) {
            DialogSurface {
                DialogTitle("Vínculo hacia «$targetTitle»")
                DialogOption(
                    label = "Eliminar vínculo (la tinta no se toca)",
                    onClick = {
                        deleteLink(link)
                        confirmDeleteLink = null
                    },
                    danger = true,
                )
                DialogOption(label = "Cancelar", onClick = { confirmDeleteLink = null })
            }
        }
      }

      // RF-05b + RF-34: aviso no bloqueante para deshacer el borrado del link
      // (por goma o deliberado). "Deshacer" re-crea el link; si el borrado fue
      // por goma, restaura además los trazos completos (snapshot).
      // v2 4.3: tipo de tarjeta — solo frente (se muestra y se califica) o
      // frente + reverso (el reverso se rodea con un segundo lazo).
      cardSelection?.let { front ->
        Dialog(onDismissRequest = { cardSelection = null }) {
            DialogSurface {
                DialogTitle("Tarjeta de repaso")
                DialogOption(
                    label = "Solo frente (se muestra y calificas tu recuerdo)",
                    onClick = {
                        cardSelection = null
                        addCard(front, emptyList())
                    },
                )
                DialogOption(
                    label = "Frente + reverso (rodea la respuesta después)",
                    onClick = {
                        cardSelection = null
                        pendingCardFront = front
                        infoNotice = "Rodea la respuesta con «Selección» para completar la tarjeta"
                    },
                )
                DialogOption(label = "Cancelar", onClick = { cardSelection = null })
            }
        }
      }

      // v2 4.3 + RF-34: la goma invalidó una tarjeta (todo su frente borrado);
      // deshacer restaura los trazos completos y re-crea la tarjeta.
      cardUndo?.let { deleted ->
        TransientNotice(
            message = "Tarjeta de repaso eliminada",
            actionLabel = "Deshacer",
            onAction = {
                if (deleted.strokes.isNotEmpty()) {
                    val ids = deleted.card.frontStrokeIds.toHashSet()
                    strokes.removeAll { it.id in ids }
                    strokes.addAll(deleted.strokes)
                    deleted.strokes.forEach { fullStrokeById[it.id] = it }
                    inkChanged = true
                }
                cards.add(deleted.card)
                repo.saveCards(meta.uuid, cards)
                cardUndo = null
            },
            onDismiss = { cardUndo = null },
            modifier = Modifier.align(Alignment.BottomStart),
        )
      }

      linkUndo?.let { deleted ->
        TransientNotice(
            message = "Vínculo eliminado",
            actionLabel = "Deshacer",
            onAction = {
                if (deleted.strokes.isNotEmpty()) {
                    val ids = deleted.link.strokeIds.toHashSet()
                    strokes.removeAll { it.id in ids }
                    strokes.addAll(deleted.strokes)
                    deleted.strokes.forEach { fullStrokeById[it.id] = it }
                    inkChanged = true
                }
                links.add(deleted.link)
                repo.addRegionLink(meta.uuid, deleted.link)
                connections = repo.loadBacklinks(meta.uuid)
                linksChanged = true
                linkUndo = null
            },
            onDismiss = { linkUndo = null },
            modifier = Modifier.align(Alignment.BottomStart),
        )
      }

      // RF-34: aviso informativo (p. ej. selección ya linkeada). Acción única
      // que solo lo cierra — mismo componente, sin variantes ad hoc.
      infoNotice?.let { msg ->
        TransientNotice(
            message = msg,
            actionLabel = "OK",
            onAction = { infoNotice = null },
            onDismiss = { infoNotice = null },
            modifier = Modifier.align(Alignment.BottomStart),
        )
      }
    }
}

/** Link borrado (por goma o deliberado), retenido para deshacer (RF-05b): el
 *  link y los trazos a restaurar (vacío si la tinta quedó intacta). */
private data class DeletedLink(val link: LinkRegion, val strokes: List<IdStroke>)

/** Tarjeta invalidada por la goma (v2 4.3), con los trazos completos del
 *  frente para poder deshacer — mismo patrón que [DeletedLink]. */
private data class DeletedCard(val card: ReviewCard, val strokes: List<IdStroke>)

/** Menú de overflow de la nota: ajustes, tags, exportar y eliminar. */
@Composable
private fun NoteOverflowMenu(
    expanded: Boolean,
    tagCount: Int,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
    onTags: () -> Unit,
    onExportPdf: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = Theme.colors
    Box {
        BasicText(
            text = "⋮",
            style = TextStyle(color = colors.textPrimary, fontSize = AppType.title),
            modifier = Modifier
                .border(1.dp, colors.outline, RoundedCornerShape(8.dp))
                .clickable(onClick = onExpand)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
        if (expanded) {
            Popup(onDismissRequest = onDismiss) {
                Column(
                    modifier = Modifier
                        .background(colors.surface, RoundedCornerShape(8.dp))
                        .border(1.dp, colors.outlineVariant, RoundedCornerShape(8.dp))
                        .padding(vertical = 4.dp),
                ) {
                    MenuRow("Ajustes de página", onSettings)
                    MenuRow("Tags ($tagCount)", onTags)
                    MenuRow("Exportar PDF", onExportPdf)
                    // RF-36: reversible (papelera + deshacer), sin confirmación.
                    MenuRow("Eliminar nota", onDelete, danger = true)
                }
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit, danger: Boolean = false) {
    val colors = Theme.colors
    BasicText(
        text = label,
        style = TextStyle(
            color = if (danger) colors.danger else colors.textPrimary,
            fontSize = AppType.label,
        ),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .fillMaxWidth(),
    )
}

/** Texto separado por comas → lista de tags limpia (sin vacíos ni repetidos). */
fun parseTags(text: String): List<String> =
    text.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** Confirma `raw` como chip nuevo (RF-16): recorta, ignora vacíos y duplicados. */
fun addTag(tags: List<String>, raw: String): List<String> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || tags.contains(trimmed)) return tags
    return tags + trimmed
}

/** Editor de tags (RF-16): entrada de chips — espacio/Enter confirma la
 *  palabra escrita como etiqueta removible con un toque, sin comas manuales.
 *  Tocar fuera GUARDA los cambios (heurística 5: antes descartaba en
 *  silencio); descartar es solo el botón explícito "Cancelar". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsDialog(
    initial: List<String>,
    onConfirm: (List<String>) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = Theme.colors
    var chips by remember { mutableStateOf(initial) }
    var pending by remember { mutableStateOf("") }
    fun confirm() = onConfirm(addTag(chips, pending))
    Dialog(onDismissRequest = ::confirm) {
        DialogSurface {
            BasicText(
                text = "Tags",
                style = TextStyle(
                    color = colors.textPrimary,
                    fontSize = AppType.title,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(modifier = Modifier.height(4.dp))
            BasicText(
                text = "Escribe una palabra y confirma con espacio o Enter",
                style = TextStyle(color = colors.textSecondary, fontSize = AppType.body),
            )
            Spacer(modifier = Modifier.height(16.dp))
            if (chips.isNotEmpty()) {
                FlowRow(modifier = Modifier.fillMaxWidth()) {
                    chips.forEach { tag ->
                        TagChip(text = tag, onRemove = { chips = chips - tag })
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            BasicTextField(
                value = pending,
                onValueChange = { text ->
                    if (text.endsWith(" ") || text.endsWith("\n")) {
                        chips = addTag(chips, text)
                        pending = ""
                    } else {
                        pending = text
                    }
                },
                textStyle = TextStyle(color = colors.textPrimary, fontSize = AppType.label),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        chips = addTag(chips, pending)
                        pending = ""
                    },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, colors.outline, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.align(Alignment.End)) {
                AppButton(label = "Cancelar", onClick = onCancel, style = ButtonStyle.TEXT)
                AppButton(
                    label = "Guardar",
                    onClick = ::confirm,
                    style = ButtonStyle.FILLED,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/** Chip visual de un tag (RF-16): X o mantener presionado lo remueve. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TagChip(text: String, onRemove: () -> Unit) {
    val colors = Theme.colors
    Row(
        modifier = Modifier
            .padding(end = 6.dp, bottom = 6.dp)
            .background(colors.surfaceVariant, RoundedCornerShape(16.dp))
            .combinedClickable(onClick = {}, onLongClick = onRemove)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(text = text, style = TextStyle(color = colors.textPrimary, fontSize = AppType.body))
        Spacer(modifier = Modifier.width(6.dp))
        BasicText(
            text = "✕",
            style = TextStyle(color = colors.textSecondary, fontSize = AppType.caption),
            modifier = Modifier.clickable(onClick = onRemove),
        )
    }
}

/**
 * Ajustes de la nota: fondo de página (RF-06, blanco/líneas/cuadrícula + slider
 * de espaciado, para toda la nota) y tamaño + orientación de la PÁGINA ACTUAL
 * (RF-06a, presets + personalizado por página — [pageLabel] indica cuál). Los
 * cambios se aplican en vivo al canvas; la persistencia ocurre al cerrar (en
 * [onDismiss]). Nunca bloquea antes de escribir — se invoca solo desde el menú.
 */
@Composable
private fun NoteSettingsDialog(
    paper: PaperBackground,
    onPaperChange: (PaperBackground) -> Unit,
    pageSize: PageSize,
    onPageSizeChange: (PageSize) -> Unit,
    pageLabel: String,
    // v2 2.3: guarda fondo + tamaño actual como plantilla con nombre.
    onSaveTemplate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Theme.colors
    Dialog(onDismissRequest = onDismiss) {
        DialogSurface {
            SettingsHeading("Fondo de página")
            Spacer(modifier = Modifier.height(12.dp))
            Row {
                AppChip("Blanco", paper.style == PaperStyle.BLANK, {
                    onPaperChange(paper.copy(style = PaperStyle.BLANK))
                }, modifier = Modifier.padding(end = 8.dp))
                AppChip("Líneas", paper.style == PaperStyle.LINES, {
                    onPaperChange(paper.copy(style = PaperStyle.LINES))
                }, modifier = Modifier.padding(end = 8.dp))
                AppChip("Cuadrícula", paper.style == PaperStyle.GRID, {
                    onPaperChange(paper.copy(style = PaperStyle.GRID))
                }, modifier = Modifier.padding(end = 8.dp))
                AppChip("Puntos", paper.style == PaperStyle.DOTS, {
                    onPaperChange(paper.copy(style = PaperStyle.DOTS))
                })
            }
            // Espaciado (RF-06): solo relevante para líneas/cuadrícula.
            if (paper.style != PaperStyle.BLANK) {
                Spacer(modifier = Modifier.height(8.dp))
                GraphSlider(
                    label = "Espaciado",
                    value = paper.spacing,
                    range = PAPER_SPACING_RANGE,
                    onChange = { onPaperChange(paper.copy(spacing = it)) },
                    onCommit = {},
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            SettingsHeading("Tamaño de página ($pageLabel)")
            Spacer(modifier = Modifier.height(12.dp))
            // Modo personalizado explícito: no basta comparar con presets, porque
            // un tamaño custom podría coincidir por valor con un preset. Arranca
            // en custom si la página ya traía un tamaño fuera de los presets.
            var custom by remember { mutableStateOf(presetNameFor(pageSize) == null) }
            Row {
                for ((name, size) in PAGE_SIZE_PRESETS) {
                    // El preset matchea en ambas orientaciones y al aplicarse
                    // conserva la orientación activa de la página (RF-06a).
                    AppChip(
                        name,
                        !custom && (pageSize == size || pageSize == size.rotated()),
                        {
                            custom = false
                            onPageSizeChange(if (pageSize.isLandscape) size.rotated() else size)
                        },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                // Personalizado: al tocarlo se muestran los campos ancho×alto,
                // partiendo del tamaño actual como base editable.
                AppChip("Personalizado", custom, { custom = true })
            }
            Spacer(modifier = Modifier.height(8.dp))
            // Orientación (RF-06a): intercambia las dimensiones, sin campo extra.
            Row {
                AppChip("Retrato", !pageSize.isLandscape, {
                    if (pageSize.isLandscape) onPageSizeChange(pageSize.rotated())
                }, modifier = Modifier.padding(end = 8.dp))
                AppChip("Paisaje", pageSize.isLandscape, {
                    if (!pageSize.isLandscape) onPageSizeChange(pageSize.rotated())
                })
            }
            if (custom) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberField(
                        value = pageSize.widthMm,
                        range = PAGE_MM_RANGE,
                        onValue = { onPageSizeChange(pageSize.copy(widthMm = it)) },
                        modifier = Modifier.width(72.dp),
                    )
                    BasicText(
                        text = "×",
                        style = TextStyle(color = colors.textPrimary, fontSize = AppType.label),
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    NumberField(
                        value = pageSize.heightMm,
                        range = PAGE_MM_RANGE,
                        onValue = { onPageSizeChange(pageSize.copy(heightMm = it)) },
                        modifier = Modifier.width(72.dp),
                    )
                    BasicText(
                        text = "mm",
                        style = TextStyle(color = colors.textSecondary, fontSize = AppType.body),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Row(modifier = Modifier.align(Alignment.End)) {
                AppButton(
                    label = "Guardar plantilla",
                    onClick = onSaveTemplate,
                    modifier = Modifier.padding(end = 8.dp),
                )
                AppButton(label = "Cerrar", onClick = onDismiss, style = ButtonStyle.TEXT)
            }
        }
    }
}

@Composable
private fun SettingsHeading(text: String) {
    BasicText(
        text = text,
        style = TextStyle(
            color = Theme.colors.textPrimary,
            fontSize = AppType.label,
            fontWeight = FontWeight.Bold,
        ),
    )
}

/** Sheet inferior mínimo sin Material: scrim que cierra al tocar + panel. */
@Composable
private fun BottomSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Theme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .clickable(onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // Absorbe el tap para que tocar el panel no cierre el sheet.
                .clickable(enabled = false, onClick = {})
                .background(
                    colors.surface,
                    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                )
                .padding(20.dp),
            content = content,
        )
    }
}

/**
 * Búsqueda de la nota destino del Lazo de vínculo (UC-05, RF-17): reusa el
 * filtrado instantáneo de la Fase 4 (título + OCR indexado, sin tildes) vía
 * [galleryNotes]. La nota origen se excluye de las candidatas.
 */
@Composable
private fun LinkTargetSheet(
    repo: NoteRepository,
    originUuid: String,
    onSelect: (NoteMeta) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Theme.colors
    val candidates = remember { repo.listNotes().filterNot { it.uuid == originUuid } }
    // ponytail: relee el OCR de todas las notas al abrir el sheet; cachear si
    // el vault crece a cientos de notas.
    val ocrTexts = remember {
        candidates.associate { it.uuid to repo.loadOcrText(it.uuid) }
    }
    var query by remember { mutableStateOf("") }
    // Consulta vacía = todas las candidatas (galleryNotes con consulta vacía
    // filtraría por cuaderno, que aquí no aplica: la búsqueda es global).
    val results = if (query.isBlank()) {
        candidates
    } else {
        galleryNotes(GalleryUiState(notes = candidates, ocrTexts = ocrTexts, query = query))
    }

    BottomSheet(onDismiss = onDismiss) {
        BasicText(
            text = "Linkear a…",
            style = TextStyle(
                color = colors.textPrimary,
                fontSize = AppType.title,
                fontWeight = FontWeight.Bold,
            ),
        )
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = TextStyle(color = colors.textPrimary, fontSize = AppType.label),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        BasicText(
                            text = "Buscar por título o contenido…",
                            style = TextStyle(color = colors.textHint, fontSize = AppType.label),
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .border(1.dp, colors.outline, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
        if (results.isEmpty()) {
            BasicText(
                text = if (candidates.isEmpty()) {
                    "No hay otras notas para linkear"
                } else {
                    "Sin coincidencias"
                },
                style = TextStyle(color = colors.textHint, fontSize = AppType.label),
                modifier = Modifier.padding(vertical = 16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(results, key = { it.uuid }) { note ->
                    SheetNoteRow(note = note, onClick = { onSelect(note) })
                }
            }
        }
    }
}

/** Panel de referencias (RF-23b, UC-06): notas conectadas según graph.json. */
@Composable
private fun BacklinksSheet(
    repo: NoteRepository,
    connections: List<String>,
    onOpen: (NoteMeta) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Theme.colors
    // Títulos desde los meta.json de las notas conectadas.
    val notes = remember(connections) {
        val byUuid = repo.listNotes().associateBy { it.uuid }
        connections.mapNotNull { byUuid[it] }
    }
    BottomSheet(onDismiss = onDismiss) {
        BasicText(
            text = "Vínculos entrantes (${notes.size})",
            style = TextStyle(
                color = colors.textPrimary,
                fontSize = AppType.title,
                fontWeight = FontWeight.Bold,
            ),
        )
        if (notes.isEmpty()) {
            BasicText(
                text = "Ninguna nota linkea con esta todavía",
                style = TextStyle(color = colors.textHint, fontSize = AppType.label),
                modifier = Modifier.padding(vertical = 16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .heightIn(max = 360.dp),
            ) {
                items(notes, key = { it.uuid }) { note ->
                    SheetNoteRow(note = note, onClick = { onOpen(note) })
                }
            }
        }
    }
}

@Composable
private fun SheetNoteRow(note: NoteMeta, onClick: () -> Unit) {
    BasicText(
        text = note.title,
        style = TextStyle(color = Theme.colors.textPrimary, fontSize = AppType.label),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
    )
}

/**
 * Controles de página (RF-09/RF-09a): navegación explícita anterior/siguiente
 * con indicador «n/m» y botón de añadir. Complementan al swipe del canvas (que
 * solo pagina con la página encajada a ~1x): con zoom activo o para añadir
 * páginas, estos botones son el camino.
 */
@Composable
private fun PageControls(
    currentPage: Int,
    pageCount: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        AppButton(label = "◀", enabled = currentPage > 0, onClick = onPrev)
        BasicText(
            text = "${currentPage + 1}/$pageCount",
            style = TextStyle(color = Theme.colors.textPrimary, fontSize = AppType.label),
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        AppButton(label = "▶", enabled = currentPage < pageCount - 1, onClick = onNext)
        AppButton(
            label = "+ Página",
            onClick = onAdd,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
