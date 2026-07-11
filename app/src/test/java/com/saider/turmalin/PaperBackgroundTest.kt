package com.saider.turmalin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Alineación de las guías del fondo de página (RF-06) bajo pan/zoom. Mismo
 * riesgo que motivó los tests del viewport: fondo y tinta deben usar la
 * transformación doc→pantalla idéntica (`pantalla = doc·scale + offset`) o el
 * papel "flota" respecto a los trazos.
 */
class PaperBackgroundTest {

    private val eps = 1e-3f

    @Test
    fun `sin pan ni zoom, las guias caen en multiplos del espaciado`() {
        val lines = paperLinePositions(offset = 0f, scale = 1f, spacing = 40f, extent = 100f)
        assertEquals(listOf(0f, 40f, 80f), lines)
    }

    @Test
    fun `cada guia coincide con la proyeccion de su linea de documento`() {
        // pantalla = doc·scale + offset. La primera guía visible debe ser una
        // línea de documento entera (k·spacing) proyectada, sin corrimiento.
        val offset = 37f
        val scale = 1.5f
        val spacing = 40f
        val lines = paperLinePositions(offset, scale, spacing, extent = 400f)
        for (screen in lines) {
            val doc = (screen - offset) / scale
            val k = Math.round(doc / spacing)
            assertEquals("guía en pantalla=$screen no es múltiplo de doc·spacing",
                k * spacing, doc, eps)
        }
        assertTrue(lines.isNotEmpty())
    }

    @Test
    fun `con offset negativo (pan) la primera guia sigue dentro del lienzo`() {
        val lines = paperLinePositions(offset = -95f, scale = 1f, spacing = 40f, extent = 200f)
        assertTrue(lines.all { it in 0f..200f })
        // primera guía = -95 + ceil(95/40)·40 = -95 + 120 = 25
        assertEquals(25f, lines.first(), eps)
    }

    @Test
    fun `zoom muy bajo (guias demasiado juntas) no dibuja nada`() {
        // spacing·scale < 6px ⇒ vacío, evita un mar de líneas ilegible.
        assertTrue(paperLinePositions(offset = 0f, scale = 0.1f, spacing = 40f, extent = 500f).isEmpty())
    }

    // --- Tamaño de página (RF-06a) ---

    @Test
    fun `Carta es el default y se reconoce como preset`() {
        assertEquals(PAGE_SIZE_CARTA, DEFAULT_PAGE_SIZE)
        assertEquals("Carta", presetNameFor(PAGE_SIZE_CARTA))
        assertEquals("A4", presetNameFor(PAGE_SIZE_A4))
    }

    @Test
    fun `un tamano fuera de los presets no matchea ninguno (personalizado)`() {
        assertEquals(null, presetNameFor(PageSize(200f, 250f)))
    }

    @Test
    fun `los presets convierten a los puntos PDF estandar (RF-28)`() {
        // Referencias del spec (Carta/Legal caen 1pt del "oficial" porque los
        // presets usan 279/356mm, no 279.4/355.6mm — dentro de aproximado).
        assertEquals(612 to 791, PAGE_SIZE_CARTA.toPointsPt())
        assertEquals(595 to 842, PAGE_SIZE_A4.toPointsPt())
        assertEquals(612 to 1009, PAGE_SIZE_LEGAL.toPointsPt())
        assertEquals(612 to 964, PAGE_SIZE_OFICIO.toPointsPt())
    }

    // --- Orientación por página (RF-06a): paisaje = dimensiones intercambiadas ---

    @Test
    fun `rotated intercambia dimensiones y define la orientacion`() {
        val paisaje = PAGE_SIZE_CARTA.rotated()
        assertEquals(PageSize(279f, 216f), paisaje)
        assertTrue(paisaje.isLandscape)
        assertTrue(!PAGE_SIZE_CARTA.isLandscape)
        assertEquals(PAGE_SIZE_CARTA, paisaje.rotated()) // involutiva
    }

    @Test
    fun `un preset en paisaje sigue reconociendose para resaltar el chip`() {
        assertEquals("Carta", presetNameFor(PAGE_SIZE_CARTA.rotated()))
        assertEquals("A4", presetNameFor(PAGE_SIZE_A4.rotated()))
        assertEquals(null, presetNameFor(PageSize(250f, 200f)))
    }

    @Test
    fun `Carta paisaje convierte a los puntos PDF intercambiados (RF-28)`() {
        assertEquals(791 to 612, PAGE_SIZE_CARTA.rotated().toPointsPt())
    }

    @Test
    fun `coercePageMm acota el rango y cae al fallback si no parsea`() {
        assertEquals(PAGE_MM_RANGE.endInclusive, coercePageMm("99999", 216f), eps)
        assertEquals(PAGE_MM_RANGE.start, coercePageMm("1", 216f), eps)
        assertEquals(216f, coercePageMm("abc", 216f), eps) // no parseable ⇒ fallback
        assertEquals(210f, coercePageMm(" 210 ", 216f), eps)
    }

    @Test
    fun `coerceToRange acepta coma decimal y acota al rango dado`() {
        assertEquals(4.5f, coerceToRange("4,5", 1f, PEN_SIZE_RANGE), eps)
        assertEquals(4.5f, coerceToRange("4.5", 1f, PEN_SIZE_RANGE), eps)
        assertEquals(PEN_SIZE_RANGE.endInclusive, coerceToRange("99", 1f, PEN_SIZE_RANGE), eps)
        assertEquals(3f, coerceToRange("", 3f, PEN_SIZE_RANGE), eps) // vacío ⇒ fallback
    }
}
