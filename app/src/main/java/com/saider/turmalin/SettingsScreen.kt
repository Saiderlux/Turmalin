package com.saider.turmalin

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Ajustes globales de la app (v2 3.4): pantalla mínima sobre [AppSettings].
 * Cada toggle aplica y persiste al instante — sin botón de guardar. Apagar un
 * gesto/atajo nunca quita función: la barra de herramientas cubre lo mismo.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    // Bridge a Obsidian (v2 6): exportación unidireccional a la carpeta
    // elegida; [exportStatus] refleja el avance/resultado.
    exportStatus: String?,
    onExportToObsidian: (Uri) -> Unit,
    onBack: () -> Unit,
) {
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(onExportToObsidian) }
    val colors = Theme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackArrow(onClick = onBack)
            BasicText(
                text = "Ajustes",
                style = TextStyle(
                    color = colors.textPrimary,
                    fontSize = AppType.display,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }

        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            SettingsSection("Gestos rápidos (v2 3.3)")
            ToggleRow(
                label = "Tap con dos dedos deshace",
                checked = settings.gestureUndo,
                onToggle = { onChange(settings.copy(gestureUndo = it)) },
                modifier = Modifier.fillMaxWidth(),
            )
            ToggleRow(
                label = "Tap con tres dedos rehace",
                checked = settings.gestureRedo,
                onToggle = { onChange(settings.copy(gestureRedo = it)) },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(20.dp))
            SettingsSection("S Pen")
            ToggleRow(
                label = "Mantener el botón del S Pen activa la goma",
                checked = settings.stylusButtonEraser,
                onToggle = { onChange(settings.copy(stylusButtonEraser = it)) },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(20.dp))
            SettingsSection("Exportación")
            BasicText(
                text = "Exporta el vault completo a una carpeta como archivos " +
                    "Markdown para Obsidian: un .md por nota con sus tags y " +
                    "[[vínculos]], más el PDF de cada nota como referencia " +
                    "visual. Es una copia única, no sincronización.",
                style = TextStyle(color = colors.textSecondary, fontSize = AppType.body),
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppButton(
                    label = "Exportar a Obsidian…",
                    onClick = { exportLauncher.launch(null) },
                    style = ButtonStyle.FILLED,
                )
                exportStatus?.let { status ->
                    BasicText(
                        text = status,
                        style = TextStyle(color = colors.textSecondary, fontSize = AppType.body),
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(text: String) {
    BasicText(
        text = text,
        style = TextStyle(
            color = Theme.colors.textSecondary,
            fontSize = AppType.body,
            fontWeight = FontWeight.Bold,
        ),
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}
