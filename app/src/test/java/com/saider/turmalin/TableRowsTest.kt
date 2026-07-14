package com.saider.turmalin

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tabla agrupada por carpetas (post-v2): agrupación, colapso y sangría. */
class TableRowsTest {

    private fun note(uuid: String, notebookIds: List<String> = emptyList()) = NoteMeta(
        uuid = uuid,
        title = uuid,
        createdAtMillis = 0,
        modifiedAtMillis = 0,
        titleNudgeCount = 0,
        notebookIds = notebookIds,
    )

    private val work = Notebook(id = "nb-work", name = "Trabajo")
    private val ideas = Notebook(id = "nb-ideas", name = "Ideas")

    @Test
    fun `carpetas alfabeticas colapsadas y sueltas al final`() {
        val rows = tableRows(
            notes = listOf(note("a", listOf(work.id)), note("b")),
            notebooks = listOf(work, ideas),
            expandedIds = emptySet(),
        )
        assertEquals(
            listOf("folder-nb-ideas", "folder-nb-work", "b"),
            rows.map { it.key },
        )
        assertEquals(1, (rows[1] as TableRow.Folder).noteCount)
    }

    @Test
    fun `expandir intercala las notas indentadas bajo su carpeta`() {
        val rows = tableRows(
            notes = listOf(note("a", listOf(work.id)), note("b")),
            notebooks = listOf(work),
            expandedIds = setOf(work.id),
        )
        assertEquals(listOf("folder-nb-work", "nb-work/a", "b"), rows.map { it.key })
        assertEquals(true, (rows[1] as TableRow.Note).indented)
        assertEquals(false, (rows[2] as TableRow.Note).indented)
    }

    @Test
    fun `multi-pertenencia aparece bajo cada carpeta con clave unica`() {
        val rows = tableRows(
            notes = listOf(note("a", listOf(work.id, ideas.id))),
            notebooks = listOf(work, ideas),
            expandedIds = setOf(work.id, ideas.id),
        )
        assertEquals(
            listOf("folder-nb-ideas", "nb-ideas/a", "folder-nb-work", "nb-work/a"),
            rows.map { it.key },
        )
    }

    @Test
    fun `las notas dentro conservan el orden recibido`() {
        val rows = tableRows(
            notes = listOf(note("z", listOf(work.id)), note("a", listOf(work.id))),
            notebooks = listOf(work),
            expandedIds = setOf(work.id),
        )
        assertEquals(listOf("folder-nb-work", "nb-work/z", "nb-work/a"), rows.map { it.key })
    }
}
