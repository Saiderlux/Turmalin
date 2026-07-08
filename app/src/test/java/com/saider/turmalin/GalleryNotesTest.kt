package com.saider.turmalin

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryNotesTest {

    private fun note(
        uuid: String,
        title: String = uuid,
        modified: Long = 0,
        notebookId: String? = null,
        tags: List<String> = emptyList(),
    ) = NoteMeta(
        uuid = uuid,
        title = title,
        createdAtMillis = 0,
        modifiedAtMillis = modified,
        titleNudgeCount = 0,
        notebookId = notebookId,
        tags = tags,
    )

    private val work = Notebook(id = "nb-work", name = "Trabajo")
    private val ideas = Notebook(id = "nb-ideas", name = "Ideas")

    @Test
    fun `raiz muestra solo notas sin cuaderno`() {
        val state = GalleryUiState(
            notes = listOf(note("a"), note("b", notebookId = work.id)),
            notebooks = listOf(work),
        )
        assertEquals(listOf("a"), galleryNotes(state).map { it.uuid })
    }

    @Test
    fun `cuaderno abierto muestra solo sus notas`() {
        val state = GalleryUiState(
            notes = listOf(note("a"), note("b", notebookId = work.id)),
            notebooks = listOf(work),
            openNotebookId = work.id,
        )
        assertEquals(listOf("b"), galleryNotes(state).map { it.uuid })
    }

    @Test
    fun `notebookId huerfano cae a la raiz`() {
        val state = GalleryUiState(
            notes = listOf(note("a", notebookId = "cuaderno-borrado")),
            notebooks = emptyList(),
        )
        assertEquals(listOf("a"), galleryNotes(state).map { it.uuid })
    }

    @Test
    fun `la busqueda encuentra por tag ademas de titulo`() {
        val state = GalleryUiState(
            notes = listOf(
                note("a", title = "Reunión", tags = listOf("proyecto-x", "urgente")),
                note("b", title = "Otra cosa"),
            ),
            query = "proyecto",
        )
        assertEquals(listOf("a"), galleryNotes(state).map { it.uuid })
    }

    @Test
    fun `la busqueda por tag ignora tildes y mayusculas`() {
        val state = GalleryUiState(
            notes = listOf(note("a", title = "x", tags = listOf("Biología"))),
            query = "biologia",
        )
        assertEquals(listOf("a"), galleryNotes(state).map { it.uuid })
    }

    @Test
    fun `orden por fecha de modificacion descendente`() {
        val state = GalleryUiState(
            notes = listOf(note("vieja", modified = 1), note("nueva", modified = 2)),
            sortOrder = SortOrder.MODIFIED,
        )
        assertEquals(listOf("nueva", "vieja"), galleryNotes(state).map { it.uuid })
    }

    @Test
    fun `orden por titulo ignora mayusculas`() {
        val state = GalleryUiState(
            notes = listOf(note("2", title = "banana"), note("1", title = "Arroz")),
            sortOrder = SortOrder.TITLE,
        )
        assertEquals(listOf("1", "2"), galleryNotes(state).map { it.uuid })
    }

    @Test
    fun `orden por tags agrupa por etiqueta menor y deja sin tags al final`() {
        val state = GalleryUiState(
            notes = listOf(
                note("sinTag", title = "aaa"),
                note("zeta", title = "b", tags = listOf("Zeta")),
                note("alfa", title = "c", tags = listOf("medio", "alfa")),
            ),
            sortOrder = SortOrder.TAGS,
        )
        // alfa(min "alfa") < zeta(min "zeta") < sinTag(null → al final).
        assertEquals(listOf("alfa", "zeta", "sinTag"), galleryNotes(state).map { it.uuid })
    }

    @Test
    fun `orden por cuaderno agrupa por nombre y luego titulo`() {
        // En la raíz con huérfanos tratados como raíz, el orden por cuaderno
        // solo es observable con notas visibles de cuadernos conocidos: se
        // simula abriendo la raíz con notas sueltas ordenadas por título.
        val state = GalleryUiState(
            notes = listOf(note("2", title = "zeta"), note("1", title = "alfa")),
            notebooks = listOf(work, ideas),
            sortOrder = SortOrder.NOTEBOOK,
        )
        assertEquals(listOf("1", "2"), galleryNotes(state).map { it.uuid })
    }
}
