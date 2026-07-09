package com.saider.turmalin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Historial deshacer/rehacer de la capa de ink (RF-37). Cada paso es una
 * instantánea del estado tal como estaba ANTES de una mutación real; como los
 * trazos son inmutables, la instantánea solo copia referencias. Restaurar la
 * instantánea previa cubre por igual trazo añadido, borrado total y borrado
 * parcial: la instantánea contiene el trazo original completo, no sus
 * fragmentos (RF-05a).
 *
 * Vive solo en memoria durante la sesión de edición — nunca se serializa a
 * disco ni toca ink.bin. Genérico para poder testearse en JVM sin construir
 * Strokes de androidx.ink.
 */
class UndoHistory<T>(private val limit: Int = 100) {
    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()

    // Estado observable: habilita/deshabilita los botones de la barra (RF-37).
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** Registra el estado previo a una mutación nueva; limpia la pila de rehacer. */
    fun commit(before: T) {
        undoStack.addLast(before)
        if (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
        refresh()
    }

    /** Estado a restaurar al deshacer, o null; [current] pasa a la pila de rehacer. */
    fun undo(current: T): T? {
        val previous = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        refresh()
        return previous
    }

    /** Estado a restaurar al rehacer, o null; [current] vuelve a la pila de deshacer. */
    fun redo(current: T): T? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        refresh()
        return next
    }

    /** Reinicio total: al abrir la nota o cambiar de página (RF-37). */
    fun clear() {
        undoStack.clear()
        redoStack.clear()
        refresh()
    }

    private fun refresh() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }
}
