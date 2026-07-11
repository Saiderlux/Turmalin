package com.saider.turmalin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlin.math.ceil
import kotlin.math.roundToInt

// Estilos de fondo de página (RF-06, v2 2.1): blanco, líneas, cuadrícula o
// puntos. Un build viejo que lea DOTS cae a BLANK sin romper (el valueOf de
// lectura tiene fallback), así que no hay migración.
enum class PaperStyle { BLANK, LINES, GRID, DOTS }

// Espaciado por defecto de líneas/cuadrícula en unidades de DOCUMENTO (≈ px a
// zoom 1x). Ajustable por el usuario con slider (RF-06) dentro de este rango.
const val DEFAULT_PAPER_SPACING = 40f
val PAPER_SPACING_RANGE = 16f..120f

/**
 * Preferencia de fondo de una nota (RF-06): estilo + espaciado. Se persiste en
 * meta.json; una nota sin la preferencia asume [PaperStyle.BLANK] por default,
 * así las notas existentes no se rompen.
 */
data class PaperBackground(
    val style: PaperStyle = PaperStyle.BLANK,
    val spacing: Float = DEFAULT_PAPER_SPACING,
)

// --- Tamaño de página (RF-06a) ---

/**
 * Tamaño de UNA página en milímetros (RF-06a): configurable por página
 * individual dentro de la nota, no uniforme. La orientación no es un campo:
 * paisaje = dimensiones intercambiadas ([rotated]). Se persiste por página en
 * meta.json (`pageSizesMm`); ausente ⇒ [DEFAULT_PAGE_SIZE] (Carta retrato).
 */
data class PageSize(val widthMm: Float, val heightMm: Float)

/** Misma hoja girada 90° (retrato ↔ paisaje). */
fun PageSize.rotated(): PageSize = PageSize(heightMm, widthMm)

/** Paisaje = más ancha que alta; cuadrada cuenta como retrato (RF-06a). */
val PageSize.isLandscape: Boolean get() = widthMm > heightMm

val PAGE_SIZE_CARTA = PageSize(216f, 279f)
val PAGE_SIZE_OFICIO = PageSize(216f, 340f)
val PAGE_SIZE_A4 = PageSize(210f, 297f)
val PAGE_SIZE_LEGAL = PageSize(216f, 356f)

// Presets recomendados (RF-06a) para el selector, en orden. Carta es el default.
val PAGE_SIZE_PRESETS: List<Pair<String, PageSize>> = listOf(
    "Carta" to PAGE_SIZE_CARTA,
    "Oficio" to PAGE_SIZE_OFICIO,
    "A4" to PAGE_SIZE_A4,
    "Legal" to PAGE_SIZE_LEGAL,
)
val DEFAULT_PAGE_SIZE = PAGE_SIZE_CARTA

// Límites sanos para el tamaño personalizado (RF-06a): evita una página de 0 mm
// (invisible) o absurdamente grande.
val PAGE_MM_RANGE = 50f..1000f

/**
 * Unidades de documento por milímetro (RF-06a sobre RF-09b). El tamaño de página
 * se pasa a coordenadas de documento con esta constante; los trazos siguen en
 * coordenadas de documento y el viewport no cambia (unidades absolutas: la
 * identidad —scale 1— sigue siendo la "página encajada" y el swipe de
 * paginación no se toca).
 *
 * ponytail: constante calibrada al ancho de la Tab S6 Lite (≈1200 px útiles en
 * vertical) para que Carta (216 mm) llene el ancho a zoom 1x. Es la perilla de
 * calibración: ajústala si se soporta otro ancho de pantalla.
 */
const val DOC_UNITS_PER_MM = 5.5f

fun PageSize.widthDoc(): Float = widthMm * DOC_UNITS_PER_MM
fun PageSize.heightDoc(): Float = heightMm * DOC_UNITS_PER_MM

// Puntos PDF por milímetro (RF-28): 72pt = 1 pulgada = 25.4mm.
const val MM_TO_PT = 72f / 25.4f

/**
 * Tamaño de la hoja en puntos PDF (RF-28), redondeado al entero que exige
 * PdfDocument.PageInfo. Presets ⇒ valores estándar: Carta 612×791, A4 595×842,
 * Legal 612×1009, Oficio 612×964 (Carta/Legal caen 1pt del Letter/Legal
 * "oficiales" porque los presets usan 279/356mm, no 279.4/355.6mm).
 */
fun PageSize.toPointsPt(): Pair<Int, Int> =
    (widthMm * MM_TO_PT).roundToInt() to (heightMm * MM_TO_PT).roundToInt()

/** Empareja un tamaño con su preset para resaltar el chip, en cualquiera de las
 *  dos orientaciones (los presets se declaran en retrato); null = personalizado. */
fun presetNameFor(size: PageSize): String? =
    PAGE_SIZE_PRESETS.firstOrNull { it.second == size || it.second == size.rotated() }?.first

