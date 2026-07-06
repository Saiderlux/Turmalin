package com.saider.turmalin

import android.content.ContentValues
import android.content.Context
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke

// Página A4 en puntos PDF (72 dpi) y margen imprimible.
private const val PAGE_WIDTH_PT = 595
private const val PAGE_HEIGHT_PT = 842
private const val PAGE_MARGIN_PT = 36f

/**
 * Exportación de una nota a PDF vectorial (RF-28/29, UC-10).
 *
 * Usa [PdfDocument] nativo (sin dependencias nuevas): el canvas de cada página
 * graba las órdenes de dibujo como operadores vectoriales del PDF, no como
 * bitmap. El ink se dibuja con el mismo [CanvasStrokeRenderer] de la pantalla,
 * así que cada trazo aterriza como relleno vectorial de su silueta (variable por
 * presión) y el PDF se ve idéntico a la app. Cada página de la nota es una página
 * del PDF; el contenido se ajusta al área imprimible conservando la proporción.
 *
 * Los halos de link (RF-23a) se re-tiñen con [linkOverlayBrush] y se dibujan
 * debajo de la tinta, igual que en el lienzo.
 *
 * El archivo se escribe en Descargas vía MediaStore (scoped storage, sin permiso
 * en API 29+), fuera del vault (RF-30/UC-10: PDF portable).
 */
class PdfExporter(private val context: Context) {

    private val renderer = CanvasStrokeRenderer.create()
    private val linkBrush = linkOverlayBrush()
    // Aporta familia (pressurePen) y epsilon al reconstruir los pinceles al leer
    // ink.bin; el color y grosor reales vienen del archivo.
    private val baseBrush = defaultBlackPen()
    // Identidad: el ajuste documento→página se aplica al canvas (no como Matrix
    // del renderer), así que cada trazo se dibuja con identidad (ver [drawPage]).
    private val identity = Matrix()

    /** Genera el PDF y lo guarda; devuelve su Uri o null si algo falló. */
    fun export(repo: NoteRepository, meta: NoteMeta): Uri? = runCatching {
        val document = PdfDocument()
        for (page in 0 until meta.pageCount) {
            val strokes = repo.loadStrokes(meta.uuid, page, baseBrush)
            val links = repo.loadRegionLinks(meta.uuid).filter { it.page == page }
            drawPage(document, page, strokes, links)
        }
        val uri = writeToDownloads(document, meta.title)
        document.close()
        uri
    }.getOrNull()

    private fun drawPage(
        document: PdfDocument,
        pageIndex: Int,
        strokes: List<IdStroke>,
        links: List<LinkRegion>,
    ) {
        val info = PdfDocument.PageInfo
            .Builder(PAGE_WIDTH_PT, PAGE_HEIGHT_PT, pageIndex + 1)
            .create()
        val pdfPage = document.startPage(info)
        val transform = fitTransform(strokes)
        if (transform != null) {
            val canvas = pdfPage.canvas
            // El renderer NO aplica de forma fiable su parámetro Matrix a trazos
            // recién cargados: cachea el Path por malla y el ajuste se pierde, así
            // que el trazo se dibujaba en coordenadas de documento crudas y lo que
            // excedía la hoja (x>595) se recortaba —el trazo salía diminuto o
            // "desaparecido". Se transforma el CANVAS y se dibuja con identidad,
            // igual que la pantalla (que dibuja con identidad bajo un graphicsLayer).
            canvas.save()
            canvas.concat(transform)
            // Overlay de links debajo de la tinta (mismo orden que el lienzo).
            for (link in links) {
                for (item in strokes.filter { it.id in link.strokeIds }) {
                    renderer.draw(canvas, Stroke(linkBrush, item.stroke.inputs), identity)
                }
            }
            for (item in strokes) {
                renderer.draw(canvas, item.stroke, identity)
            }
            canvas.restore()
        }
        document.finishPage(pdfPage)
    }

    // Matriz documento→PDF que ajusta el bbox de los trazos al área imprimible,
    // conservando proporción y centrando. Página vacía ⇒ null (hoja en blanco).
    private fun fitTransform(strokes: List<IdStroke>): Matrix? {
        val (xMin, yMin, xMax, yMax) = strokesBoundingBox(strokes)
        val contentW = xMax - xMin
        val contentH = yMax - yMin
        if (contentW <= 0f || contentH <= 0f) return null
        val printableW = PAGE_WIDTH_PT - 2f * PAGE_MARGIN_PT
        val printableH = PAGE_HEIGHT_PT - 2f * PAGE_MARGIN_PT
        val scale = minOf(printableW / contentW, printableH / contentH)
        val offsetX = PAGE_MARGIN_PT + (printableW - contentW * scale) / 2f - xMin * scale
        val offsetY = PAGE_MARGIN_PT + (printableH - contentH * scale) / 2f - yMin * scale
        return Matrix().apply {
            setScale(scale, scale)
            postTranslate(offsetX, offsetY)
        }
    }

    private fun writeToDownloads(document: PdfDocument, title: String): Uri? {
        val safeName = title.ifBlank { "nota" }.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$safeName.pdf")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            // Marca el archivo en curso: se limpia al terminar de escribir para
            // que no aparezca a medias en el explorador.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return null
        resolver.openOutputStream(uri)?.use { document.writeTo(it) } ?: return null
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
