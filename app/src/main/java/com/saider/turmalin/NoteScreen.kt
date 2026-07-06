package com.saider.turmalin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.ink.strokes.Stroke
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    focusTitleOnOpen: Boolean,
    // Al cerrar entrega el meta guardado y si la tinta cambió en la sesión
    // (dirty flag): decide si se dispara el OCR (RF-24). Editar solo el
    // título o navegar páginas no marca la tinta como sucia.
    onClose: (NoteMeta, inkChanged: Boolean) -> Unit,
    // Seguir un link (región linkeada o backlink): cierra esta nota igual que
    // onClose y navega a la nota destino por su uuid.
    onFollowLink: (NoteMeta, inkChanged: Boolean, targetUuid: String) -> Unit,
) {
    val brush = remember { defaultBlackPen() }
    // RF-09a: se restaura la última página abierta (coerción por si el meta
    // viene de una versión sin páginas o quedó inconsistente).
    val initialPage = remember { meta.lastPageIndex.coerceIn(0, meta.pageCount - 1) }
    var pageCount by remember { mutableStateOf(meta.pageCount) }
    var currentPage by remember { mutableStateOf(initialPage) }
    val initialLoaded = remember { repo.loadStrokes(meta.uuid, initialPage, brush) }
    val strokes = remember { mutableStateListOf<IdStroke>().apply { addAll(initialLoaded) } }
    // Versión más completa conocida de cada trazo en esta sesión (por ID), para
    // restaurar un link al deshacer su borrado. Se llena al abrir la página y al
    // crear un link (nunca se sobrescribe con piezas recortadas), así "Deshacer"
    // devuelve la palabra entera, no el último fragmento.
    val fullStrokeById = remember {
        mutableStateMapOf<Long, IdStroke>().apply { putAll(initialLoaded.associateBy { it.id }) }
    }
    // Pincel del halo de link (RF-23a); constante por sesión.
    val linkBrush = remember { linkOverlayBrush() }
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
    // Alcance para la exportación a PDF en background (UC-10).
    val exportScope = rememberCoroutineScope()
    // Trazos seleccionados por el lazo, pendientes de destino (UC-05): no nulo =
    // sheet de búsqueda abierto.
    var pendingSelection by remember { mutableStateOf<List<IdStroke>?>(null) }
    var showBacklinks by remember { mutableStateOf(false) }
    // Link recién borrado por la goma (RF-05a/b), ofrecido para deshacer.
    var linkUndo by remember { mutableStateOf<DeletedLink?>(null) }
    // Aviso informativo no bloqueante (RF-34): p. ej. selección ya linkeada.
    var infoNotice by remember { mutableStateOf<String?>(null) }

    // Overlays de los links de la página actual (RF-23a): halo ceñido re-tiñendo
    // los trazos linkeados vivos. Si un link no resuelve trazos (todos borrados),
    // no se dibuja — la goma ya debería haberlo eliminado (RF-05b).
    val linkOverlays = links.filter { it.page == currentPage }.mapNotNull { link ->
        val targets = strokes.filter { it.id in link.strokeIds }
        if (targets.isEmpty()) return@mapNotNull null
        LinkOverlay(
            targetUuid = link.targetUuid,
            tintStrokes = targets.map { Stroke(linkBrush, it.stroke.inputs) },
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
            repo.saveRegionLinks(meta.uuid, links)
            repo.removeGraphLinkIfOrphan(meta.uuid, link.targetUuid)
            connections = repo.loadBacklinks(meta.uuid)
            val restore = link.strokeIds.mapNotNull { fullStrokeById[it] }.distinct()
            linkUndo = DeletedLink(link, restore)
        }
    }

    fun snapshotMeta(): NoteMeta = meta.copy(
        title = titleField.text.trim().ifBlank { DEFAULT_NOTE_TITLE },
        modifiedAtMillis = System.currentTimeMillis(),
        pageCount = pageCount,
        lastPageIndex = currentPage,
        tags = tags,
    )

    // Recarga la página destino en el lienzo y re-snapshotea la versión completa
    // de sus trazos (para deshacer). Los links son de la nota, no de la página.
    fun loadPageInto(target: Int) {
        val loaded = repo.loadStrokes(meta.uuid, target, brush)
        strokes.clear()
        strokes.addAll(loaded)
        fullStrokeById.clear()
        fullStrokeById.putAll(loaded.associateBy { it.id })
    }

    // Recalcula la bbox cacheada de cada link desde sus trazos vivos (tras
    // recortes de goma parcial), conservando su conjunto de IDs de creación.
    fun persistLinks() {
        repo.saveRegionLinks(
            meta.uuid,
            links.map { link ->
                val live = strokes.filter { it.id in link.strokeIds }
                if (live.isEmpty()) link else link.copy(bbox = strokesBoundingBox(live))
            },
        )
    }

    fun save(): NoteMeta {
        val updated = snapshotMeta()
        repo.saveStrokes(meta.uuid, currentPage, strokes)
        persistLinks()
        repo.saveMeta(updated)
        return updated
    }

    // RF-09a: cambiar de página guarda la actual antes de cargar la destino.
    fun switchToPage(target: Int) {
        if (target == currentPage || target !in 0 until pageCount) return
        repo.saveStrokes(meta.uuid, currentPage, strokes)
        persistLinks()
        currentPage = target
        loadPageInto(target)
        repo.saveMeta(snapshotMeta())
    }

    // RF-09: añadir página con botón explícito; la nueva queda al final y se
    // navega a ella de inmediato.
    fun addPage() {
        repo.saveStrokes(meta.uuid, currentPage, strokes)
        persistLinks()
        pageCount += 1
        currentPage = pageCount - 1
        strokes.clear()
        fullStrokeById.clear()
        repo.saveMeta(snapshotMeta())
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

    Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
      Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = "←",
                style = TextStyle(color = Color.Black, fontSize = 24.sp),
                modifier = Modifier
                    .clickable { onClose(save(), inkChanged) }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
            BasicTextField(
                value = titleField,
                onValueChange = { titleField = it },
                textStyle = TextStyle(
                    color = Color.Black,
                    fontSize = 18.sp,
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
                                    color = Color(0xFF999999),
                                    fontSize = 18.sp,
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
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            // RF-16: tags manuales; el chip abre el editor por teclado y muestra
            // cuántos tiene la nota.
            BasicText(
                text = "🏷 ${tags.size}",
                style = TextStyle(
                    color = if (tags.isEmpty()) Color(0xFF999999) else Color.Black,
                    fontSize = 16.sp,
                ),
                modifier = Modifier
                    .padding(end = 8.dp)
                    .border(1.dp, Color(0xFFCCCCCC), RoundedCornerShape(8.dp))
                    .clickable { showTags = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            // UC-10: exporta la nota a PDF vectorial (ink + overlays de link) en
            // Descargas; guarda primero para volcar la página abierta.
            BasicText(
                text = "PDF",
                style = TextStyle(color = Color.Black, fontSize = 16.sp),
                modifier = Modifier
                    .padding(end = 8.dp)
                    .border(1.dp, Color(0xFFCCCCCC), RoundedCornerShape(8.dp))
                    .clickable {
                        val exported = save()
                        exportScope.launch {
                            val uri = withContext(Dispatchers.IO) {
                                repo.exportNoteToPdf(exported)
                            }
                            infoNotice = if (uri != null) {
                                "PDF guardado en Descargas"
                            } else {
                                "No se pudo exportar el PDF"
                            }
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            // RF-23b: badge con el número de referencias de la nota; se abre
            // por toque (no swipe) para no comprometer el palm rejection.
            BasicText(
                text = "⇄ ${connections.size}",
                style = TextStyle(
                    color = if (connections.isEmpty()) Color(0xFF999999) else Color.Black,
                    fontSize = 16.sp,
                ),
                modifier = Modifier
                    .border(1.dp, Color(0xFFCCCCCC), RoundedCornerShape(8.dp))
                    .clickable { showBacklinks = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            InkCanvasScreen(
                strokes = strokes,
                wetHighLatency = wetHighLatency,
                eraserRouter = eraserRouter,
                onSwipePage = { direction -> switchToPage(currentPage + direction) },
                onInkModified = { inkChanged = true },
                linkOverlays = linkOverlays,
                // RF-17: al cerrar el lazo, seleccionamos por cobertura de área
                // los trazos dentro. Si algún trazo ya pertenece a un link, se
                // bloquea: un trazo apunta a lo sumo a un destino (aviso RF-34).
                onLassoComplete = { polygon ->
                    val selected = strokesInLasso(strokes, polygon)
                    val alreadyLinked = selected.any { s ->
                        links.any { it.page == currentPage && s.id in it.strokeIds }
                    }
                    when {
                        selected.isEmpty() -> Unit
                        alreadyLinked -> infoNotice = "Esta selección ya tiene un link"
                        else -> pendingSelection = selected
                    }
                },
                onLinkTap = { targetUuid ->
                    onFollowLink(save(), inkChanged, targetUuid)
                },
                onStrokesErased = { removed -> onStrokesErased(removed) },
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
            onDismiss = { showTags = false },
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

      // RF-05b + RF-34: aviso no bloqueante para deshacer el borrado del link.
      // "Deshacer" restaura los trazos completos (snapshot) y re-crea el link.
      linkUndo?.let { deleted ->
        TransientNotice(
            message = "Link eliminado",
            actionLabel = "Deshacer",
            onAction = {
                val ids = deleted.link.strokeIds.toHashSet()
                strokes.removeAll { it.id in ids }
                strokes.addAll(deleted.strokes)
                deleted.strokes.forEach { fullStrokeById[it.id] = it }
                links.add(deleted.link)
                repo.addRegionLink(meta.uuid, deleted.link)
                connections = repo.loadBacklinks(meta.uuid)
                inkChanged = true
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

/** Link borrado por la goma, retenido para deshacer (RF-05b): el link y los
 *  trazos cuyo borrado total lo mataron (se restauran juntos). */
private data class DeletedLink(val link: LinkRegion, val strokes: List<IdStroke>)

/** Texto separado por comas → lista de tags limpia (sin vacíos ni repetidos). */
fun parseTags(text: String): List<String> =
    text.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** Editor de tags (RF-16): un campo de texto separado por comas, sin Material. */
@Composable
private fun TagsDialog(
    initial: List<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial.joinToString(", ")) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color.White, RoundedCornerShape(16.dp))
                .padding(24.dp),
        ) {
            BasicText(
                text = "Tags",
                style = TextStyle(
                    color = Color.Black,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(modifier = Modifier.height(4.dp))
            BasicText(
                text = "Separa con comas",
                style = TextStyle(color = Color(0xFF888888), fontSize = 14.sp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                textStyle = TextStyle(color = Color.Black, fontSize = 17.sp),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFCCCCCC), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.align(Alignment.End)) {
                TagsDialogButton(label = "Cancelar", onClick = onDismiss)
                TagsDialogButton(label = "Guardar", onClick = { onConfirm(parseTags(value)) })
            }
        }
    }
}

@Composable
private fun TagsDialogButton(label: String, onClick: () -> Unit) {
    BasicText(
        text = label,
        style = TextStyle(
            color = Color.Black,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        ),
        modifier = Modifier
            .padding(start = 16.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    )
}

/** Sheet inferior mínimo sin Material: scrim que cierra al tocar + panel blanco. */
@Composable
private fun BottomSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // Absorbe el tap para que tocar el panel no cierre el sheet.
                .clickable(enabled = false, onClick = {})
                .background(
                    Color.White,
                    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                )
                .padding(20.dp),
            content = content,
        )
    }
}

/**
 * Búsqueda de la nota destino del lazo (UC-05, RF-17): reusa el filtrado
 * instantáneo de la Fase 4 (título + OCR indexado, sin tildes) vía
 * [galleryNotes]. La nota origen se excluye de las candidatas.
 */
@Composable
private fun LinkTargetSheet(
    repo: NoteRepository,
    originUuid: String,
    onSelect: (NoteMeta) -> Unit,
    onDismiss: () -> Unit,
) {
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
                color = Color.Black,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = TextStyle(color = Color.Black, fontSize = 16.sp),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        BasicText(
                            text = "Buscar por título o contenido…",
                            style = TextStyle(color = Color(0xFF999999), fontSize = 16.sp),
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .border(1.dp, Color(0xFFCCCCCC), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
        if (results.isEmpty()) {
            BasicText(
                text = if (candidates.isEmpty()) {
                    "No hay otras notas para linkear"
                } else {
                    "Sin coincidencias"
                },
                style = TextStyle(color = Color(0xFF999999), fontSize = 16.sp),
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
    // Títulos desde los meta.json de las notas conectadas.
    val notes = remember(connections) {
        val byUuid = repo.listNotes().associateBy { it.uuid }
        connections.mapNotNull { byUuid[it] }
    }
    BottomSheet(onDismiss = onDismiss) {
        BasicText(
            text = "Referencias (${notes.size})",
            style = TextStyle(
                color = Color.Black,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        if (notes.isEmpty()) {
            BasicText(
                text = "Ninguna nota linkea con esta todavía",
                style = TextStyle(color = Color(0xFF999999), fontSize = 16.sp),
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
        style = TextStyle(color = Color.Black, fontSize = 16.sp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
    )
}

/**
 * Controles de página (RF-09/RF-09a): navegación explícita anterior/siguiente
 * con indicador «n/m» y botón de añadir. Complementan al swipe de dos dedos
 * del canvas (que solo pagina con la página encajada a ~1x): con zoom activo
 * o para añadir páginas, estos botones son el camino.
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
        PageChip(label = "◀", enabled = currentPage > 0, onClick = onPrev)
        BasicText(
            text = "${currentPage + 1}/$pageCount",
            style = TextStyle(color = Color.Black, fontSize = 16.sp),
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        PageChip(label = "▶", enabled = currentPage < pageCount - 1, onClick = onNext)
        PageChip(label = "+ Página", enabled = true, onClick = onAdd)
    }
}

@Composable
private fun PageChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val contentColor = if (enabled) Color.Black else Color(0xFFBBBBBB)
    BasicText(
        text = label,
        style = TextStyle(color = contentColor, fontSize = 16.sp),
        modifier = Modifier
            .padding(4.dp)
            .background(Color.White, shape)
            .border(1.dp, contentColor, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}
