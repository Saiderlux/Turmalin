package com.saider.turmalin

// Herramientas de la barra (RF-05, RF-17, v2 1.2, v2 sección 5): pluma,
// marcatextos, gomas de trazo completo y parcial, lazo para crear links y
// Selección para mover/escalar/rotar trazos (ninguno de los dos lazos produce
// tinta: capturan un polígono).
enum class Tool { PEN, HIGHLIGHTER, ERASER_STROKE, ERASER_PARTIAL, LASSO, SELECT }

/** ¿La herramienta produce tinta? La pluma y el marcatextos comparten toda la
 *  ruta de captura/render; solo cambia el pincel (v2 1.1/1.2). */
val Tool.drawsInk: Boolean
    get() = this == Tool.PEN || this == Tool.HIGHLIGHTER
