package com.saider.turmalin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.text.DateFormat
import java.util.Date

/**
 * Papelera de notas (RF-36, UC-14): lista las notas eliminadas, permite
 * restaurarlas una por una o vaciar la papelera entera (borrado permanente,
 * con diálogo de confirmación bloqueante — la única acción irreversible de
 * este flujo, a diferencia de eliminar una nota individual que es reversible).
 */
@Composable
fun TrashScreen(
    repo: NoteRepository,
    onBack: () -> Unit,
) {
    val colors = Theme.colors
    var notes by remember { mutableStateOf(repo.listTrash()) }
    var confirmEmpty by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackArrow(onClick = onBack)
            BasicText(
                text = "Papelera",
                style = TextStyle(
                    color = colors.textPrimary,
                    fontSize = AppType.title,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            if (notes.isNotEmpty()) {
                AppButton(
                    label = "Vaciar papelera",
                    onClick = { confirmEmpty = true },
                    style = ButtonStyle.DANGER,
                )
            }
        }

        if (notes.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = "La papelera está vacía",
                    style = TextStyle(color = colors.textSecondary, fontSize = AppType.label),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(notes, key = { it.uuid }) { note ->
                    TrashRow(
                        note = note,
                        onRestore = {
                            repo.restoreNote(note.uuid)
                            notes = repo.listTrash()
                        },
                    )
                }
            }
        }
    }

    // Confirmación bloqueante (RF-36): única acción irreversible de la pantalla.
    if (confirmEmpty) {
        Dialog(onDismissRequest = { confirmEmpty = false }) {
            DialogSurface {
                DialogTitle("¿Vaciar la papelera?")
                BasicText(
                    text = if (notes.size == 1) {
                        "Se borrará 1 nota de forma permanente. Esta acción no se puede deshacer."
                    } else {
                        "Se borrarán ${notes.size} notas de forma permanente. Esta acción no se puede deshacer."
                    },
                    style = TextStyle(color = colors.textSecondary, fontSize = AppType.body),
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(modifier = Modifier.align(Alignment.End)) {
                    AppButton(
                        label = "Cancelar",
                        onClick = { confirmEmpty = false },
                        style = ButtonStyle.TEXT,
                    )
                    AppButton(
                        label = "Vaciar",
                        onClick = {
                            repo.emptyTrash()
                            notes = repo.listTrash()
                            confirmEmpty = false
                        },
                        style = ButtonStyle.DANGER,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TrashRow(note: NoteMeta, onRestore: () -> Unit) {
    val colors = Theme.colors
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = note.title,
                style = TextStyle(
                    color = colors.textPrimary,
                    fontSize = AppType.label,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            BasicText(
                text = "Eliminada " + dateFormat.format(Date(note.deletedAtMillis ?: 0L)),
                style = TextStyle(color = colors.textSecondary, fontSize = AppType.caption),
            )
        }
        AppButton(label = "Restaurar", onClick = onRestore)
    }
}
