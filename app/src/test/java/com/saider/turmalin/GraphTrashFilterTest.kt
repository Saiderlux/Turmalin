package com.saider.turmalin

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ocultar-sin-borrar de aristas con notas en papelera (RF-36 sobre RF-18/19/23b):
 * el ciclo eliminar → restaurar → vaciar papelera a nivel de la función pura
 * que comparten la vista de grafo, el badge de referencias y la purga.
 */
class GraphTrashFilterTest {

    // A→B y C→A: B tiene un entrante, A tiene uno entrante y uno saliente.
    private val graph = mapOf("A" to listOf("B"), "C" to listOf("A"))

    private fun backlinksOf(uuid: String, active: Set<String>): List<String> =
        filterGraphNodes(graph) { it in active }.filter { uuid in it.value }.keys.toList()

    @Test
    fun `eliminar A oculta sus aristas entrantes y salientes sin tocar el grafo`() {
        val visible = filterGraphNodes(graph) { it in setOf("B", "C") }
        // Ninguna arista sobrevive: ambas tocaban a A.
        assertEquals(emptyMap<String, List<String>>(), visible)
        // El badge de B ya no cuenta el entrante desde A.
        assertEquals(emptyList<String>(), backlinksOf("B", setOf("B", "C")))
    }

    @Test
    fun `restaurar A trae de vuelta aristas y contadores intactos`() {
        // El grafo crudo nunca cambió: con A activa de nuevo, todo reaparece.
        val visible = filterGraphNodes(graph) { it in setOf("A", "B", "C") }
        assertEquals(graph, visible)
        assertEquals(listOf("A"), backlinksOf("B", setOf("A", "B", "C")))
        assertEquals(listOf("C"), backlinksOf("A", setOf("A", "B", "C")))
    }

    @Test
    fun `vaciar papelera purga las entradas que referencian la nota eliminada`() {
        val purged = filterGraphNodes(graph) { it !in setOf("A") }
        // La clave A desaparece y C pierde su única arista (apuntaba a A).
        assertEquals(emptyMap<String, List<String>>(), purged)
    }

    @Test
    fun `las aristas entre notas activas no se ven afectadas`() {
        val withExtra = graph + ("B" to listOf("C"))
        val visible = filterGraphNodes(withExtra) { it in setOf("B", "C") }
        assertEquals(mapOf("B" to listOf("C")), visible)
    }
}
