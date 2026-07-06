package com.saider.turmalin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrSearchTest {

    private fun note(uuid: String, title: String = uuid, notebookId: String? = null) =
        NoteMeta(
            uuid = uuid,
            title = title,
            createdAtMillis = 0,
            modifiedAtMillis = 0,
            titleNudgeCount = 0,
            notebookId = notebookId,
        )

    @Test
    fun `busqueda por titulo ignora mayusculas`() {
        val state = GalleryUiState(
            notes = listOf(note("1", title = "Receta de pan"), note("2", title = "Otra")),
            query = "RECETA",
        )
        assertEquals(listOf("1"), galleryNotes(state).map { it.uuid })
    }

    @Test
    fun `busqueda encuentra por texto OCR indexado`() {
        val state = GalleryUiState(
            notes = listOf(note("1"), note("2")),
            ocrTexts = mapOf("1" to "lista de compras\nharina", "2" to "otra cosa"),
            query = "harina",
        )
        assertEquals(listOf("1"), galleryNotes(state).map { it.uuid })
    }

    @Test
    fun `busqueda es global aunque haya un cuaderno abierto`() {
        val notebook = Notebook(id = "nb", name = "Trabajo")
        val state = GalleryUiState(
            notes = listOf(note("suelta", title = "presupuesto")),
            notebooks = listOf(notebook),
            openNotebookId = notebook.id,
            query = "presupuesto",
        )
        assertEquals(listOf("suelta"), galleryNotes(state).map { it.uuid })
    }

    @Test
    fun `consulta en blanco vuelve al filtrado por cuaderno`() {
        val state = GalleryUiState(
            notes = listOf(note("a"), note("b", notebookId = "x")),
            query = "   ",
        )
        assertEquals(listOf("a", "b"), galleryNotes(state).map { it.uuid }.sorted())
    }

    @Test
    fun `busqueda sin tildes encuentra texto con tildes y viceversa`() {
        val state = GalleryUiState(
            notes = listOf(note("1"), note("2", title = "Cancion favorita")),
            ocrTexts = mapOf("1" to "sin excepción"),
            query = "excepcion",
        )
        assertEquals(listOf("1"), galleryNotes(state).map { it.uuid })

        val byTitle = state.copy(query = "canción")
        assertEquals(listOf("2"), galleryNotes(byTitle).map { it.uuid })
    }

    @Test
    fun `firstOcrLine salta lineas vacias y recorta espacios`() {
        assertEquals("Receta de pan", firstOcrLine("\n  \n  Receta de pan  \nharina"))
    }

    @Test
    fun `firstOcrLine sin texto util devuelve null`() {
        assertNull(firstOcrLine("  \n\n  "))
    }
}
