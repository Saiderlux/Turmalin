package com.saider.turmalin

// Herramientas de la barra (RF-05, RF-17, v2 1.2): pluma, marcatextos, gomas de
// trazo completo y parcial, y lazo para crear links (el lazo no produce tinta:
// captura un polígono).
enum class Tool { PEN, HIGHLIGHTER, ERASER_STROKE, ERASER_PARTIAL, LASSO }

/** ¿La herramienta produce tinta? La pluma y el marcatextos comparten toda la
 *  ruta de captura/render; solo cambia el pincel (v2 1.1/1.2). */
val Tool.drawsInk: Boolean
    get() = this == Tool.PEN || this == Tool.HIGHLIGHTER
