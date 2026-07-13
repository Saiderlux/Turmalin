package com.saider.turmalin

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Diálogo activo en la galería; null = ninguno. */
private sealed interface GalleryDialog {
    data object NewNotebook : GalleryDialog
    data class NotebookActions(val notebook: Notebook) : GalleryDialog
    data class RenameNotebook(val notebook: Notebook) : GalleryDialog
    data class NoteActions(val note: NoteMeta) : GalleryDialog
    data class NoteNotebooks(val note: NoteMeta) : GalleryDialog
    data object NewNoteFromTemplate : GalleryDialog
}

/**
 * Pantalla de inicio (RF-15): cuadrícula de cuadernos y notas del vault.
 * La raíz muestra los cuadernos como carpetas navegables (estilo Obsidian)
 * más las notas sueltas; dentro de un cuaderno se ven solo sus notas.
 */
@Composable
fun GalleryScreen(
    state: GalleryUiState,
    titleNudge: TitleNudge?,
    onNudgeAction: (TitleNudge) -> Unit,
    onNudgeDismiss: () -> Unit,
    onSetQuery: (String) -> Unit,
    onNewNote: () -> Unit,
    onNewNoteFromTemplate: (NoteTemplate) -> Unit,
    onDeleteTemplate: (NoteTemplate) -> Unit,
    onOpenNote: (NoteMeta) -> Unit,
    onOpenNotebook: (String?) -> Unit,
    onSetSortOrder: (SortOrder) -> Unit,
    onCreateNotebook: (String) -> Unit,
    onRenameNotebook: (Notebook, String) -> Unit,
    onDeleteNotebook: (Notebook) -> Unit,
    onSetNotebooks: (NoteMeta, List<String>) -> Unit,
    onOpenGraph: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
    onDeleteNote: (NoteMeta) -> Unit,
    deleteUndo: NoteMeta?,
    onUndoDeleteNote: () -> Unit,
    onDismissDeleteUndo: () -> Unit,
    // Estado visible del sistema (heurística 1): "Nota guardada",
    // "indexando contenido…"; null = nada que mostrar.
    saveStatus: String? = null,
) {
    val colors = Theme.colors
    var dialog by remember { mutableStateOf<GalleryDialog?>(null) }
    val openNotebook = state.notebooks.find { it.id == state.openNotebookId }
    val notes = galleryNotes(state)

    // Gesto atrás dentro de un cuaderno: volver a la raíz, no salir de la app.
    BackHandler(enabled = state.openNotebookId != null) { onOpenNotebook(null) }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            GalleryHeader(
                openNotebook = openNotebook,
                sortOrder = state.sortOrder,
                onBack = { onOpenNotebook(null) },
                onSetSortOrder = onSetSortOrder,
                onNewNotebook = { dialog = GalleryDialog.NewNotebook },
                onOpenGraph = onOpenGraph,
                onOpenTrash = onOpenTrash,
                onOpenSettings = onOpenSettings,
            )

            SearchField(query = state.query, onSetQuery = onSetQuery)

            // Con búsqueda activa los resultados son globales (UC-09): las
            // tarjetas de cuaderno se ocultan y solo se listan coincidencias.
            val showNotebooks = state.openNotebookId == null && state.query.isBlank()
            if (notes.isEmpty() && (!showNotebooks || state.notebooks.isEmpty())) {
                EmptyGalleryMessage(
                    text = if (state.query.isBlank()) {
                        "Sin notas todavía — crea la primera"
                    } else {
                        "Sin coincidencias para «${state.query.trim()}»"
                    },
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 170.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (showNotebooks) {
                        items(state.notebooks, key = { "notebook-${it.id}" }) { notebook ->
                            NotebookCard(
                                notebook = notebook,
                                noteCount = state.notes.count { notebook.id in it.notebookIds },
                                onOpen = { onOpenNotebook(notebook.id) },
                                onLongPress = {
                                    dialog = GalleryDialog.NotebookActions(notebook)
                                },
                            )
                        }
                    }
                    items(notes, key = { it.uuid }) { note ->
                        NoteCard(
                            note = note,
                            thumbFile = state.thumbFiles[note.uuid],
                            onOpen = { onOpenNote(note) },
                            onLongPress = { dialog = GalleryDialog.NoteActions(note) },
                        )
                    }
                }
            }
        }

        NewNoteFab(
            onClick = onNewNote,
            onLongPress = { dialog = GalleryDialog.NewNoteFromTemplate },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
        )

        // Confirmación de guardado e indexado OCR (heurística 1): pasivo, sin
        // acción — convive con los avisos RF-34 de la esquina izquierda.
        saveStatus?.let { status ->
            StatusChip(
                text = status,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )
        }

        // Aviso de título pendiente (RF-11/RF-12) con el componente estándar
        // RF-34: si el OCR aportó una primera línea, se ofrece como título.
        titleNudge?.let { nudge ->
            key(nudge) {
                TransientNotice(
                    message = nudge.suggestedTitle
                        ?.let { "Título sugerido: «$it»" }
                        ?: "La nota quedó sin título",
                    actionLabel = if (nudge.suggestedTitle != null) "Usar" else "Añadir título",
                    onAction = { onNudgeAction(nudge) },
                    onDismiss = onNudgeDismiss,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }

        // RF-36: aviso de deshacer tras mover una nota a la papelera (UC-13).
        // Comparte esquina con el aviso de título; ambos son transitorios y no
        // deberían solaparse en el flujo normal.
        deleteUndo?.let { note ->
            key(note.uuid) {
                TransientNotice(
                    message = "«${note.title}» eliminada",
                    actionLabel = "Deshacer",
                    onAction = onUndoDeleteNote,
                    onDismiss = onDismissDeleteUndo,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }
    }

    when (val current = dialog) {
        null -> Unit
        is GalleryDialog.NewNotebook -> TextInputDialog(
            title = "Nuevo cuaderno",
            confirmLabel = "Crear",
            onConfirm = { name ->
                onCreateNotebook(name)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        is GalleryDialog.NotebookActions -> NotebookActionsDialog(
            notebook = current.notebook,
            onRename = { dialog = GalleryDialog.RenameNotebook(current.notebook) },
            onDelete = {
                onDeleteNotebook(current.notebook)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        is GalleryDialog.RenameNotebook -> TextInputDialog(
            title = "Renombrar cuaderno",
            confirmLabel = "Guardar",
            initialValue = current.notebook.name,
            onConfirm = { name ->
                onRenameNotebook(current.notebook, name)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        is GalleryDialog.NoteActions -> NoteActionsDialog(
            note = current.note,
            onMove = { dialog = GalleryDialog.NoteNotebooks(current.note) },
            onDelete = {
                onDeleteNote(current.note)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        is GalleryDialog.NoteNotebooks -> NoteNotebooksDialog(
            note = current.note,
            notebooks = state.notebooks,
            onSetNotebooks = { ids -> onSetNotebooks(current.note, ids) },
            onDismiss = { dialog = null },
        )
        is GalleryDialog.NewNoteFromTemplate -> NewNoteFromTemplateDialog(
            templates = state.templates,
            onCreate = { template ->
                onNewNoteFromTemplate(template)
                dialog = null
            },
            onDelete = onDeleteTemplate,
            onDismiss = { dialog = null },
        )
    }
}

/**
 * Búsqueda instantánea (RF-26/27): filtra título + texto OCR ya indexado en
 * memoria mientras se escribe — nunca ejecuta OCR al consultar.
 */
@Composable
private fun SearchField(query: String, onSetQuery: (String) -> Unit) {
    val colors = Theme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 8.dp)
            .border(1.dp, colors.outline, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = query,
            onValueChange = onSetQuery,
            textStyle = TextStyle(color = colors.textPrimary, fontSize = AppType.label),
            singleLine = true,
            decorationBox = { innerTextField ->
                Box {
                    if (query.isEmpty()) {
                        BasicText(
                            text = "Buscar por título o contenido…",
                            style = TextStyle(color = colors.textHint, fontSize = AppType.label),
                        )
                    }
                    innerTextField()
                }
            },
            modifier = Modifier.weight(1f),
        )
        if (query.isNotEmpty()) {
            BasicText(
                text = "✕",
                style = TextStyle(color = colors.textSecondary, fontSize = AppType.label),
                modifier = Modifier
                    .clickable { onSetQuery("") }
                    .padding(start = 8.dp),
            )
        }
    }
}

/** Barra superior: volver/título, orden y alta de cuadernos (solo en raíz). */
@Composable
private fun GalleryHeader(
    openNotebook: Notebook?,
    sortOrder: SortOrder,
    onBack: () -> Unit,
    onSetSortOrder: (SortOrder) -> Unit,
    onNewNotebook: () -> Unit,
    onOpenGraph: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = Theme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (openNotebook != null) {
            BackArrow(onClick = onBack)
        }
        BasicText(
            text = openNotebook?.name ?: "Turmalin",
            style = TextStyle(
                color = colors.textPrimary,
                fontSize = AppType.display,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
        )
        SortMenu(sortOrder = sortOrder, onSetSortOrder = onSetSortOrder)
        if (openNotebook == null) {
            // RF-19: entrada a la vista de grafo desde la raíz de la galería.
            AppButton(
                label = "Grafo",
                onClick = onOpenGraph,
                style = ButtonStyle.FILLED,
                modifier = Modifier.padding(start = 8.dp),
            )
            // RF-36: entrada a la papelera (UC-14), solo visible en la raíz.
            AppButton(
                label = "Papelera",
                onClick = onOpenTrash,
                modifier = Modifier.padding(start = 8.dp),
            )
            AppButton(
                label = "+ Cuaderno",
                onClick = onNewNotebook,
                style = ButtonStyle.FILLED,
                modifier = Modifier.padding(start = 8.dp),
            )
            // v2 3.4: ajustes globales de la app.
            Box(modifier = Modifier.padding(start = 8.dp)) {
                AppIconButton(
                    icon = AppIcons.Settings,
                    label = "Ajustes",
                    selected = false,
                    onClick = onOpenSettings,
                )
            }
        }
    }
}

private val sortLabels = mapOf(
    SortOrder.MODIFIED to "Fecha",
    SortOrder.TITLE to "Título",
    SortOrder.NOTEBOOK to "Cuaderno",
    SortOrder.TAGS to "Tags",
)

/** Selector de orden (RF-15) con Popup de foundation — sin Material. */
@Composable
private fun SortMenu(
    sortOrder: SortOrder,
    onSetSortOrder: (SortOrder) -> Unit,
) {
    val colors = Theme.colors
    var expanded by remember { mutableStateOf(false) }
    Box {
        AppButton(
            label = "Orden: ${sortLabels.getValue(sortOrder)} ▾",
            onClick = { expanded = true },
        )
        if (expanded) {
            Popup(onDismissRequest = { expanded = false }) {
                Column(
                    modifier = Modifier
                        .background(colors.surface, RoundedCornerShape(8.dp))
                        .border(1.dp, colors.outlineVariant, RoundedCornerShape(8.dp)),
                ) {
                    SortOrder.entries.forEach { order ->
                        BasicText(
                            text = sortLabels.getValue(order),
                            style = TextStyle(
                                color = colors.textPrimary,
                                fontSize = AppType.label,
                                fontWeight = if (order == sortOrder) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Normal
                                },
                            ),
                            modifier = Modifier
                                .clickable {
                                    expanded = false
                                    onSetSortOrder(order)
                                }
                                .padding(horizontal = 20.dp, vertical = 12.dp)
                                .fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotebookCard(
    notebook: Notebook,
    noteCount: Int,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val colors = Theme.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .background(colors.surfaceVariant, shape)
            .border(1.dp, colors.outlineVariant, shape)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(16.dp),
    ) {
        BasicText(
            text = "🗂 ${notebook.name}",
            style = TextStyle(
                color = colors.textPrimary,
                fontSize = AppType.label,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(6.dp))
        BasicText(
            text = if (noteCount == 1) "1 nota" else "$noteCount notas",
            style = TextStyle(color = colors.textSecondary, fontSize = AppType.caption),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    note: NoteMeta,
    thumbFile: File?,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val colors = Theme.colors
    val dateFormat = remember {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    }
    val shape = RoundedCornerShape(12.dp)
    // Carátula cacheada (RF-15): el mtime del archivo es la clave — solo se
    // redecodifica cuando la carátula se regeneró (contenido real cambiado).
    // ponytail: decode síncrono en composición; son webp de ~10-30KB.
    val thumbMtime = thumbFile?.lastModified() ?: 0L
    val thumbnail = remember(note.uuid, thumbMtime) {
        thumbFile
            ?.takeIf { thumbMtime > 0L }
            ?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
    }
    Column(
        modifier = Modifier
            .background(colors.surface, shape)
            .border(1.dp, colors.outlineVariant, shape)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(16.dp),
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, colors.outlineVariant, RoundedCornerShape(8.dp)),
            )
            Spacer(modifier = Modifier.height(10.dp))
        }
        BasicText(
            text = note.title,
            style = TextStyle(
                color = colors.textPrimary,
                fontSize = AppType.label,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(6.dp))
        BasicText(
            text = dateFormat.format(Date(note.modifiedAtMillis)),
            style = TextStyle(color = colors.textSecondary, fontSize = AppType.caption),
        )
    }
}

/** Botón flotante de nueva nota (UC-01). Tap = nota inmediata (RF-07, jamás
 *  bloquea con un selector); long-press = elegir plantilla guardada (v2 2.3). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NewNoteFab(
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Theme.colors
    Box(
        modifier = modifier
            .size(60.dp)
            .background(colors.accent, CircleShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = "+",
            style = TextStyle(color = colors.onAccent, fontSize = AppType.display),
        )
    }
}

/**
 * Nueva nota desde plantilla (v2 2.3): lista de presets guardados; el ✕ borra
 * el preset (recrearlo cuesta un toque desde los ajustes de una nota, no
 * amerita confirmación). Vacío ⇒ pista de cómo guardar la primera.
 */
@Composable
private fun NewNoteFromTemplateDialog(
    templates: List<NoteTemplate>,
    onCreate: (NoteTemplate) -> Unit,
    onDelete: (NoteTemplate) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Theme.colors
    Dialog(onDismissRequest = onDismiss) {
        DialogSurface {
            DialogTitle("Nueva nota desde plantilla")
            if (templates.isEmpty()) {
                BasicText(
                    text = "No hay plantillas todavía — guarda una desde los " +
                        "ajustes de página de cualquier nota",
                    style = TextStyle(color = colors.textSecondary, fontSize = AppType.body),
                )
            }
            templates.forEach { template ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) {
                        DialogOption(label = template.name, onClick = { onCreate(template) })
                    }
                    BasicText(
                        text = "✕",
                        style = TextStyle(color = colors.textHint, fontSize = AppType.label),
                        modifier = Modifier
                            .clickable { onDelete(template) }
                            .padding(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyGalleryMessage(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = TextStyle(color = Theme.colors.textSecondary, fontSize = AppType.label),
        )
    }
}

// --- Diálogos (compose.ui.window.Dialog, sin Material) ---

@Composable
fun DialogSurface(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .background(Theme.colors.surface, RoundedCornerShape(16.dp))
            .padding(24.dp),
        content = content,
    )
}

@Composable
fun DialogTitle(text: String) {
    BasicText(
        text = text,
        style = TextStyle(
            color = Theme.colors.textPrimary,
            fontSize = AppType.title,
            fontWeight = FontWeight.Bold,
        ),
    )
    Spacer(modifier = Modifier.height(16.dp))
}

@Composable
fun DialogOption(label: String, onClick: () -> Unit, danger: Boolean = false) {
    val colors = Theme.colors
    BasicText(
        text = label,
        style = TextStyle(
            color = if (danger) colors.danger else colors.textPrimary,
            fontSize = AppType.label,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

/** Diálogo genérico de un campo de texto (crear/renombrar cuaderno RF-13,
 *  nombrar plantilla v2 2.3). */
@Composable
fun TextInputDialog(
    title: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initialValue: String = "",
) {
    val colors = Theme.colors
    var value by remember { mutableStateOf(initialValue) }
    Dialog(onDismissRequest = onDismiss) {
        DialogSurface {
            DialogTitle(title)
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                textStyle = TextStyle(color = colors.textPrimary, fontSize = AppType.label),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, colors.outline, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.align(Alignment.End)) {
                AppButton(label = "Cancelar", onClick = onDismiss, style = ButtonStyle.TEXT)
                // Deshabilitado (no oculto) con el campo vacío: que el botón
                // desaparezca leía como error (heurística 4).
                AppButton(
                    label = confirmLabel,
                    onClick = { onConfirm(value.trim()) },
                    style = ButtonStyle.FILLED,
                    enabled = value.isNotBlank(),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/** Acciones de cuaderno (RF-13): renombrar o eliminar (las notas van a raíz). */
@Composable
private fun NotebookActionsDialog(
    notebook: Notebook,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        DialogSurface {
            DialogTitle(notebook.name)
            DialogOption(label = "Renombrar", onClick = onRename)
            DialogOption(
                label = "Eliminar (sus notas pasan a la raíz)",
                onClick = onDelete,
                danger = true,
            )
        }
    }
}

/** Acciones de nota desde la galería (RF-14/RF-36): cuadernos o eliminar. */
@Composable
private fun NoteActionsDialog(
    note: NoteMeta,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        DialogSurface {
            DialogTitle(note.title)
            DialogOption(label = "Cuadernos…", onClick = onMove)
            // RF-36: reversible (papelera + deshacer), sin diálogo de confirmación.
            DialogOption(label = "Eliminar", onClick = onDelete, danger = true)
        }
    }
}

/**
 * Cuadernos de una nota (RF-14, v2 4.4): multi-select — el cuaderno es una
 * colección, la nota puede pertenecer a varios. Cada toggle aplica en vivo;
 * sin ninguno marcado la nota vive en la raíz (no hay opción "Raíz" explícita).
 */
@Composable
private fun NoteNotebooksDialog(
    note: NoteMeta,
    notebooks: List<Notebook>,
    onSetNotebooks: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Theme.colors
    // Estado local: la nota del llamador queda obsoleta tras el primer toggle
    // (el refresh de la galería no re-emite este diálogo).
    var selected by remember { mutableStateOf(note.notebookIds.toSet()) }
    Dialog(onDismissRequest = onDismiss) {
        DialogSurface {
            DialogTitle("Cuadernos de «${note.title}»")
            if (notebooks.isEmpty()) {
                BasicText(
                    text = "No hay cuadernos todavía",
                    style = TextStyle(color = colors.textSecondary, fontSize = AppType.body),
                )
            }
            notebooks.forEach { notebook ->
                AppChip(
                    label = notebook.name,
                    selected = notebook.id in selected,
                    onClick = {
                        selected = if (notebook.id in selected) selected - notebook.id
                        else selected + notebook.id
                        onSetNotebooks(selected.toList())
                    },
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Row(modifier = Modifier.align(Alignment.End)) {
                AppButton(label = "Cerrar", onClick = onDismiss, style = ButtonStyle.TEXT)
            }
        }
    }
}
