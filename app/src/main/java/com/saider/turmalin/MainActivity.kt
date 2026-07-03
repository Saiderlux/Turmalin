package com.saider.turmalin

import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

// Fase 0 del roadmap: prototipo de ink. Una sola pantalla, sin navegación.
class MainActivity : ComponentActivity() {

    // Ver StylusEraserRouter: el stream de goma del S Pen se enruta desde
    // dispatchTouchEvent directo al canvas, sin pasar por Compose.
    private val eraserRouter = StylusEraserRouter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Render de la capa wet (ver InkCanvasScreen). Default: helper clásico V21,
        // porque el helper de baja latencia V33 no refresca el panel físico de la
        // Tab S6 Lite (el trazo en vivo no se ve, o aparece de golpe a mitad de
        // trazo). Para reevaluar V33 en otro dispositivo:
        //   adb shell am force-stop com.saider.turmalin
        //   adb shell am start -n com.saider.turmalin/.MainActivity --ez wet_high_latency false
        val wetHighLatency = intent.getBooleanExtra("wet_high_latency", true)
        setContent {
            InkCanvasScreen(wetHighLatency = wetHighLatency, eraserRouter = eraserRouter)
        }
    }

    // ¿El evento trae señal de goma del S Pen? Cubre las tres formas observadas
    // en este dispositivo (todas MotionEvent estándar, sin SDK de Samsung):
    // acciones propietarias 211-214 de One UI, botón del stylus (PRIMARY, o
    // SECONDARY en el mapeo legado de pens EMR), y toolType ERASER.
    private fun hasEraserSignal(ev: MotionEvent): Boolean {
        if (ev.actionMasked in 211..214) return true
        if (ev.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY) ||
            ev.isButtonPressed(MotionEvent.BUTTON_SECONDARY)
        ) {
            return true
        }
        return (0 until ev.pointerCount).any {
            ev.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER
        }
    }

    // Fase 0: solo el S Pen interactúa con la app, así que los streams táctiles de
    // dedo/palma se descartan aquí, antes de llegar a Compose. Además de servir como
    // palm rejection, esto evita un crash conocido de androidx.ink 1.0.0: la
    // contabilidad interna de punteros de InProgressStrokes (InProgressShapes.kt:286,
    // "changeId not mapped to a pointerId") se corrompe cuando conviven punteros
    // consumidos (dedo/palma) con el stylus. En Samsung el S Pen es un dispositivo de
    // entrada independiente y nunca comparte MotionEvent con los dedos, por lo que
    // basta con revisar el toolType del stream completo.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // DIAGNÓSTICO TEMPORAL RF-05c: registrar qué llega a la Activity con el
        // botón del pen. Quitar cuando el atajo quede verificado.
        if (ev.buttonState != 0 ||
            ev.actionMasked == MotionEvent.ACTION_DOWN ||
            ev.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            Log.d(
                "Turmalin",
                "dispatchTouch: ${MotionEvent.actionToString(ev.actionMasked)} " +
                    "tool=${ev.getToolType(0)} buttons=${ev.buttonState}",
            )
        }
        // RF-05c: el stream de goma se maneja aquí y NO se entrega a Compose —
        // en este dispositivo los DOWN de streams con toolType ERASER se pierden
        // en el interop Compose→AndroidView, así que el borrado se enruta desde
        // el único punto donde está garantizado que llega todo el gesto.
        if (hasEraserSignal(ev)) {
            eraserRouter.handler?.let { handle ->
                handle(ev)
                return true
            }
        }

        // TOOL_TYPE_ERASER también cuenta como stylus: algunos digitalizadores
        // reportan ese toolType cuando el botón del pen va presionado (RF-05c).
        val isStylusStream = (0 until ev.pointerCount).any {
            val toolType = ev.getToolType(it)
            toolType == MotionEvent.TOOL_TYPE_STYLUS ||
                toolType == MotionEvent.TOOL_TYPE_ERASER
        }
        return if (isStylusStream) super.dispatchTouchEvent(ev) else true
    }

    // DIAGNÓSTICO TEMPORAL RF-05c: los eventos de botón de stylus pueden llegar
    // como genéricos (hover, ACTION_BUTTON_PRESS). Quitar tras verificar.
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.buttonState != 0) {
            Log.d(
                "Turmalin",
                "dispatchGeneric: ${MotionEvent.actionToString(ev.actionMasked)} " +
                    "tool=${ev.getToolType(0)} buttons=${ev.buttonState}",
            )
        }
        return super.dispatchGenericMotionEvent(ev)
    }
}
