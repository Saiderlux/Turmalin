package com.saider.turmalin

import kotlin.math.sqrt
import kotlin.random.Random

// --- Constantes de físicas (perillas de calibración; ajustar en hardware) ---
// Distancia ideal entre nodos (px de mundo). Fija —no depende de la pantalla—
// para que el grafo ocupe un área proporcional a la cantidad de notas: plano
// infinito, se navega con pan/zoom (como Obsidian).
private const val IDEAL_DISTANCE = 170f

// Gravedad hacia el centro: da cohesión y acota los huérfanos sin encerrarlos
// (equilibran repulsión vs. centrado a un radio estable, no en una caja). Más
// alta ⇒ los huérfanos (que solo tienen repulsión, sin resortes) no se van tan
// lejos y el grafo queda más compacto.
private const val CENTER_GRAVITY = 0.22f

// Integración velocidad-amortiguación (mata la vibración, converge suave).
// Cerca de amortiguación crítica: converge rápido sin oscilar.
private const val TIMESTEP = 0.07f
private const val DAMPING = 0.8f
private const val MAX_SPEED = 60f // tope px/frame: evita la explosión inicial

// Enfriamiento tipo d3/Obsidian: alpha escala TODAS las fuerzas y decae hasta
// [ALPHA_MIN]. Garantiza que el grafo se congele en un nº fijo de frames sea
// cual sea la topología (la amortiguación sola no basta: un cúmulo de huérfanos
// que se repelen mutuamente nunca baja de un piso de energía y "vibra" para
// siempre). ~0.965 ⇒ ~110 frames (~1.8 s) hasta congelar; arrastrar lo reaviva.
private const val ALPHA_DECAY = 0.965f
private const val ALPHA_MIN = 0.02f

// Piso de distancia para la repulsión: acota k²/d cuando dos nodos casi se
// tocan (evita fuerzas enormes que desestabilizan la integración).
private const val MIN_REPULSION_DIST = 40f

// Radio de la semilla ≈ tamaño de equilibrio del grafo (radio ~ k·√n): se
// siembra ya casi desplegado para no arrastrar una explosión larga.
private const val SEED_SPREAD_FACTOR = 0.5f

// Impulso de velocidad del botón Animate (mismo orden que MAX_SPEED, visible
// pero sin desbaratar el layout).
private const val KICK_IMPULSE = 50f

/**
 * Simulación de grafo dirigida por fuerzas (RF-19..22), estilo Obsidian: corre
 * en vivo un paso por frame ([step]) sobre un plano infinito. Repulsión k²/d
 * entre todos los pares —la que separa nodos y aristas para que no se encimen—,
 * atracción d²/k por arista y gravedad suave al centro. La integración usa
 * velocidad con amortiguación: la energía se disipa y los nodos frenan sin
 * oscilar (sin ella, Euler explícito sobrepasa el equilibrio y vibra). Un
 * factor de enfriamiento (alpha) escala las fuerzas y decae a cero, así que el
 * grafo se congela en un nº fijo de frames sea cual sea la topología.
 *
 * Posiciones sembradas por el hash de cada uuid: mismo vault, mismo dibujo, sin
 * persistir nada. Clase pura (sin Android), testeable paso a paso.
 * ponytail: O(n²) por frame; barnes-hut si el vault pasa de ~500 notas.
 */
