package com.saider.turmalin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Migración de lectura del tamaño de página (RF-06a v2): meta.json pasa de un
 * tamaño uniforme por nota (`pageWidthMm`/`pageHeightMm`) a uno por página
 * (`pageSizesMm`). Una nota vieja debe conservar exactamente el tamaño que ya
 * funcionaba, replicado en todas sus páginas, sin paso de migración de archivos.
 */
class PageSizesMigrationTest {

    @Test
    fun `clave nueva presente entrega la lista por pagina`() {
        val json = JSONObject("""{"pageSizesMm":[[216,279],[297,210],[100,150]]}""")
        assertEquals(
            listOf(PageSize(216f, 279f), PageSize(297f, 210f), PageSize(100f, 150f)),
            resolvePageSizes(json, pageCount = 3),
        )
    }

    @Test
    fun `solo legacy uniforme replica ese tamano en todas las paginas`() {
        val json = JSONObject("""{"pageWidthMm":210,"pageHeightMm":297,"pageCount":3}""")
        assertEquals(List(3) { PAGE_SIZE_A4 }, resolvePageSizes(json, pageCount = 3))
    }

    @Test
    fun `sin ninguna configuracion todas las paginas asumen Carta retrato`() {
        assertEquals(List(2) { PAGE_SIZE_CARTA }, resolvePageSizes(JSONObject(), pageCount = 2))
    }

    @Test
    fun `lista mas corta que pageCount cae a Carta via pageSizeOf`() {
        val meta = NoteMeta(
            uuid = "u",
            title = "t",
            createdAtMillis = 0L,
            modifiedAtMillis = 0L,
            titleNudgeCount = 0,
            pageCount = 3,
            pageSizes = listOf(PAGE_SIZE_A4.rotated()),
        )
        assertEquals(PAGE_SIZE_A4.rotated(), meta.pageSizeOf(0))
        assertEquals(PAGE_SIZE_CARTA, meta.pageSizeOf(1))
        assertEquals(PAGE_SIZE_CARTA, meta.pageSizeOf(2))
    }
}
