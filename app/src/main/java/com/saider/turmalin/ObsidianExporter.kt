package com.saider.turmalin

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bridge de exportación a Obsidian (v2 6): snapshot unidireccional del vault a
 * una carpeta elegida por el usuario (SAF, árbol de documentos) — NUNCA
 * sincronización. Por nota: un `.md` con front-matter (uuid, título, tags,
 * fechas), el texto OCR como cuerpo, un `[[wikilink]]` por link saliente y el
 * PDF de la nota embebido como referencia visual (el ink no es texto editable
 * en Obsidian). No rompe "cero red" (RF-32): es escritura a disco local, el
 * mismo flujo SAF del PDF (RF-28).
 */

private val EXPORT_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

// Caracteres inválidos en nombres de archivo más los que rompen wikilinks de
// Obsidian ([]#^|). El nombre es también el texto del link: debe ser limpio.
private val INVALID_NAME_CHARS = Regex("[/\\\\:*?\"<>|#^\\[\\]]")

/** Nombre base (sin extensión) para la nota en el export: título saneado,
 *  desambiguado con el uuid corto si ya está tomado (dos "Sin título"). */
fun obsidianBaseName(title: String, uuid: String, taken: Set<String>): String {
    val clean = title.trim().ifBlank { "nota" }.replace(INVALID_NAME_CHARS, "_")
    return if (clean !in taken) clean else "$clean ${uuid.take(8)}"
}

/**
 * Contenido del `.md` de una nota. [outgoingTitles] son los nombres base de
 * las notas destino (ya desambiguados: el wikilink debe apuntar al archivo);
 * [pdfBaseName] no nulo embebe el PDF adjunto.
 */
fun buildObsidianMarkdown(
    meta: NoteMeta,
    ocrText: String,
    outgoingTitles: List<String>,
    pdfBaseName: String?,
): String = buildString {
    appendLine("---")
    appendLine("uuid: ${meta.uuid}")
    appendLine("title: \"${meta.title.replace("\"", "'")}\"")
    if (meta.tags.isNotEmpty()) {
        appendLine("tags: [${meta.tags.joinToString(", ")}]")
    }
    appendLine("created: ${EXPORT_DATE_FORMAT.format(Date(meta.createdAtMillis))}")
    appendLine("modified: ${EXPORT_DATE_FORMAT.format(Date(meta.modifiedAtMillis))}")
    appendLine("---")
    if (ocrText.isNotBlank()) {
        appendLine()
        appendLine(ocrText.trim())
    }
    if (outgoingTitles.isNotEmpty()) {
        appendLine()
        appendLine("## Vínculos")
        for (title in outgoingTitles) appendLine("- [[$title]]")
    }
    if (pdfBaseName != null) {
        appendLine()
        appendLine("## Original")
        appendLine("![[$pdfBaseName.pdf]]")
    }
}

class ObsidianExporter(private val context: Context) {

    /**
     * Exporta todas las notas activas a la carpeta [treeUri]. Devuelve cuántas
     * notas se escribieron. Cada nota produce `{base}.md` y, si tiene tinta,
     * `{base}.pdf` (mismo generador vectorial de RF-28).
     */
    fun export(repo: NoteRepository, treeUri: Uri): Int {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )

        val notes = repo.listNotes()
        val graph = repo.activeGraph()

        // Nombres base por uuid, desambiguados de una vez: los wikilinks
        // necesitan saber el nombre final de la nota destino.
        val baseNames = LinkedHashMap<String, String>()
        for (note in notes) {
            baseNames[note.uuid] = obsidianBaseName(
                note.title, note.uuid, baseNames.values.toSet(),
            )
        }

        var exported = 0
        val brush = defaultBlackPen()
        for (note in notes) {
            val base = baseNames.getValue(note.uuid)

            // PDF solo si la nota tiene tinta (mismo criterio que RF-28): sin
            // trazos no hay adjunto que citar.
            val hasInk = (0 until note.pageCount).any { page ->
                repo.loadStrokes(note.uuid, page, brush).isNotEmpty()
            }
            val document = if (hasInk) repo.buildPdf(note) else null
            val pdfBase = if (document != null) base else null
            if (document != null) {
                DocumentsContract.createDocument(
                    resolver, parent, "application/pdf", "$base.pdf",
                )?.let { uri ->
                    resolver.openOutputStream(uri)?.use { document.writeTo(it) }
                }
                document.close()
            }

            val markdown = buildObsidianMarkdown(
                meta = note,
                ocrText = repo.loadOcrText(note.uuid),
                outgoingTitles = (graph[note.uuid] ?: emptyList())
                    .mapNotNull { baseNames[it] },
                pdfBaseName = pdfBase,
            )
            DocumentsContract.createDocument(
                resolver, parent, "text/markdown", "$base.md",
            )?.let { uri ->
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(markdown.toByteArray(Charsets.UTF_8))
                }
                exported++
            }
        }
        return exported
    }
}
