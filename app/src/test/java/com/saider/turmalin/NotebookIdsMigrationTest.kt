package com.saider.turmalin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Migración de lectura de cuadernos (v2 4.4): meta.json pasa de un cuaderno
 * único (`notebookId`, string) a multi-pertenencia (`notebookIds`, array). Una
 * nota vieja debe conservar su cuaderno como lista de un elemento, sin paso de
 * migración de archivos.
 */
class NotebookIdsMigrationTest {

    @Test
    fun `clave nueva presente entrega la lista completa`() {
        val json = JSONObject("""{"notebookIds":["nb-1","nb-2"]}""")
        assertEquals(listOf("nb-1", "nb-2"), resolveNotebookIds(json))
    }

    @Test
    fun `solo legacy string entrega lista de un elemento`() {
        val json = JSONObject("""{"notebookId":"nb-viejo"}""")
        assertEquals(listOf("nb-viejo"), resolveNotebookIds(json))
    }

    @Test
    fun `clave nueva gana sobre la legacy si coexisten`() {
        val json = JSONObject("""{"notebookId":"legacy","notebookIds":["nuevo"]}""")
        assertEquals(listOf("nuevo"), resolveNotebookIds(json))
    }

    @Test
    fun `sin ninguna clave la nota vive en la raiz`() {
        assertEquals(emptyList<String>(), resolveNotebookIds(JSONObject()))
    }

    @Test
    fun `array vacio explicito tambien es raiz`() {
        assertEquals(emptyList<String>(), resolveNotebookIds(JSONObject("""{"notebookIds":[]}""")))
    }
}