class GraphSimulation(
    private val ids: List<String>,
    edges: List<Pair<String, String>>,
    private val centerX: Float,
    private val centerY: Float,
) {
    private val n = ids.size
    private val index = HashMap<String, Int>(n).apply {
        ids.forEachIndexed { i, id -> put(id, i) }
    }
    private val x = FloatArray(n)
    private val y = FloatArray(n)
    private val vx = FloatArray(n)
    private val vy = FloatArray(n)
    private var edgeIdx: List<IntArray> = emptyList()

    // Perillas de calibración, expuestas al panel del grafo (GraphSettings). Los
    // defaults igualan las constantes históricas. `idealDistance` (k) fija la
    // distancia de equilibrio; los multiplicadores escalan cada fuerza en step().
    var idealDistance = IDEAL_DISTANCE
    var centerGravity = CENTER_GRAVITY
    var repulsionStrength = 1f
    var linkStrength = 1f

    // Nodo fijado por el arrastre: las fuerzas no lo mueven, pero sí
    // ejerce repulsión y tensión sobre el resto — los vecinos lo siguen.
    private var pinned = -1

    // Factor de enfriamiento: escala las fuerzas y decae a 0. Arranca en 1
    // (caliente) para desplegar el grafo.
    private var alpha = 1f

    init {
        // Semilla determinista, ya cerca del tamaño de equilibrio (radio ~ k·√n):
        // mismo vault, mismo dibujo; las fuerzas solo afinan y da un asentamiento
        // breve en vez de una explosión larga desde el centro.
        val spread = SEED_SPREAD_FACTOR * idealDistance * sqrt(n.toFloat())
        for (i in 0 until n) {
            val rnd = Random(ids[i].hashCode())
            x[i] = centerX + spread * (rnd.nextFloat() - 0.5f)
            y[i] = centerY + spread * (rnd.nextFloat() - 0.5f)
        }
        setEdges(edges)
    }

    /** El grafo se enfrió (alpha al mínimo) y no hay arrastre: el loop puede parar. */
    val settled: Boolean get() = n == 0 || (pinned < 0 && alpha <= ALPHA_MIN)

    /** Reaviva la simulación (apertura, cambio de aristas, arrastre). */
    fun reheat() { alpha = 1f }

    /**
     * Botón Animate: un grafo ya asentado tiene las fuerzas en equilibrio, así
     * que [reheat] solo (alpha=1 sin tocar posiciones) no movería nada visible.
     * Se suma un impulso aleatorio a la velocidad de los nodos no fijados para
     * que el reacomodo se note.
     */
    fun kick() {
        alpha = 1f
        for (i in 0 until n) {
            if (i == pinned) continue
            vx[i] += (Random.nextFloat() - 0.5f) * KICK_IMPULSE
            vy[i] += (Random.nextFloat() - 0.5f) * KICK_IMPULSE
        }
    }

    /** Reemplaza las aristas (al crear/borrar un link); ignora uuids ajenos. */
    fun setEdges(edges: List<Pair<String, String>>) {
        edgeIdx = edges.mapNotNull { (a, b) ->
            val i = index[a] ?: return@mapNotNull null
            val j = index[b] ?: return@mapNotNull null
            if (i == j) null else intArrayOf(i, j)
        }
    }

    fun pin(id: String) { pinned = index[id] ?: -1 }
    fun unpin() { pinned = -1 }

    fun setPosition(id: String, px: Float, py: Float) {
        val i = index[id] ?: return
        x[i] = px
        y[i] = py
        vx[i] = 0f
        vy[i] = 0f
    }

    fun xOf(id: String): Float = index[id]?.let { x[it] } ?: 0f
    fun yOf(id: String): Float = index[id]?.let { y[it] } ?: 0f

    /**
     * Un paso de simulación. Devuelve la energía cinética media del frame
     * (para observar la convergencia en tests).
     */
    fun step(): Float {
        if (n == 0) return 0f
        val dispX = FloatArray(n)
        val dispY = FloatArray(n)
        val k = idealDistance

        // Repulsión entre todos los pares (k²/d): empuja nodos —y sus aristas—
        // a separarse; es lo que evita que las aristas se enciman. La distancia
        // efectiva tiene piso para no disparar fuerzas enormes al casi tocarse.
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                var dx = x[i] - x[j]
                var dy = y[i] - y[j]
                if (dx == 0f && dy == 0f) { dx = 0.1f * (i - j); dy = 0.1f }
                val dist = sqrt(dx * dx + dy * dy)
                val eff = if (dist < MIN_REPULSION_DIST) MIN_REPULSION_DIST else dist
                val force = k * k / eff * repulsionStrength
                val fx = dx / dist * force
                val fy = dy / dist * force
                dispX[i] += fx; dispY[i] += fy
                dispX[j] -= fx; dispY[j] -= fy
            }
        }
        // Atracción por arista (d²/k).
        for (e in edgeIdx) {
            val i = e[0]
            val j = e[1]
            val dx = x[i] - x[j]
            val dy = y[i] - y[j]
            val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(0.01f)
            val force = dist * dist / k * linkStrength
            val fx = dx / dist * force
            val fy = dy / dist * force
            dispX[i] -= fx; dispY[i] -= fy
            dispX[j] += fx; dispY[j] += fy
        }
        // Gravedad al centro: cohesión y freno de huérfanos, sin caja rígida.
        for (i in 0 until n) {
            dispX[i] += (centerX - x[i]) * centerGravity
            dispY[i] += (centerY - y[i]) * centerGravity
        }

        // Integración velocidad + amortiguación (sin clamp: plano infinito). Las
        // fuerzas se escalan por alpha; al enfriarse, la velocidad se amortigua a
        // cero y el grafo se congela pase lo que pase con la topología.
        var ke = 0f
        for (i in 0 until n) {
            if (i == pinned) { vx[i] = 0f; vy[i] = 0f; continue }
            var nvx = (vx[i] + dispX[i] * TIMESTEP * alpha) * DAMPING
            var nvy = (vy[i] + dispY[i] * TIMESTEP * alpha) * DAMPING
            val speed = sqrt(nvx * nvx + nvy * nvy)
            if (speed > MAX_SPEED) {
                val s = MAX_SPEED / speed
                nvx *= s; nvy *= s
            }
            vx[i] = nvx
            vy[i] = nvy
            x[i] += nvx
            y[i] += nvy
            ke += nvx * nvx + nvy * nvy
        }
        // El enfriamiento solo avanza cuando no se arrastra (fijar reaviva el follow).
        if (pinned < 0) alpha *= ALPHA_DECAY
        return ke / n
    }
}
