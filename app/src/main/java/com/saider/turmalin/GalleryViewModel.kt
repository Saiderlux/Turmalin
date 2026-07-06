package com.saider.turmalin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Criterios de orden de la galería (RF-15). */
enum class SortOrder { MODIFIED, TITLE, NOTEBOOK }

data class GalleryUiState(
    val notes: List<NoteMeta> = emptyList(),
    val notebooks: List<Notebook> = emptyList(),
    // Cuaderno abierto en la galería; null = raíz (cuadernos + notas sueltas).
    val openNotebookId: String? = null,
    val sortOrder: SortOrder = SortOrder.MODIFIED,
    // Búsqueda (RF-26): consulta activa y texto OCR ya indexado por nota.
    // Se busca sobre estos JSON en memoria — jamás OCR al consultar (RF-27).
    val query: String = "",
    val ocrTexts: Map<String, String> = emptyMap(),
)

/**
 * Aviso de título pendiente al cerrar una nota (RF-11), con título sugerido
 * por la primera línea del OCR cuando existe (RF-12, UC-04).
 */
data class TitleNudge(
    val note: NoteMeta,
    val suggestedTitle: String?,
)

/**
 * Notas visibles en la galería según el cuaderno abierto y el orden elegido.
 * Función pura, separada del ViewModel para poder testearla sin Android.
 *
 * Un notebookId huérfano (cuaderno borrado fuera de la app, vault copiado a
 * mano) se trata como raíz: una nota nunca desaparece de la UI.
 */
// Minúsculas y sin diacríticos: en español "excepcion" debe encontrar
// "excepción" (y el OCR a veces coloca tildes donde el usuario no las puso).
fun normalizeForSearch(text: String): String =
    java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")

fun galleryNotes(state: GalleryUiState): List<NoteMeta> {
    val query = normalizeForSearch(state.query.trim())
    val visible = if (query.isNotEmpty()) {
        // Búsqueda global (UC-09): título + tags + texto OCR, ignorando el
        // cuaderno abierto. Consulta solo datos ya en memoria, nunca OCR (RF-27).
        state.notes.filter { note ->
            normalizeForSearch(note.title).contains(query) ||
                note.tags.any { normalizeForSearch(it).contains(query) } ||
                normalizeForSearch(state.ocrTexts[note.uuid].orEmpty()).contains(query)
        }
    } else {
        val knownIds = state.notebooks.map { it.id }.toSet()
        state.notes.filter { note ->
            val effectiveId = note.notebookId?.takeIf { it in knownIds }
            effectiveId == state.openNotebookId
        }
    }
    val nameById = state.notebooks.associate { it.id to it.name }
    return when (state.sortOrder) {
        SortOrder.MODIFIED -> visible.sortedByDescending { it.modifiedAtMillis }
        SortOrder.TITLE -> visible.sortedBy { it.title.lowercase() }
        // Dentro de un cuaderno todas comparten cuaderno: cae al orden por título.
        SortOrder.NOTEBOOK -> visible.sortedWith(
            compareBy<NoteMeta> { nameById[it.notebookId]?.lowercase() ?: "" }
                .thenBy { it.title.lowercase() }
        )
    }
}

/**
 * Estado de la pantalla de inicio (RF-13/14/15). Las operaciones delegan al
 * [NoteRepository] y recargan el estado completo: el vault es local y chico,
 * releer es más simple que mantener caches coherentes.
 */
