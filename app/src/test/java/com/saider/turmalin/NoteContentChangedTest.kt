package com.saider.turmalin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteContentChangedTest {

    private val original = NoteMeta(
        uuid = "a",
        title = "Original",
        createdAtMillis = 0,
        modifiedAtMillis = 0,
        titleNudgeCount = 0,
        tags = listOf("x"),
    )

    @Test
    fun `abrir sin editar no cuenta como cambio`() {
        assertFalse(
            noteContentChanged(
                original = original,
                newTitle = original.title,
                newTags = original.tags,
                inkChanged = false,
                linksChanged = false,
            )
        )
    }

    @Test
    fun `trazo nuevo o borrado cuenta como cambio`() {
        assertTrue(
            noteContentChanged(
                original = original,
                newTitle = original.title,
                newTags = original.tags,
                inkChanged = true,
                linksChanged = false,
            )
        )
    }

    @Test
    fun `crear o borrar un link cuenta como cambio`() {
        assertTrue(
            noteContentChanged(
                original = original,
                newTitle = original.title,
                newTags = original.tags,
                inkChanged = false,
                linksChanged = true,
            )
        )
    }

    @Test
    fun `titulo editado cuenta como cambio`() {
        assertTrue(
            noteContentChanged(
                original = original,
                newTitle = "Nuevo título",
                newTags = original.tags,
                inkChanged = false,
                linksChanged = false,
            )
        )
    }

    @Test
    fun `tags editados cuentan como cambio`() {
        assertTrue(
            noteContentChanged(
                original = original,
                newTitle = original.title,
                newTags = listOf("y"),
                inkChanged = false,
                linksChanged = false,
            )
        )
    }
}
