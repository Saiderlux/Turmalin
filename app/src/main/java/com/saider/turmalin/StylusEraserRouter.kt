package com.saider.turmalin

import android.view.MotionEvent

/**
 * Canal mínimo entre la Activity y el canvas para el atajo de goma del S Pen
 * (RF-05c). La Activity intercepta el stream de goma a nivel dispatch y lo
 * entrega aquí, porque en este dispositivo los ACTION_DOWN de los streams con
 * toolType ERASER se pierden en el recorrido Compose→AndroidView (verificado
 * con instrumentación: el dispatch de la Activity los recibe, el listener del
 * canvas no).
 */
class StylusEraserRouter {
    var handler: ((MotionEvent) -> Unit)? = null
}
