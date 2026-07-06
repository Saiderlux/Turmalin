package com.saider.turmalin

import androidx.compose.ui.geometry.Offset
import androidx.ink.geometry.ImmutableTriangle
import androidx.ink.geometry.ImmutableVec

// Fracción del área de un trazo que debe caer dentro del lazo para seleccionarlo.
private const val LASSO_COVERAGE_THRESHOLD = 0.5f

// --- Geometría pura (sin Android ni androidx.ink) — testeable en JVM ---

/** Área con signo (shoelace); positiva si el polígono está en orden CCW. */
fun polygonSignedArea(poly: List<Offset>): Float {
    var sum = 0f
    for (i in poly.indices) {
        val a = poly[i]
        val b = poly[(i + 1) % poly.size]
        sum += a.x * b.y - b.x * a.y
    }
    return sum / 2f
}

/** Área (sin signo) del triángulo abc. */
fun triangleArea(a: Offset, b: Offset, c: Offset): Float =
    kotlin.math.abs((b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y)) / 2f

// Producto cruz de (b-a)×(c-a): >0 giro a la izquierda (convexo en CCW).
private fun cross(a: Offset, b: Offset, c: Offset): Float =
    (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

/** ¿El punto p está dentro (o en el borde) del triángulo abc? */
fun pointInTriangle(p: Offset, a: Offset, b: Offset, c: Offset): Boolean {
    val d1 = cross(a, b, p)
    val d2 = cross(b, c, p)
    val d3 = cross(c, a, p)
    val hasNeg = d1 < 0f || d2 < 0f || d3 < 0f
    val hasPos = d1 > 0f || d2 > 0f || d3 > 0f
    // Dentro si todos los signos coinciden (o algún d==0: sobre un borde).
    return !(hasNeg && hasPos)
}

/**
 * Triangulación por recorte de orejas (ear clipping) de un polígono simple.
 * Devuelve triángulos como tripletas de índices sobre [poly]. Los triángulos
 * teselan exactamente el interior sin solaparse, así que sumar coberturas por
 * triángulo da la fracción exacta de un trazo dentro del polígono — clave para
 * la selección del lazo con polígonos cóncavos (fan-triangulation fallaría).
 *
 * Función pura, O(n²) sobre los vértices. Polígono degenerado o auto-intersecado
 * ⇒ se corta y devuelve lo triangulado hasta ese punto (nunca cuelga).
 */
fun triangulatePolygon(poly: List<Offset>): List<Triple<Int, Int, Int>> {
    val n = poly.size
    if (n < 3) return emptyList()
    val idx = (0 until n).toMutableList()
    // Orientar CCW para que "oreja convexa" sea cross > 0.
    if (polygonSignedArea(poly) < 0f) idx.reverse()

    val triangles = mutableListOf<Triple<Int, Int, Int>>()
    var guard = 0
    val maxGuard = n * n
    while (idx.size > 3 && guard++ < maxGuard) {
        var clipped = false
        for (i in idx.indices) {
            val i0 = idx[(i - 1 + idx.size) % idx.size]
            val i1 = idx[i]
            val i2 = idx[(i + 1) % idx.size]
            val a = poly[i0]
            val b = poly[i1]
            val c = poly[i2]
            // Oreja candidata: vértice convexo…
            if (cross(a, b, c) <= 0f) continue
            // …sin ningún otro vértice del polígono dentro del triángulo.
            var containsOther = false
            for (j in idx) {
                if (j == i0 || j == i1 || j == i2) continue
                if (pointInTriangle(poly[j], a, b, c)) {
                    containsOther = true
                    break
                }
            }
            if (containsOther) continue
            triangles.add(Triple(i0, i1, i2))
            idx.removeAt(i)
            clipped = true
            break
        }
        if (!clipped) break // degenerado: se corta para no colgar
    }
    if (idx.size == 3) triangles.add(Triple(idx[0], idx[1], idx[2]))
    return triangles
}

// --- Selección con la malla nativa de androidx.ink (RF-17) ---

/**
 * Trazos cuya fracción de área dentro del lazo alcanza [threshold]. Triangula el
 * lazo y suma `computeCoverage(triángulo)` sobre la malla de cada trazo: la suma
 * es exactamente la fracción del trazo dentro del polígono (los triángulos no se
 * solapan). Pre-filtra por bounding box para no evaluar trazos lejanos.
 *
 * [polygonFlat] es el polígono del lazo en coordenadas de documento, lista plana
 * x0,y0,x1,y1,…
 */
fun strokesInLasso(
    strokes: List<IdStroke>,
    polygonFlat: List<Float>,
    threshold: Float = LASSO_COVERAGE_THRESHOLD,
): List<IdStroke> {
    if (polygonFlat.size < 6) return emptyList()
    val poly = ArrayList<Offset>(polygonFlat.size / 2)
    var i = 0
    while (i + 1 < polygonFlat.size) {
        poly.add(Offset(polygonFlat[i], polygonFlat[i + 1]))
        i += 2
    }
    val tris = triangulatePolygon(poly)
    if (tris.isEmpty()) return emptyList()
    val immTris = tris.map {
        ImmutableTriangle(
            ImmutableVec(poly[it.first].x, poly[it.first].y),
            ImmutableVec(poly[it.second].x, poly[it.second].y),
            ImmutableVec(poly[it.third].x, poly[it.third].y),
        )
    }
    val lxMin = poly.minOf { it.x }
    val lxMax = poly.maxOf { it.x }
    val lyMin = poly.minOf { it.y }
    val lyMax = poly.maxOf { it.y }

    return strokes.filter { item ->
        val box = item.stroke.shape.computeBoundingBox() ?: return@filter false
        // Pre-filtro: si las cajas no se tocan, la cobertura es 0.
        if (box.xMax < lxMin || box.xMin > lxMax ||
            box.yMax < lyMin || box.yMin > lyMax
        ) {
            return@filter false
        }
        var coverage = 0f
        for (t in immTris) {
            coverage += item.stroke.shape.computeCoverage(t)
            if (coverage >= threshold) break
        }
        coverage >= threshold
    }
}

/** Bounding box (unión) de un grupo de trazos: [xMin, yMin, xMax, yMax]. */
fun strokesBoundingBox(strokes: List<IdStroke>): List<Float> {
    var xMin = Float.MAX_VALUE
    var yMin = Float.MAX_VALUE
    var xMax = -Float.MAX_VALUE
    var yMax = -Float.MAX_VALUE
    for (item in strokes) {
        val box = item.stroke.shape.computeBoundingBox() ?: continue
        xMin = minOf(xMin, box.xMin)
        yMin = minOf(yMin, box.yMin)
        xMax = maxOf(xMax, box.xMax)
        yMax = maxOf(yMax, box.yMax)
    }
    if (xMin > xMax) return listOf(0f, 0f, 0f, 0f)
    return listOf(xMin, yMin, xMax, yMax)
}
