package com.saider.turmalin

import androidx.ink.strokes.Stroke
import kotlin.random.Random

/**
 * Trazo con identidad estable ([id]). El ID es el puente ink↔anotación: permite
 * que un link en `annotations.json` referencie trazos concretos SIN tocar
 * `ink.bin` (la inmutabilidad de la capa de tinta se mantiene — los IDs viven
 * del lado de anotaciones, alineados por posición al orden de los trazos).
 *
 * El ID se asigna al nacer el trazo y nunca cambia. Cuando la goma parcial parte
 * un trazo en varias piezas, todas heredan el ID del padre: así un link solo se
 * pierde con el borrado TOTAL de sus trazos, no con un recorte parcial (RF-05a/b).
 */
data class IdStroke(val id: Long, val stroke: Stroke)

/** ID aleatorio de 64 bits; colisión dentro de una nota es despreciable. */
fun newStrokeId(): Long = Random.nextLong()
