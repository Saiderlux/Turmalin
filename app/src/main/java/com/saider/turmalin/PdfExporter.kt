package com.saider.turmalin

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Puntos PDF por unidad de documento (RF-28): compone mm→pt con la escala
// documento→mm de la nota. Mapea la hoja completa (0,0)–(anchoDoc,altoDoc) a la
// página PDF (0,0)–(anchoPt,altoPt) con proporción del papel real, sin estirar.
private const val DOC_TO_PT = MM_TO_PT / DOC_UNITS_PER_MM

// Grosor de las guías del fondo en unidades de documento. Se dibuja bajo el
// canvas escalado, así que en pt equivale a ~0.8pt (hairline, como en pantalla).
private const val PDF_LINE_WIDTH_DOC = 1.5f

private val PDF_FILENAME_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

/**
 * Nombre de archivo sugerido al exportar (RF-28): título de la nota saneado
 * (sin caracteres inválidos en nombres de archivo) más la fecha, para que el
 * selector de documentos del sistema arranque con un nombre útil por default.
 */
fun suggestedPdfFileName(title: String, nowMillis: Long): String {
    val safeTitle = title.trim().ifBlank { "nota" }.replace(Regex("[/\\\\:*?\"<>|]"), "_")
    return "$safeTitle ${PDF_FILENAME_DATE_FORMAT.format(Date(nowMillis))}.pdf"
}

/**
 * Exportación de una nota a PDF vectorial (RF-28/29, UC-10).
 *
 * Usa [PdfDocument] nativo (sin dependencias nuevas): el canvas de cada página
 * graba las órdenes de dibujo como operadores vectoriales del PDF, no como
 * bitmap. El ink se dibuja con el mismo [CanvasStrokeRenderer] de la pantalla,
 * así que cada trazo aterriza como relleno vectorial de su silueta (variable por
 * presión) y el PDF se ve idéntico a la app.
 *
 * Cada página del PDF toma el tamaño y orientación de SU página (RF-06a/RF-28):
 * mm→pt (factor 72/25.4) por página, así una nota con páginas mixtas produce un
 * PDF con hojas de distinto tamaño. El contenido se dibuja en coordenadas de
 * documento bajo un
 * canvas escalado documento→pt (mismo esquema que la pantalla bajo su
 * graphicsLayer), de modo que la tinta cae en su posición real sobre la hoja y
 * la proporción es la del papel real. Lo que se salga de la hoja lo recorta el
 * borde del PDF (la página es guía visual, no límite duro).
 *
 * Orden de capas idéntico al lienzo (RF-28): fondo configurado (líneas/cuadrícula
 * con el espaciado de RF-06) primero, luego el halo de los links (RF-23a/RF-29)
 * debajo de la tinta, y la tinta encima.
 *
 * El destino del archivo lo elige el usuario (RF-28/UC-10): el selector de
 * documentos del sistema (Storage Access Framework, `ACTION_CREATE_DOCUMENT`)
 * entrega el [Uri] destino desde la UI, y [writeTo] vuelca ahí el PDF ya
 * generado — nada se guarda en Descargas por default.
 */
class PdfExporter(private val context: Context) {

    private val renderer = CanvasStrokeRenderer.create()
    // Aporta familia (pressurePen) y epsilon al reconstruir los pinceles al leer
    // ink.bin; el color y grosor reales vienen del archivo.
    private val baseBrush = defaultBlackPen()
    // Identidad: el ajuste documento→página se aplica al canvas (no como Matrix
    // del renderer), así que cada trazo se dibuja con identidad (ver [drawPage]).
    private val identity = Matrix()

