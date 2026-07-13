package com.saider.turmalin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bridge a Obsidian (v2 6): nombres de archivo y contenido del .md. */
class ObsidianExportTest {

    private fun meta(title: String, tags: List<String> = emptyList()) = NoteMeta(
        uuid = "abcdef12-3456-7890-aaaa-bbbbccccdddd",
        title = title,
        createdAtMillis = 0L,
        modifiedAtMillis = 0L,
        titleNudgeCount = 0,
        tags = tags,
    )

    @Test
    fun `el nombre sanea caracteres invalidos y de wikilink`() {
        assertEquals(
            "a_b_c_d_e_f",
            obsidianBaseName("a/b[c]d#e|f", "uuid-1234", emptySet()),
        )
    }

    @Test
    fun `titulos repetidos se desambiguan con el uuid corto`() {
        assertEquals("Sin título", obsidianBaseName("Sin título", "11112222-x", emptySet()))
        assertEquals(
            "Sin título 33334444",
            obsidianBaseName("Sin título", "33334444-y", setOf("Sin título")),
        )
    }

    @Test
    fun `el markdown lleva frontmatter, ocr, vinculos y pdf`() {
        val md = buildObsidianMarkdown(
            meta = meta("Mi nota", tags = listOf("uni", "señales")),
            ocrText = "texto reconocido",
            outgoingTitles = listOf("Otra nota"),
            pdfBaseName = "Mi nota",
        )
        assertTrue(md.startsWith("---\n"))
        assertTrue("uuid: abcdef12" in md)
        assertTrue("tags: [uni, señales]" in md)
        assertTrue("texto reconocido" in md)
        assertTrue("- [[Otra nota]]" in md)
        assertTrue("![[Mi nota.pdf]]" in md)
    }

    @Test
    fun `sin links ni pdf no aparecen esas secciones`() {
        val md = buildObsidianMarkdown(
            meta = meta("Suelta"),
            ocrText = "",
            outgoingTitles = emptyList(),
            pdfBaseName = null,
        )
        assertFalse("## Vínculos" in md)
        assertFalse("## Original" in md)
        assertFalse("tags:" in md)
    }
}