/** Sanea una dimensión mm tecleada por el usuario: no parseable ⇒ [fallback]. */
fun coercePageMm(text: String, fallback: Float): Float =
    coerceToRange(text, fallback, PAGE_MM_RANGE)

// Borde tenue de la hoja. El fondo gris alrededor de la hoja es token del tema
// (AppColors.canvasBackdrop); la hoja sigue blanca también en tema oscuro: la
// tinta es WYSIWYG con el PDF exportado (RF-28) y la paleta incluye tinta blanca.
private val PAGE_BORDER_COLOR = Color(0xFFBBBBBB)
// Tono tenue azulado de cuaderno para las guías. El ARGB es la única fuente de
// verdad del color: la capa Compose lo envuelve en Color y el export a PDF
// (android.graphics) lo usa como int, así pantalla y PDF pintan idéntico (RF-28).
const val PAPER_LINE_ARGB = 0xFFC7D2E8.toInt()
private val PAPER_LINE_COLOR = Color(PAPER_LINE_ARGB)

// Bajo este espaciado en pantalla (px) las guías quedan demasiado juntas para
// distinguirse (zoom muy bajo): no se dibujan.
private const val MIN_LINE_SPACING_PX = 6f

// Radio del punto del dot grid (v2 2.1). Mismo valor en px de pantalla y en
// unidades de documento del PDF: coincide a zoom 1x y, como el grosor de las
// guías, no crece con el zoom (es guía visual, no tinta).
const val PAPER_DOT_RADIUS = 2.5f

/**
 * Posiciones de pantalla de las guías a lo largo de un eje: líneas en documento
 * en k·spacing (k entero) proyectadas con `pantalla = doc·scale + offset` — la
 * misma transformación doc→pantalla que la tinta (RF-09b) — acotadas a
 * [0, extent]. Aislada como función pura para testear la alineación bajo
 * pan/zoom sin depender del Canvas.
 */
fun paperLinePositions(offset: Float, scale: Float, spacing: Float, extent: Float): List<Float> {
    val spacingPx = spacing * scale
    if (spacingPx < MIN_LINE_SPACING_PX || extent <= 0f) return emptyList()
    val out = ArrayList<Float>()
    var pos = offset + ceil((0f - offset) / spacingPx) * spacingPx
    while (pos <= extent) {
        out.add(pos)
        pos += spacingPx
    }
    return out
}

/**
 * Página (RF-06a) + fondo (RF-06) como CAPA DE RENDER, nunca ink real: nada de
 * esto vive en `strokes`, así que no se puede seleccionar, borrar ni linkear
 * (RF-23). Se dibuja en espacio de pantalla usando [viewport] como única fuente
 * de verdad (misma transformación doc→pantalla que la tinta, así hoja, guías y
 * trazos jamás se desalinean bajo pan/zoom):
 *
 *  1. Hoja blanca del tamaño de página proyectado (origen doc 0,0).
 *  2. Guías de líneas/cuadrícula recortadas a la hoja.
 *  3. Borde tenue de la hoja.
 *
 * El pan es libre y la tinta puede salirse del borde (la página es guía visual,
 * no límite duro).
 */
@Composable
fun PageBackgroundLayer(
    pageSize: PageSize,
    background: PaperBackground,
    viewport: CanvasViewport,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val scale = viewport.scale
        // Rect de la hoja en pantalla: doc (0,0) → (offsetX, offsetY).
        val left = viewport.offsetX
        val top = viewport.offsetY
        val pageW = pageSize.widthDoc() * scale
        val pageH = pageSize.heightDoc() * scale
        val right = left + pageW
        val bottom = top + pageH

        drawRect(Color.White, topLeft = Offset(left, top), size = Size(pageW, pageH))

        // Guías recortadas a la hoja: se generan sobre todo el lienzo y el clip
        // deja solo las que caen dentro de la página (RF-06). Puntos (v2 2.1) =
        // círculos en las intersecciones de la misma retícula de la cuadrícula.
        if (background.style != PaperStyle.BLANK) {
            clipRect(left = left, top = top, right = right, bottom = bottom) {
                val w = size.width
                val h = size.height
                val ys = paperLinePositions(top, scale, background.spacing, bottom)
                if (background.style == PaperStyle.DOTS) {
                    for (y in ys) {
                        for (x in paperLinePositions(left, scale, background.spacing, right)) {
                            drawCircle(PAPER_LINE_COLOR, PAPER_DOT_RADIUS, Offset(x, y))
                        }
                    }
                } else {
                    for (y in ys) {
                        drawLine(PAPER_LINE_COLOR, Offset(0f, y), Offset(w, y), 1.5f)
                    }
                    if (background.style == PaperStyle.GRID) {
                        for (x in paperLinePositions(left, scale, background.spacing, right)) {
                            drawLine(PAPER_LINE_COLOR, Offset(x, 0f), Offset(x, h), 1.5f)
                        }
                    }
                }
            }
        }

        drawRect(
            PAGE_BORDER_COLOR,
            topLeft = Offset(left, top),
            size = Size(pageW, pageH),
            style = Stroke(width = 1.5f),
        )
    }
}