    /** Genera el PDF en memoria; null si algo falló. Llamar [writeTo] después. */
    fun build(repo: NoteRepository, meta: NoteMeta): PdfDocument? = runCatching {
        val document = PdfDocument()
        for (page in 0 until meta.pageCount) {
            val strokes = repo.loadStrokes(meta.uuid, page, baseBrush)
            val links = repo.loadRegionLinks(meta.uuid).filter { it.page == page }
            drawPage(document, page, meta.pageSizeOf(page), meta.paper, strokes, links)
        }
        document
    }.getOrNull()

    /** Vuelca un PDF ya generado al [uri] elegido por el usuario (SAF). */
    fun writeTo(document: PdfDocument, uri: Uri): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri)?.use { document.writeTo(it) } != null
    }.getOrDefault(false)

    private fun drawPage(
        document: PdfDocument,
        pageIndex: Int,
        pageSize: PageSize,
        paper: PaperBackground,
        strokes: List<IdStroke>,
        links: List<LinkRegion>,
    ) {
        // Tamaño de página en puntos PDF a partir del tamaño de ESTA página (RF-28).
        val (widthPt, heightPt) = pageSize.toPointsPt()
        val info = PdfDocument.PageInfo.Builder(widthPt, heightPt, pageIndex + 1).create()
        val pdfPage = document.startPage(info)
        val canvas = pdfPage.canvas

        // Canvas escalado documento→pt: todo se dibuja en coordenadas de DOCUMENTO,
        // igual que la pantalla bajo su graphicsLayer. El renderer NO aplica de
        // forma fiable su parámetro Matrix a trazos recién cargados (cachea el Path
        // por malla), así que se transforma el canvas y se dibuja con identidad.
        canvas.save()
        canvas.scale(DOC_TO_PT, DOC_TO_PT)

        // 1. Fondo configurado (RF-06/RF-28), bajo la tinta como en el lienzo.
        drawBackground(canvas, paper, pageSize)
        // 2. Overlay de links (RF-23a/RF-29) debajo de la tinta.
        for (link in links) {
            for (item in strokes.filter { it.id in link.strokeIds }) {
                renderer.draw(canvas, linkTintStroke(item.stroke), identity)
            }
        }
        // 3. Tinta encima.
        for (item in strokes) {
            renderer.draw(canvas, item.stroke, identity)
        }

        canvas.restore()
        document.finishPage(pdfPage)
    }

    /**
     * Fondo de página en el PDF (RF-06/RF-28): líneas o cuadrícula con el mismo
     * espaciado que en pantalla. Se dibuja en coordenadas de documento (el canvas
     * ya está escalado a pt) reutilizando [paperLinePositions] con scale=1, así
     * las guías caen en las mismas posiciones k·spacing que en el lienzo. Blanco
     * ⇒ no dibuja nada (la hoja del PDF ya es blanca).
     */
    private fun drawBackground(canvas: Canvas, paper: PaperBackground, pageSize: PageSize) {
        if (paper.style == PaperStyle.BLANK) return
        val wDoc = pageSize.widthDoc()
        val hDoc = pageSize.heightDoc()
        val paint = Paint().apply {
            color = PAPER_LINE_ARGB
            strokeWidth = PDF_LINE_WIDTH_DOC
            isAntiAlias = true
        }
        val ys = paperLinePositions(0f, 1f, paper.spacing, hDoc)
        if (paper.style == PaperStyle.DOTS) {
            // Puntos (v2 2.1): intersecciones de la retícula, mismo radio que
            // en pantalla (coincide a zoom 1x, RF-28).
            for (y in ys) {
                for (x in paperLinePositions(0f, 1f, paper.spacing, wDoc)) {
                    canvas.drawCircle(x, y, PAPER_DOT_RADIUS, paint)
                }
            }
            return
        }
        for (y in ys) {
            canvas.drawLine(0f, y, wDoc, y, paint)
        }
        if (paper.style == PaperStyle.GRID) {
            for (x in paperLinePositions(0f, 1f, paper.spacing, wDoc)) {
                canvas.drawLine(x, 0f, x, hDoc, paint)
            }
        }
    }

}
