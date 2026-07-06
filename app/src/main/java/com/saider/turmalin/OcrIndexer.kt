package com.saider.turmalin

import android.util.Log
import androidx.ink.strokes.StrokeInput
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.google.mlkit.vision.digitalink.RecognitionContext
import com.google.mlkit.vision.digitalink.WritingArea
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Primera línea no vacía del texto OCR: candidata a título (RF-12). */
fun firstOcrLine(ocrText: String): String? =
    ocrText.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }

// Hueco vertical máximo (en fracción de la altura mediana de trazo) para que
// un trazo se una a la línea en curso. Absorbe tildes y puntos de la i (que
// flotan sobre la línea) sin fusionar renglones separados.
private const val LINE_GAP_FACTOR = 0.5f

/**
 * Agrupa trazos en líneas de escritura por su extensión vertical (yMin, yMax),
 * en orden de lectura. Devuelve índices sobre la lista de entrada. Función
 * pura, testeable sin Android ni androidx.ink.
 */
fun groupStrokesIntoLines(spans: List<Pair<Float, Float>>): List<List<Int>> {
    if (spans.isEmpty()) return emptyList()
    val medianHeight = spans.map { it.second - it.first }.sorted()[spans.size / 2]
    val maxGap = medianHeight * LINE_GAP_FACTOR

    val byCenterY = spans.indices.sortedBy { (spans[it].first + spans[it].second) / 2f }
    val lines = mutableListOf<MutableList<Int>>()
    var lineYMax = Float.NEGATIVE_INFINITY
    for (index in byCenterY) {
        val (yMin, yMax) = spans[index]
        if (lines.isEmpty() || yMin - lineYMax > maxGap) {
            lines.add(mutableListOf(index))
            lineYMax = yMax
        } else {
            lines.last().add(index)
            lineYMax = maxOf(lineYMax, yMax)
        }
    }
    // Dentro de cada línea, orden temporal de escritura (el índice original):
    // Digital Ink usa las coordenadas de los puntos, no necesita orden por x.
    for (line in lines) line.sort()
    return lines
}

/**
 * Reconocimiento de manuscrita on-device con ML Kit Digital Ink (RF-24):
 * consume los puntos de los trazos (x, y, t) — sin rasterizar nada — y
 * devuelve el texto por página. Solo lee la capa de ink; el resultado vive
 * en annotations.json.
 *
 * El modelo de español se descarga UNA sola vez vía Play Services (excepción
 * aprobada a "cero red"); hasta entonces [ensureModelAvailable] devuelve
 * false y el indexado simplemente se pospone al siguiente cierre de nota.
 */
class OcrIndexer {

    private val model: DigitalInkRecognitionModel? =
        DigitalInkRecognitionModelIdentifier.fromLanguageTag("es")
            ?.let { DigitalInkRecognitionModel.builder(it).build() }

    private val modelManager = RemoteModelManager.getInstance()

    private val recognizer by lazy {
        DigitalInkRecognition.getClient(
            DigitalInkRecognizerOptions.builder(requireNotNull(model)).build()
        )
    }

    /**
     * True si el modelo ya está en el dispositivo. Si falta, dispara la
     * descarga en segundo plano y devuelve false sin esperar: el cierre de
     * nota nunca se bloquea por red.
     */
    suspend fun ensureModelAvailable(): Boolean {
        val model = model ?: return false
        return runCatching {
            if (modelManager.isModelDownloaded(model).await()) return true
            modelManager.download(model, DownloadConditions.Builder().build())
                .addOnFailureListener { /* sin red: se reintenta en el próximo cierre */ }
            false
        }.getOrElse { false }
    }

    /**
     * Texto de una página: agrupa los trazos en líneas de escritura y
     * reconoce cada línea como un Ink aparte (mejor precisión y conserva la
     * semántica de primera línea para el título, RF-12). Un error en una
     * línea la deja vacía sin tumbar el resto (RNF-07).
     */
    suspend fun recognizePage(strokes: List<androidx.ink.strokes.Stroke>): String {
        val boxed = strokes.mapNotNull { stroke ->
            val box = stroke.shape.computeBoundingBox() ?: return@mapNotNull null
            Triple(stroke, box.yMin to box.yMax, box.xMin to box.xMax)
        }
        if (boxed.isEmpty()) return ""

        val lines = groupStrokesIntoLines(boxed.map { it.second })
        val texts = lines.map { lineIndices ->
            val lineStrokes = lineIndices.map { boxed[it].first }
            val width = lineIndices.maxOf { boxed[it].third.second } -
                lineIndices.minOf { boxed[it].third.first }
            val height = lineIndices.maxOf { boxed[it].second.second } -
                lineIndices.minOf { boxed[it].second.first }
            runCatching { recognizeLine(lineStrokes, width, height) }.getOrElse {
                Log.w("Turmalin", "OCR: línea sin reconocer", it)
                ""
            }
        }
        return texts.joinToString("\n").trim('\n')
    }

    private suspend fun recognizeLine(
        strokes: List<androidx.ink.strokes.Stroke>,
        width: Float,
        height: Float,
    ): String {
        val ink = Ink.builder().apply {
            val scratch = StrokeInput()
            for (stroke in strokes) {
                val strokeBuilder = Ink.Stroke.builder()
                val inputs = stroke.inputs
                for (i in 0 until inputs.size) {
                    inputs.populate(i, scratch)
                    strokeBuilder.addPoint(
                        Ink.Point.create(scratch.x, scratch.y, scratch.elapsedTimeMillis)
                    )
                }
                addStroke(strokeBuilder.build())
            }
        }.build()

        val context = RecognitionContext.builder()
            .setPreContext("")
            .setWritingArea(WritingArea(width, height))
            .build()
        return recognizer.recognize(ink, context).await()
            .candidates.firstOrNull()?.text.orEmpty()
    }
}

// Puente Task→corrutina mínimo, para no añadir coroutines-play-services.
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
