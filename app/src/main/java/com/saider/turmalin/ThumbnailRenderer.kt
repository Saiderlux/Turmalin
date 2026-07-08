package com.saider.turmalin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer

// Ancho fijo de la carátula en px; la altura sigue la proporción de la página.
// Suficiente para una tarjeta de galería de ~170dp sin pesar en disco.
const val THUMB_WIDTH_PX = 320

/**
 * Carátula de una nota (RF-15): los trazos de una página renderizados a un
 * bitmap pequeño con el mismo [CanvasStrokeRenderer] de la pantalla — misma
 * geometría vectorial, escalada al ancho de la miniatura. Fondo blanco fijo
 * (la hoja es blanca también en tema oscuro). Solo lectura de trazos: jamás
 * toca la capa de ink.
 */
fun renderThumbnail(strokes: List<IdStroke>, pageSize: PageSize): Bitmap {
    val scale = THUMB_WIDTH_PX / pageSize.widthDoc()
    val heightPx = (pageSize.heightDoc() * scale).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(THUMB_WIDTH_PX, heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    // Igual que el export a PDF: canvas escalado documento→miniatura y trazos
    // dibujados con matriz identidad (el renderer cachea el Path por malla).
    canvas.scale(scale, scale)
    val renderer = CanvasStrokeRenderer.create()
    val identity = Matrix()
    for (item in strokes) {
        renderer.draw(canvas, item.stroke, identity)
    }
    return bitmap
}
