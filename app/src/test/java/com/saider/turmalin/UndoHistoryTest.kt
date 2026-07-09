package com.saider.turmalin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Historial de ink (RF-37): instantáneas, límite de profundidad y limpieza de redo. */
class UndoHistoryTest {

    @Test
    fun `undo y redo restauran las instantáneas en orden`() {
        val h = UndoHistory<String>()
        h.commit("v0") // antes de escribir v1
        h.commit("v1") // antes de escribir v2 (estado vigente: v2)
        assertEquals("v1", h.undo("v2"))
        assertEquals("v0", h.undo("v1"))
        assertNull(h.undo("v0"))
        assertEquals("v1", h.redo("v0"))
        assertEquals("v2", h.redo("v1"))
        assertNull(h.redo("v2"))
    }

    @Test
    fun `una acción nueva tras deshacer limpia la pila de rehacer`() {
        val h = UndoHistory<String>()
        h.commit("v0")
        h.undo("v1")
        assertTrue(h.canRedo)
        h.commit("v0-bis")
        assertFalse(h.canRedo)
        assertNull(h.redo("x"))
    }

    @Test
    fun `el límite de profundidad descarta el paso más antiguo`() {
        val h = UndoHistory<Int>(limit = 3)
        (0 until 5).forEach { h.commit(it) }
        assertEquals(4, h.undo(99))
        assertEquals(3, h.undo(4))
        assertEquals(2, h.undo(3))
        assertNull(h.undo(2))
    }

    @Test
    fun `clear reinicia ambas pilas y los flags`() {
        val h = UndoHistory<String>()
        h.commit("a")
        h.undo("b")
        h.clear()
        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
    }
}
