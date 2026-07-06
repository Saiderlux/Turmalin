package com.saider.turmalin

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphLayoutTest {

    private fun dist(sim: GraphSimulation, a: String, b: String): Float =
        hypot(sim.xOf(a) - sim.xOf(b), sim.yOf(a) - sim.yOf(b))

    // Corre la simulación hasta enfriarse (o un tope, por si algo no converge).
    private fun settle(sim: GraphSimulation, max: Int = 2000) {
        repeat(max) { if (sim.settled) return; sim.step() }
    }

    @Test
    fun `sin nodos el paso no rompe`() {
        val sim = GraphSimulation(emptyList(), emptyList(), 500f, 500f)
        assertEquals(0f, sim.step(), 0f)
        assertTrue(sim.settled)
    }

    @Test
    fun `la simulacion es determinista para el mismo vault`() {
        val ids = listOf("a", "b", "c", "d")
        val edges = listOf("a" to "b")
        val s1 = GraphSimulation(ids, edges, 500f, 500f).also { settle(it) }
        val s2 = GraphSimulation(ids, edges, 500f, 500f).also { settle(it) }
        for (id in ids) {
            assertEquals(s1.xOf(id), s2.xOf(id), 1e-3f)
            assertEquals(s1.yOf(id), s2.yOf(id), 1e-3f)
        }
    }

    @Test
    fun `la simulacion converge y se detiene`() {
        val ids = (1..20).map { "nota-$it" }
        val sim = GraphSimulation(ids, listOf("nota-1" to "nota-2"), 500f, 500f)
        settle(sim)
        assertTrue("no convergió (energía alta)", sim.settled)
    }

    @Test
    fun `los nodos conectados terminan mas cerca que los sueltos`() {
        val ids = listOf("a", "b", "c", "d")
        val sim = GraphSimulation(ids, listOf("a" to "b"), 500f, 500f).also { settle(it) }
        val linked = dist(sim, "a", "b")
        val unlinked = dist(sim, "c", "d")
        assertTrue("linkeados=$linked, sueltos=$unlinked", linked < unlinked)
    }

    @Test
    fun `aristas hacia uuids desconocidos se ignoran sin romper`() {
        val sim = GraphSimulation(listOf("a"), listOf("a" to "fantasma"), 50f, 50f)
        settle(sim)
        assertTrue(sim.xOf("a").isFinite())
        assertEquals(0f, sim.xOf("fantasma"), 0f) // desconocido: sin posición
    }

    @Test
    fun `un nodo fijado no se mueve pero el resto sigue vivo`() {
        val ids = listOf("a", "b", "c")
        val sim = GraphSimulation(ids, listOf("a" to "b"), 500f, 500f)
        sim.setPosition("a", 500f, 500f)
        sim.pin("a")
        repeat(50) { sim.step() }
        assertEquals(500f, sim.xOf("a"), 0f)
        assertEquals(500f, sim.yOf("a"), 0f)
        assertTrue("fijado ⇒ nunca se asienta", !sim.settled)
    }

    @Test
    fun `el radio del nodo crece con las conexiones y tiene tope`() {
        assertTrue(nodeRadius(0) < nodeRadius(1))
        assertTrue(nodeRadius(1) < nodeRadius(5))
        assertEquals(nodeRadius(100), nodeRadius(1000), 0f)
    }

    @Test
    fun `con linkStrength en cero la atraccion no acerca los nodos`() {
        val ids = listOf("a", "b")
        val edges = listOf("a" to "b")
        val conAtraccion = GraphSimulation(ids, edges, 500f, 500f)
            .also { settle(it) }.let { dist(it, "a", "b") }
        val sinAtraccion = GraphSimulation(ids, edges, 500f, 500f)
            .apply { linkStrength = 0f }.also { settle(it) }.let { dist(it, "a", "b") }
        assertTrue(
            "con=$conAtraccion, sin=$sinAtraccion",
            sinAtraccion > conAtraccion,
        )
    }

    @Test
    fun `kick reaviva un grafo ya asentado (boton Animate)`() {
        val sim = GraphSimulation(listOf("a", "b", "c"), listOf("a" to "b"), 500f, 500f)
        settle(sim)
        assertTrue(sim.settled)
        sim.kick()
        assertTrue("kick() debe recalentar alpha (reheat solo no basta en equilibrio)", !sim.settled)
    }

    @Test
    fun `mayor idealDistance ⇒ mayor distancia de equilibrio`() {
        val ids = listOf("a", "b")
        val edges = listOf("a" to "b")
        val corta = GraphSimulation(ids, edges, 500f, 500f)
            .apply { idealDistance = 100f }.also { settle(it) }.let { dist(it, "a", "b") }
        val larga = GraphSimulation(ids, edges, 500f, 500f)
            .apply { idealDistance = 320f }.also { settle(it) }.let { dist(it, "a", "b") }
        assertTrue("corta=$corta, larga=$larga", larga > corta)
    }
}
