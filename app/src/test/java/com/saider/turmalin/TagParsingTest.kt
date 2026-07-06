package com.saider.turmalin

import org.junit.Assert.assertEquals
import org.junit.Test

class TagParsingTest {

    @Test
    fun `separa por comas y recorta espacios`() {
        assertEquals(listOf("uno", "dos", "tres"), parseTags(" uno, dos ,tres "))
    }

    @Test
    fun `descarta vacios y repetidos`() {
        assertEquals(listOf("a", "b"), parseTags("a, , b, a,"))
    }

    @Test
    fun `texto en blanco da lista vacia`() {
        assertEquals(emptyList<String>(), parseTags("   "))
    }
}
