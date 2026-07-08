package com.saider.turmalin

import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel

// Pantallas: galería (inicio) y nota abierta, navegadas por estado — dos
// pantallas no ameritan librería de navegación todavía.
class MainActivity : ComponentActivity() {

    // Ver StylusEraserRouter: el stream de goma del S Pen se enruta desde
    // dispatchTouchEvent directo al canvas, sin pasar por Compose.
    private val eraserRouter = StylusEraserRouter()

    // El atajo de goma solo aplica con el canvas abierto.
    private var canvasOpen = false

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
          AppTheme {
            val repo = remember { NoteRepository(applicationContext) }
            val viewModel: GalleryViewModel = viewModel(factory = GalleryViewModel.factory(repo))
            val galleryState by viewModel.state.collectAsState()
            var openNote by remember { mutableStateOf<NoteMeta?>(null) }
            var focusTitleOnOpen by remember { mutableStateOf(false) }
            var showGraph by remember { mutableStateOf(false) }
            var showTrash by remember { mutableStateOf(false) }
            val titleNudge by viewModel.titleNudge.collectAsState()
            val deleteUndo by viewModel.deleteUndo.collectAsState()
            val saveStatus by viewModel.saveStatus.collectAsState()

            SideEffect { canvasOpen = openNote != null }

            val current = openNote
            if (current == null && showGraph) {
                GraphScreen(
                    repo = repo,
                    // RF-22: doble tap en un nodo abre la nota.
                    onOpenNote = { note ->
                        viewModel.dismissTitleNudge()
                        focusTitleOnOpen = false
                        openNote = note
                    },
                    onBack = { showGraph = false },
                )
            } else if (current == null && showTrash) {
                // UC-14: notas eliminadas (RF-36) — restaurar puede traer de
                // vuelta notas a la galería, así que se refresca al volver.
                TrashScreen(
                    repo = repo,
                    onBack = {
                        viewModel.refresh()
                        showTrash = false
                    },
                )
            } else if (current == null) {
                GalleryScreen(
                    state = galleryState,
                    titleNudge = titleNudge,
                    // Con título sugerido por OCR (RF-12) se aplica directo;
                    // sin OCR, se abre la nota con el título enfocado (RF-11).
                    onNudgeAction = { nudge ->
                        if (nudge.suggestedTitle != null) {
                            viewModel.applySuggestedTitle(nudge)
                        } else {
                            viewModel.dismissTitleNudge()
                            focusTitleOnOpen = true
                            openNote = nudge.note
                        }
                    },
                    onNudgeDismiss = viewModel::dismissTitleNudge,
                    onNewNote = {
                        viewModel.dismissTitleNudge()
                        focusTitleOnOpen = false
                        openNote = viewModel.createNote()
                    },
                    onOpenNote = { note ->
                        viewModel.dismissTitleNudge()
                        focusTitleOnOpen = false
                        openNote = note
                    },
                    onSetQuery = viewModel::setQuery,
                    onOpenNotebook = viewModel::openNotebook,
                    onSetSortOrder = viewModel::setSortOrder,
                    onCreateNotebook = viewModel::createNotebook,
                    onRenameNotebook = { notebook, name ->
                        viewModel.renameNotebook(notebook.id, name)
                    },
                    onDeleteNotebook = { notebook -> viewModel.deleteNotebook(notebook.id) },
                    onMoveNote = viewModel::moveNote,
                    onOpenGraph = { showGraph = true },
                    onOpenTrash = { showTrash = true },
                    onDeleteNote = viewModel::deleteNote,
                    deleteUndo = deleteUndo,
                    onUndoDeleteNote = viewModel::undoDeleteNote,
                    onDismissDeleteUndo = viewModel::dismissDeleteUndo,
                    saveStatus = saveStatus,
                )
            } else {
                key(current.uuid) {
                    NoteScreen(
                        meta = current,
                        repo = repo,
                        wetHighLatency = wetHighLatency,
                        eraserRouter = eraserRouter,
                        focusTitleOnOpen = focusTitleOnOpen,
                        onClose = { closed, inkChanged ->
                            openNote = null
                            // UC-04: OCR en background solo si la tinta cambió;
                            // el aviso de título (RF-11/12) lo emite el ViewModel.
                            viewModel.onNoteClosed(closed, inkChanged)
                        },
                        // Seguir un link cierra la nota origen con el mismo
                        // flujo de onClose y abre la destino (UC-05/06).
                        onFollowLink = { closed, inkChanged, targetUuid ->
                            viewModel.onNoteClosed(closed, inkChanged)
                            focusTitleOnOpen = false
                            openNote = viewModel.state.value.notes
                                .find { it.uuid == targetUuid }
                        },
                        // RF-36: eliminar desde la propia nota cierra igual que
                        // "atrás" pero mueve la nota a la papelera en vez de
                        // solo guardarla; el snackbar de deshacer aparece ya en
                        // la galería.
                        onDeleteNote = { closed ->
                            viewModel.deleteNote(closed)
                            openNote = null
                        },
                    )
                }
            }
          }
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

    // La palm rejection vive en el listener del canvas (InkCanvasScreen), no
    // aquí: el dedo opera normal en toda la UI (galería, título, chips,
    // controles de página) y solo el canvas ignora los punteros que no son del
    // S Pen. Aquí queda únicamente la intercepción del stream de goma (RF-05c):
    // en este dispositivo los DOWN de streams con toolType ERASER se pierden en
    // el interop Compose→AndroidView, así que el borrado se enruta desde el
    // único punto donde está garantizado que llega el gesto completo.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (canvasOpen && hasEraserSignal(ev)) {
            eraserRouter.handler?.let { handle ->
                handle(ev)
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}
