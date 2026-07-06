package com.saider.turmalin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import java.text.DateFormat
import java.util.Date

/** Diálogo activo en la galería; null = ninguno. */
private sealed interface GalleryDialog {
    data object NewNotebook : GalleryDialog
    data class NotebookActions(val notebook: Notebook) : GalleryDialog
    data class RenameNotebook(val notebook: Notebook) : GalleryDialog
    data class MoveNote(val note: NoteMeta) : GalleryDialog
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
    onOpenNote: (NoteMeta) -> Unit,
    onOpenNotebook: (String?) -> Unit,
    onSetSortOrder: (SortOrder) -> Unit,
    onCreateNotebook: (String) -> Unit,
    onRenameNotebook: (Notebook, String) -> Unit,
    onDeleteNotebook: (Notebook) -> Unit,
    onMoveNote: (NoteMeta, String?) -> Unit,
    onOpenGraph: () -> Unit,
) {
    var dialog by remember { mutableStateOf<GalleryDialog?>(null) }
    val openNotebook = state.notebooks.find { it.id == state.openNotebookId }
    val notes = galleryNotes(state)

    // Gesto atrás dentro de un cuaderno: volver a la raíz, no salir de la app.
    BackHandler(enabled = state.openNotebookId != null) { onOpenNotebook(null) }

    Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
        Column(modifier = Modifier.fillMaxSize()) {
            GalleryHeader(
                openNotebook = openNotebook,
                sortOrder = state.sortOrder,
                onBack = { onOpenNotebook(null) },
                onSetSortOrder = onSetSortOrder,
                onNewNotebook = { dialog = GalleryDialog.NewNotebook },
                onOpenGraph = onOpenGraph,
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
                                noteCount = state.notes.count { it.notebookId == notebook.id },
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
                            onOpen = { onOpenNote(note) },
                            onLongPress = { dialog = GalleryDialog.MoveNote(note) },
                        )
                    }
                }
            }
        }

        NewNoteFab(
            onClick = onNewNote,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
        )

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
        is GalleryDialog.MoveNote -> MoveNoteDialog(
            note = current.note,
            notebooks = state.notebooks,
            onMove = { notebookId ->
                onMoveNote(current.note, notebookId)
                dialog = null
            },
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 8.dp)
            .border(1.dp, Color(0xFFCCCCCC), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = query,
            onValueChange = onSetQuery,
            textStyle = TextStyle(color = Color.Black, fontSize = 16.sp),
            singleLine = true,
            decorationBox = { innerTextField ->
                Box {
                    if (query.isEmpty()) {
                        BasicText(
                            text = "Buscar por título o contenido…",
                            style = TextStyle(color = Color(0xFF999999), fontSize = 16.sp),
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
                style = TextStyle(color = Color(0xFF888888), fontSize = 16.sp),
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (openNotebook != null) {
            BasicText(
                text = "←",
                style = TextStyle(color = Color.Black, fontSize = 24.sp),
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        BasicText(
            text = openNotebook?.name ?: "Turmalin",
            style = TextStyle(
                color = Color.Black,
                fontSize = 24.sp,
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
            HeaderButton(label = "Grafo", onClick = onOpenGraph)
            HeaderButton(label = "+ Cuaderno", onClick = onNewNotebook)
        }
    }
}

private val sortLabels = mapOf(
    SortOrder.MODIFIED to "Fecha",
    SortOrder.TITLE to "Título",
    SortOrder.NOTEBOOK to "Cuaderno",
)

/** Selector de orden (RF-15) con Popup de foundation — sin Material. */
@Composable
private fun SortMenu(
    sortOrder: SortOrder,
    onSetSortOrder: (SortOrder) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        HeaderButton(
            label = "Orden: ${sortLabels.getValue(sortOrder)} ▾",
            onClick = { expanded = true },
            filled = false,
        )
        if (expanded) {
            Popup(onDismissRequest = { expanded = false }) {
                Column(
                    modifier = Modifier
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(8.dp)),
                ) {
                    SortOrder.entries.forEach { order ->
                        BasicText(
                            text = sortLabels.getValue(order),
                            style = TextStyle(
                                color = Color.Black,
                                fontSize = 16.sp,
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

@Composable
private fun HeaderButton(label: String, onClick: () -> Unit, filled: Boolean = true) {
    val shape = RoundedCornerShape(8.dp)
    BasicText(
        text = label,
        style = TextStyle(
            color = if (filled) Color.White else Color.Black,
            fontSize = 15.sp,
        ),
        modifier = Modifier
            .padding(start = 8.dp)
            .background(if (filled) Color.Black else Color.White, shape)
            .border(1.dp, if (filled) Color.Black else Color(0xFFCCCCCC), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotebookCard(
    notebook: Notebook,
    noteCount: Int,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .background(Color(0xFFF2F2F2), shape)
            .border(1.dp, Color(0xFFE0E0E0), shape)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(16.dp),
    ) {
        BasicText(
            text = "🗂 ${notebook.name}",
            style = TextStyle(
                color = Color.Black,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(6.dp))
        BasicText(
            text = if (noteCount == 1) "1 nota" else "$noteCount notas",
            style = TextStyle(color = Color(0xFF888888), fontSize = 13.sp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    note: NoteMeta,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val dateFormat = remember {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .background(Color.White, shape)
            .border(1.dp, Color(0xFFE0E0E0), shape)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(16.dp),
    ) {
        BasicText(
            text = note.title,
            style = TextStyle(
                color = Color.Black,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(6.dp))
        BasicText(
            text = dateFormat.format(Date(note.modifiedAtMillis)),
            style = TextStyle(color = Color(0xFF888888), fontSize = 13.sp),
        )
    }
}

/** Botón flotante de nueva nota (UC-01). */
@Composable
private fun NewNoteFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(60.dp)
            .background(Color.Black, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = "+",
            style = TextStyle(color = Color.White, fontSize = 30.sp),
        )
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
            style = TextStyle(color = Color(0xFF888888), fontSize = 16.sp),
        )
    }
}

// --- Diálogos (compose.ui.window.Dialog, sin Material) ---

@Composable
private fun DialogSurface(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(24.dp),
        content = content,
    )
}

@Composable
private fun DialogTitle(text: String) {
    BasicText(
        text = text,
        style = TextStyle(color = Color.Black, fontSize = 19.sp, fontWeight = FontWeight.Bold),
    )
    Spacer(modifier = Modifier.height(16.dp))
}

@Composable
private fun DialogOption(label: String, onClick: () -> Unit, danger: Boolean = false) {
    BasicText(
        text = label,
        style = TextStyle(
            color = if (danger) Color(0xFFB00020) else Color.Black,
            fontSize = 16.sp,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

/** Diálogo genérico de un campo de texto (crear/renombrar cuaderno, RF-13). */
@Composable
private fun TextInputDialog(
    title: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initialValue: String = "",
) {
    var value by remember { mutableStateOf(initialValue) }
    Dialog(onDismissRequest = onDismiss) {
        DialogSurface {
            DialogTitle(title)
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
                HeaderButton(label = "Cancelar", onClick = onDismiss, filled = false)
                if (value.isNotBlank()) {
                    HeaderButton(label = confirmLabel, onClick = { onConfirm(value.trim()) })
                }
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

/** Mover una nota entre cuadernos (RF-14). */
@Composable
private fun MoveNoteDialog(
    note: NoteMeta,
    notebooks: List<Notebook>,
    onMove: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        DialogSurface {
            DialogTitle("Mover «${note.title}» a…")
            if (note.notebookId != null) {
                DialogOption(label = "Raíz del vault", onClick = { onMove(null) })
            }
            val destinations = notebooks.filterNot { it.id == note.notebookId }
            destinations.forEach { notebook ->
                DialogOption(label = notebook.name, onClick = { onMove(notebook.id) })
            }
            if (destinations.isEmpty() && note.notebookId == null) {
                BasicText(
                    text = "No hay cuadernos todavía",
                    style = TextStyle(color = Color(0xFF888888), fontSize = 15.sp),
                )
            }
        }
    }
}
