package com.saider.turmalin

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Parser de plantillas de papel (v2 2.3): roundtrip del JSON de templates.json
 * y resiliencia ante entradas malformadas (RNF-07).
 */
class TemplatesParsingTest {

    @Test
    fun `parsea una plantilla completa`() {
        val json = """[{"id":"t1","name":"Apuntes","paperStyle":"DOTS",
            "paperSpacing":32.0,"pageWidthMm":210.0,"pageHeightMm":297.0}]"""
        assertEquals(
            listOf(
                NoteTemplate(
                    id = "t1",
                    name = "Apuntes",
                    paper = PaperBackground(style = PaperStyle.DOTS, spacing = 32f),
                    pageSize = PAGE_SIZE_A4,
                )
            ),
            parseTemplates(json),
        )
    }

    @Test
    fun `entrada malformada se ignora sin descartar las validas`() {
        val json = """[{"sin":"campos"},{"id":"t2","name":"Simple"}]"""
        val parsed = parseTemplates(json)
        assertEquals(1, parsed.size)
        // Campos ausentes caen a los defaults de siempre (blanco + Carta).
        assertEquals("Simple", parsed[0].name)
        assertEquals(PaperBackground(), parsed[0].paper)
        assertEquals(DEFAULT_PAGE_SIZE, parsed[0].pageSize)
    }

    @Test
    fun `texto corrupto entrega lista vacia`() {
        assertEquals(emptyList<NoteTemplate>(), parseTemplates("no es json"))
    }
}
