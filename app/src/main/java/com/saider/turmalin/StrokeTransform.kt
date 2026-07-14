package com.saider.turmalin

import androidx.ink.brush.Brush
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import kotlin.math.cos
import kotlin.math.sin

/**
 * Transformación de la selección (v2 sección 5): traslación + escala uniforme +
 * rotación alrededor de un pivote (el centro del bbox de la selección al
 * iniciar el gesto). Escala uniforme a propósito: una escala por eje
 * distorsionaría el grosor del pincel de forma irrecuperable.
 *
 * punto' = pivote + R(rotation)·scale·(punto − pivote) + (dx, dy)
 */
data class SelectionTransform(
    val dx: Float = 0f,
    val dy: Float = 0f,
    val scale: Float = 1f,
    val rotation: Float = 0f, // radianes
    val pivotX: Float = 0f,
    val pivotY: Float = 0f,
)

/** Aplica [t] a un punto en coordenadas de documento. */
fun transformPoint(t: SelectionTransform, x: Float, y: Float): Pair<Float, Float> {
    val c = cos(t.rotation)
    val s = sin(t.rotation)
    val px = (x - t.pivotX) * t.scale
    val py = (y - t.pivotY) * t.scale
    return Pair(
        t.pivotX + px * c - py * s + t.dx,
        t.pivotY + px * s + py * c + t.dy,
    )
}

/**
 * Bbox [xMin,yMin,xMax,yMax] transformado: la caja que envuelve las cuatro
 * esquinas transformadas (con rotación deja de estar alineada a los ejes, así
 * que se re-envuelve). Para el recuadro de preview del gesto.
 */
fun transformBbox(t: SelectionTransform, bbox: List<Float>): List<Float> {
    val corners = listOf(
        transformPoint(t, bbox[0], bbox[1]),
        transformPoint(t, bbox[2], bbox[1]),
        transformPoint(t, bbox[2], bbox[3]),
        transformPoint(t, bbox[0], bbox[3]),
    )
    return listOf(
        corners.minOf { it.first },
        corners.minOf { it.second },
        corners.maxOf { it.first },
        corners.maxOf { it.second },
    )
}

/**
 * Reconstruye el trazo con [t] aplicada a cada punto de entrada, conservando
 * su ID (los links y tarjetas que lo referencian siguen vivos) y su pincel con
 * el grosor escalado. Mismo patrón populate→update→add que la goma parcial:
 * el resultado es un trazo normal, serializable a ink.bin sin código especial.
 */
fun transformStroke(item: IdStroke, t: SelectionTransform): IdStroke {
    val inputs = item.stroke.inputs
    val batch = MutableStrokeInputBatch()
    val scratch = StrokeInput()
    for (i in 0 until inputs.size) {
        inputs.populate(i, scratch)
        val (x, y) = transformPoint(t, scratch.x, scratch.y)
        scratch.update(
            x = x,
            y = y,
            elapsedTimeMillis = scratch.elapsedTimeMillis,
            toolType = scratch.toolType,
            strokeUnitLengthCm = scratch.strokeUnitLengthCm,
            pressure = scratch.pressure,
            tiltRadians = scratch.tiltRadians,
            orientationRadians = scratch.orientationRadians,
        )
        batch.add(scratch)
    }
    val old = item.stroke.brush
    val brush = Brush.createWithColorIntArgb(
        family = old.family,
        colorIntArgb = old.colorIntArgb,
        // El grosor acompaña la escala; el piso evita un Brush inválido (size>0).
        size = (old.size * t.scale).coerceAtLeast(0.1f),
        epsilon = old.epsilon,
    )
    return IdStroke(item.id, Stroke(brush, batch))
}
