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
import androidx.compose.foundation.layout.widthIn
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

        // Ancho contenido (v2 3.2): en tablet horizontal el toggle debe vivir
        // junto a su label, no al otro extremo de la pantalla.
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 600.dp),
        ) {
            // Tema manual (post-v2): aplica en vivo y persiste al instante.
            SettingsSection("Tema")
            Row(modifier = Modifier.padding(vertical = 8.dp)) {
                for ((label, value) in listOf(
                    "Sistema" to "system",
                    "Claro" to "light",
                    "Oscuro" to "dark",
                )) {
                    AppChip(
                        label = label,
                        selected = settings.theme == value,
                        onClick = { onChange(settings.copy(theme = value)) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            SettingsSection("Gestos rápidos")
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
            // Títulos de sección en el verde de marca (v2 3.2).
            color = Theme.colors.accent,
            fontSize = AppType.body,
            fontWeight = FontWeight.Bold,
        ),
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}
