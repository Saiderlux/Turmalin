package com.saider.turmalin

import org.junit.Assert.assertEquals
import org.junit.Test

class TagChipInputTest {

    @Test
    fun `confirma una palabra nueva como tag`() {
        assertEquals(listOf("trabajo"), addTag(emptyList(), "trabajo"))
    }

    @Test
    fun `recorta espacios antes de agregar`() {
        assertEquals(listOf("a", "b"), addTag(listOf("a"), "  b  "))
    }

    @Test
    fun `entrada en blanco no agrega tag`() {
        assertEquals(listOf("a"), addTag(listOf("a"), "   "))
    }

    @Test
    fun `no duplica un tag ya existente`() {
        assertEquals(listOf("a", "b"), addTag(listOf("a", "b"), "a"))
    }
}