class GalleryViewModel(
    private val repo: NoteRepository,
    private val ocrIndexer: OcrIndexer = OcrIndexer(),
) : ViewModel() {

    private val _state = MutableStateFlow(GalleryUiState())
    val state: StateFlow<GalleryUiState> = _state

    // Aviso de título pendiente activo (RF-11/RF-12); null = ninguno.
    private val _titleNudge = MutableStateFlow<TitleNudge?>(null)
    val titleNudge: StateFlow<TitleNudge?> = _titleNudge

    init {
        refresh()
        // Pide el modelo de Digital Ink desde ya (descarga única, ver
        // OcrIndexer) para que esté listo antes del primer cierre de nota.
        viewModelScope.launch(Dispatchers.Default) { ocrIndexer.ensureModelAvailable() }
    }

    fun refresh() {
        val notes = repo.listNotes()
        _state.update {
            it.copy(
                notes = notes,
                notebooks = repo.listNotebooks(),
                // ponytail: releer todos los annotations.json en cada refresh;
                // cachear por mtime si el vault crece a cientos de notas.
                ocrTexts = notes.associate { note -> note.uuid to repo.loadOcrText(note.uuid) },
            )
        }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun setSortOrder(order: SortOrder) {
        _state.update { it.copy(sortOrder = order) }
    }

    /**
     * Cierre de nota (UC-04): OCR en background solo si la tinta cambió en la
     * sesión (dirty flag) — abrir a leer o hacer pan/zoom no dispara ML Kit ni
     * reescribe annotations.json. El aviso de título usa el OCR recién
     * calculado, o el ya indexado si no hubo cambios.
     */
    fun onNoteClosed(meta: NoteMeta, inkChanged: Boolean) {
        refresh()
        if (!inkChanged) {
            maybeSuggestTitle(meta)
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            // Modelo aún no descargado: se pospone el indexado a un cierre
            // futuro, sin pisar el índice previo con vacíos.
            if (!ocrIndexer.ensureModelAvailable()) {
                withContext(Dispatchers.Main) { maybeSuggestTitle(meta) }
                return@launch
            }
            val brush = defaultBlackPen()
            val pages = (0 until meta.pageCount).map { page ->
                ocrIndexer.recognizePage(
                    repo.loadStrokes(meta.uuid, page, brush).map { it.stroke }
                )
            }
            repo.saveOcrText(meta.uuid, pages)
            withContext(Dispatchers.Main) {
                refresh()
                maybeSuggestTitle(meta)
            }
        }
    }

    // RF-11: máximo dos avisos por nota; RF-12: sugiere la primera línea OCR.
    private fun maybeSuggestTitle(meta: NoteMeta) {
        if (meta.title != DEFAULT_NOTE_TITLE || meta.titleNudgeCount >= 2) return
        val bumped = meta.copy(titleNudgeCount = meta.titleNudgeCount + 1)
        repo.saveMeta(bumped)
        _titleNudge.value = TitleNudge(
            note = bumped,
            suggestedTitle = firstOcrLine(repo.loadOcrText(meta.uuid)),
        )
        refresh()
    }

    /** Acepta el título sugerido por OCR (RF-12): solo reescribe meta.json. */
    fun applySuggestedTitle(nudge: TitleNudge) {
        val title = nudge.suggestedTitle ?: return
        repo.saveMeta(nudge.note.copy(title = title))
        _titleNudge.value = null
        refresh()
    }

    fun dismissTitleNudge() {
        _titleNudge.value = null
    }

    /** Navega dentro de un cuaderno, o a la raíz con null. */
    fun openNotebook(id: String?) {
        _state.update { it.copy(openNotebookId = id) }
    }

    /** UC-01: crea la nota (en el cuaderno abierto, o raíz) y devuelve su meta. */
    fun createNote(): NoteMeta {
        val meta = repo.createNote(notebookId = _state.value.openNotebookId)
        refresh()
        return meta
    }

    fun createNotebook(name: String) {
        repo.createNotebook(name)
        refresh()
    }

    fun renameNotebook(id: String, name: String) {
        repo.renameNotebook(id, name)
        refresh()
    }

    fun deleteNotebook(id: String) {
        repo.deleteNotebook(id)
        // Si el cuaderno borrado estaba abierto, la galería vuelve a la raíz.
        _state.update { if (it.openNotebookId == id) it.copy(openNotebookId = null) else it }
        refresh()
    }

    fun moveNote(meta: NoteMeta, notebookId: String?) {
        repo.moveNote(meta, notebookId)
        refresh()
    }

    companion object {
        fun factory(repo: NoteRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { GalleryViewModel(repo) }
        }
    }
}
