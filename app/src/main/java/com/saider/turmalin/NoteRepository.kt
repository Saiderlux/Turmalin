package com.saider.turmalin

import android.content.Context
import android.net.Uri
import androidx.ink.brush.Brush
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

const val DEFAULT_NOTE_TITLE = "Sin título"

// Versión del formato de ink.bin. v2 (Fase 6) añade color y grosor por trazo,
// antes de la geometría nativa; un archivo v1 (que empezaba con el conteo de
// trazos) no coincide y se lee como página en blanco (retrocompat rota a
// propósito, ver [NoteRepository.loadStrokes]).
private const val INK_FORMAT_VERSION = 2

/**
 * Metadata de una nota. El [uuid] es el identificador permanente (RF-31): se
 * genera una sola vez en [NoteRepository.createNote] y nunca se regenera.
 */
data class NoteMeta(
    val uuid: String,
    val title: String,
    val createdAtMillis: Long,
    val modifiedAtMillis: Long,
    // Veces que se mostró el aviso de "añadir título" (RF-11: máximo 2).
    val titleNudgeCount: Int,
    // Páginas de la nota (RF-09) y última página abierta (RF-09a).
    val pageCount: Int = 1,
    val lastPageIndex: Int = 0,
    // Cuaderno al que pertenece (RF-14); null = raíz del vault.
    val notebookId: String? = null,
    // Tags manuales por teclado (RF-16): la búsqueda de la galería los indexa
    // junto al título y al texto OCR.
    val tags: List<String> = emptyList(),
)

    // Fondo de página de la nota (RF-06): estilo + espaciado. Default BLANK; una
    // nota sin la preferencia (meta.json viejo) asume blanco sin romperse.
    val paper: PaperBackground = PaperBackground(),
    // Tamaño de página POR PÁGINA (RF-06a), indexado por página; consultar
    // siempre vía [pageSizeOf], que tolera listas cortas (⇒ Carta retrato).
    val pageSizes: List<PageSize> = emptyList(),
/**
 * Cuaderno (RF-13): agrupación puramente visual. No es carpeta física — las
 * notas siempre viven en `vault/notes/{uuid}` y solo referencian al cuaderno
 * por [id] desde su meta.json, así mover una nota nunca toca el ink (RF-31).
 */
/** Tamaño de la página [index] (RF-06a): fuera de la lista ⇒ Carta retrato,
 *  así una nota con pageCount mayor que su lista nunca rompe. */
fun NoteMeta.pageSizeOf(index: Int): PageSize =
    pageSizes.getOrNull(index) ?: DEFAULT_PAGE_SIZE

/**
 * Tamaños por página desde meta.json (RF-06a). Clave nueva `pageSizesMm`
 * (array de pares [w,h]); ausente ⇒ migración de lectura: el tamaño uniforme
 * legacy de la nota (`pageWidthMm`/`pageHeightMm`, a su vez Carta por default)
 * replicado en todas las páginas. Función pura para testear la migración en JVM.
 */
fun resolvePageSizes(json: JSONObject, pageCount: Int): List<PageSize> {
    val array = json.optJSONArray("pageSizesMm")
    if (array != null) {
        return List(array.length()) { i ->
            val pair = array.getJSONArray(i)
            PageSize(pair.getDouble(0).toFloat(), pair.getDouble(1).toFloat())
        }
    }
    val legacy = PageSize(
        widthMm = json.optDouble("pageWidthMm", DEFAULT_PAGE_SIZE.widthMm.toDouble()).toFloat(),
        heightMm = json.optDouble("pageHeightMm", DEFAULT_PAGE_SIZE.heightMm.toDouble()).toFloat(),
    )
    return List(pageCount) { legacy }
}
data class Notebook(
    val id: String,
    val name: String,
)

/**
 * Ajustes del grafo (estilo Obsidian): perillas de la simulación de fuerzas y del
 * render, persistidas bajo la clave `graph` de settings.json. Los defaults igualan
 * las constantes históricas de [GraphSimulation] y [GraphScreen].
 */
data class GraphSettings(
    val centerGravity: Float = 0.22f,
    val repulsionStrength: Float = 1f,
    val linkStrength: Float = 1f,
    val idealDistance: Float = 170f,
    val nodeSize: Float = 1f,
    val linkThickness: Float = 3f,
    // Bajo este zoom (viewport.scale) las etiquetas dejan de dibujarse; 0 = siempre.
    val textFadeThreshold: Float = 0f,
    val arrows: Boolean = false,
)

/**
 * Región linkeada (RF-17, RF-23a): un link desde un conjunto de trazos concretos
 * (por [strokeIds] estables) de una página de la nota origen hacia la nota
 * destino. [bbox] ([xMin,yMin,xMax,yMax] en coordenadas de documento) es la caja
 * cacheada de esos trazos, para el hit-test del tap sin re-resolver los trazos.
 * Vive solo en annotations.json — el ink jamás se modifica (RF-23).
 */
data class LinkRegion(
    val targetUuid: String,
    val page: Int,
    val strokeIds: List<Long>,
    val bbox: List<Float>,
)

/**
 * Vault local de notas (RF-32, RF-33). Estructura por nota:
 *
 * ```
 * vault/notes/{uuid}/
 *   meta.json      → título, timestamps, contador de avisos
 *   pages/0.ink    → trazos de la página con la serialización nativa de
 *                    androidx.ink.storage (un batch por trazo, con prefijo de
 *                    longitud; una sola página en este recorte)
 * ```
 *
 * Ubicación interina: almacenamiento externo de la app (visible bajo
 * Android/data en el explorador). La ubicación definitiva de RF-30 (carpeta
 * pública copiable sin restricciones) es una decisión aparte pendiente, con
 * implicaciones de permisos de Android.
 */
class NoteRepository(context: Context) {

    private val appContext = context.applicationContext
    private val notesDir = File(context.getExternalFilesDir(null), "vault/notes")
    private val notebooksFile = File(context.getExternalFilesDir(null), "vault/notebooks.json")
    private val graphFile = File(context.getExternalFilesDir(null), "vault/graph.json")
    private val settingsFile = File(context.getExternalFilesDir(null), "vault/settings.json")

    fun listNotes(): List<NoteMeta> =
        (notesDir.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { readMeta(it) }
            .sortedByDescending { it.modifiedAtMillis }

    fun createNote(notebookId: String? = null): NoteMeta {
        val now = System.currentTimeMillis()
        val meta = NoteMeta(
            uuid = UUID.randomUUID().toString(),
            title = DEFAULT_NOTE_TITLE,
            createdAtMillis = now,
            modifiedAtMillis = now,
            titleNudgeCount = 0,
            notebookId = notebookId,
        )
        saveMeta(meta)
        return meta
    }

    /** Mueve la nota a otro cuaderno (RF-14) — solo reescribe su meta.json. */
    fun moveNote(meta: NoteMeta, notebookId: String?): NoteMeta {
        val moved = meta.copy(notebookId = notebookId)
        saveMeta(moved)
        return moved
    }

    // --- Cuadernos (RF-13): registro único en vault/notebooks.json ---

    fun listNotebooks(): List<Notebook> {
        if (!notebooksFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(notebooksFile.readText())
            buildList {
                for (i in 0 until array.length()) {
                    val json = array.getJSONObject(i)
                    add(Notebook(id = json.getString("id"), name = json.getString("name")))
                }
            }
        }.getOrElse { emptyList() }
    }

    fun createNotebook(name: String): Notebook {
        val notebook = Notebook(id = UUID.randomUUID().toString(), name = name)
        saveNotebooks(listNotebooks() + notebook)
        return notebook
    }

    fun renameNotebook(id: String, name: String) {
        saveNotebooks(listNotebooks().map { if (it.id == id) it.copy(name = name) else it })
    }

    /** Elimina el cuaderno; sus notas pasan a la raíz — nunca se borra una nota. */
    fun deleteNotebook(id: String) {
        saveNotebooks(listNotebooks().filterNot { it.id == id })
        listNotes()
            .filter { it.notebookId == id }
            .forEach { saveMeta(it.copy(notebookId = null)) }
    }

    private fun saveNotebooks(notebooks: List<Notebook>) {
        notebooksFile.parentFile?.mkdirs()
        val array = JSONArray()
        for (notebook in notebooks) {
            array.put(JSONObject().put("id", notebook.id).put("name", notebook.name))
        }
        notebooksFile.writeText(array.toString())
    }

    // --- Links (RF-17/18/33): índice global en vault/graph.json + regiones
    // --- por nota en annotations.json ---

    /**
     * Índice global de links (RF-18): uuid → uuids a los que apunta (salientes).
     * Las aristas son DIRIGIDAS (A→B ≠ B→A), alineadas con el modelo de regiones
     * (`LinkRegion.targetUuid` ya es dirigido). Los backlinks (referencias
     * entrantes) se obtienen con [loadBacklinks], invirtiendo el mapa. Corrupto o
     * ausente ⇒ vacío (RNF-07).
     */
    fun loadGraph(): Map<String, List<String>> {
        if (!graphFile.exists()) return emptyMap()
        return runCatching {
            val json = JSONObject(graphFile.readText())
            buildMap {
                for (key in json.keys()) {
                    val array = json.getJSONArray(key)
                    put(key, List(array.length()) { array.getString(it) })
                }
            }
        }.getOrElse { emptyMap() }
    }

    /** Registra la arista dirigida a→b en graph.json (RF-18). Idempotente; a==b se ignora. */
    fun addGraphLink(a: String, b: String) {
        if (a == b) return
        val graph = loadGraph()
            .mapValues { it.value.toMutableSet() }
            .toMutableMap()
        graph.getOrPut(a) { mutableSetOf() }.add(b)
        writeGraph(graph)
    }

    /** Backlinks de una nota (RF-23b, UC-06): quién apunta a [uuid] (entrantes). */
    fun loadBacklinks(uuid: String): List<String> =
        loadGraph().filter { uuid in it.value }.keys.toList()

    /**
     * Quita la arista dirigida a→b de graph.json solo si ya no queda ningún link
     * de región de `a` que la respalde (RF-05b: el link muere con sus trazos).
     * ponytail: un link de arrastre nodo-a-nodo (UC-08, sin trazos) hacia el mismo
     * destino se borraría también; provenance por arista si hiciera falta distinguir.
     */
    fun removeGraphLinkIfOrphan(a: String, b: String) {
        if (loadRegionLinks(a).any { it.targetUuid == b }) return
        val graph = loadGraph().mapValues { it.value.toMutableList() }.toMutableMap()
        graph[a]?.remove(b)
        if (graph[a].isNullOrEmpty()) graph.remove(a)
        writeGraph(graph)
    }

    private fun writeGraph(graph: Map<String, Collection<String>>) {
        graphFile.parentFile?.mkdirs()
        val json = JSONObject()
        for ((uuid, connected) in graph) json.put(uuid, JSONArray(connected.toList()))
        graphFile.writeText(json.toString())
    }

    // --- Ajustes del grafo: clave `graph` de settings.json, preservando el resto ---

    /** Ajustes del grafo; ausente/corrupto ⇒ defaults (RNF-07). */
    fun loadGraphSettings(): GraphSettings {
        if (!settingsFile.exists()) return GraphSettings()
        return runCatching {
            val g = JSONObject(settingsFile.readText()).optJSONObject("graph")
                ?: return GraphSettings()
            val d = GraphSettings()
            GraphSettings(
                centerGravity = g.optDouble("centerGravity", d.centerGravity.toDouble()).toFloat(),
                repulsionStrength = g.optDouble("repulsionStrength", d.repulsionStrength.toDouble()).toFloat(),
                linkStrength = g.optDouble("linkStrength", d.linkStrength.toDouble()).toFloat(),
                idealDistance = g.optDouble("idealDistance", d.idealDistance.toDouble()).toFloat(),
                nodeSize = g.optDouble("nodeSize", d.nodeSize.toDouble()).toFloat(),
                linkThickness = g.optDouble("linkThickness", d.linkThickness.toDouble()).toFloat(),
                textFadeThreshold = g.optDouble("textFadeThreshold", d.textFadeThreshold.toDouble()).toFloat(),
                arrows = g.optBoolean("arrows", d.arrows),
            )
        }.getOrDefault(GraphSettings())
    }

    /** Persiste los ajustes del grafo sin tocar otras claves de settings.json. */
    fun saveGraphSettings(s: GraphSettings) {
        settingsFile.parentFile?.mkdirs()
        val json = runCatching { JSONObject(settingsFile.readText()) }.getOrElse { JSONObject() }
        json.put(
            "graph",
            JSONObject()
                .put("centerGravity", s.centerGravity.toDouble())
                .put("repulsionStrength", s.repulsionStrength.toDouble())
                .put("linkStrength", s.linkStrength.toDouble())
                .put("idealDistance", s.idealDistance.toDouble())
                .put("nodeSize", s.nodeSize.toDouble())
                .put("linkThickness", s.linkThickness.toDouble())
                .put("textFadeThreshold", s.textFadeThreshold.toDouble())
                .put("arrows", s.arrows),
        )
        settingsFile.writeText(json.toString())
    }

    /**
     * Añade un link de región a la nota origen (UC-05): persiste el link (trazos
     * + bbox) en su annotations.json y la arista en graph.json. La capa de ink
     * no se toca (RF-23).
     */
    fun addRegionLink(uuid: String, region: LinkRegion) {
        saveRegionLinks(uuid, loadRegionLinks(uuid) + region)
        addGraphLink(uuid, region.targetUuid)
    }

    /** Reescribe la lista completa de links de región de la nota (clave `links`),
     *  preservando las demás claves de annotations.json. No toca graph.json. */
    fun saveRegionLinks(uuid: String, links: List<LinkRegion>) {
        val dir = File(notesDir, uuid)
        dir.mkdirs()
        val file = File(dir, "annotations.json")
        val json = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
        val array = JSONArray()
        for (link in links) {
            array.put(
                JSONObject()
                    .put("targetUuid", link.targetUuid)
                    .put("page", link.page)
                    .put("strokeIds", JSONArray(link.strokeIds))
                    .put("bbox", JSONArray(link.bbox)),
            )
        }
        json.put("links", array)
        file.writeText(json.toString())
    }

    /** Regiones linkeadas de la nota (todas sus páginas); corrupto ⇒ vacío. */
    fun loadRegionLinks(uuid: String): List<LinkRegion> {
        val file = File(File(notesDir, uuid), "annotations.json")
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONObject(file.readText()).optJSONArray("links")
                ?: return emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    // Por entrada: una entrada malformada (p. ej. un link de un
                    // build viejo en formato `polygon`) se ignora sin descartar
                    // las válidas (RNF-07).
                    runCatching {
                        val json = array.getJSONObject(i)
                        val ids = json.getJSONArray("strokeIds")
                        val box = json.getJSONArray("bbox")
                        add(
                            LinkRegion(
                                targetUuid = json.getString("targetUuid"),
                                page = json.optInt("page", 0),
                                strokeIds = List(ids.length()) { ids.getLong(it) },
                                bbox = List(box.length()) { box.getDouble(it).toFloat() },
                            )
                        )
                    }
                }
            }
        }.getOrElse { emptyList() }
    }

    /**
     * Guarda el texto OCR por página en annotations.json (RF-25, RF-33).
     * Preserva las demás claves del archivo (links, highlights futuros) y
     * jamás toca la capa de ink.
     */
    fun saveOcrText(uuid: String, pages: List<String>) {
        val dir = File(notesDir, uuid)
        dir.mkdirs()
        val file = File(dir, "annotations.json")
        val json = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
        json.put("ocrPages", JSONArray(pages))
        file.writeText(json.toString())
    }

    /**
     * Texto OCR indexado de la nota, todas las páginas concatenadas. Archivo
     * ausente o corrupto ⇒ cadena vacía (RNF-07: nunca compromete el ink).
     */
    fun loadOcrText(uuid: String): String {
        val file = File(File(notesDir, uuid), "annotations.json")
        if (!file.exists()) return ""
        return runCatching {
            val pages = JSONObject(file.readText()).optJSONArray("ocrPages") ?: return ""
            (0 until pages.length()).joinToString("\n") { pages.getString(it) }
        }.getOrElse { "" }
    }

    fun saveMeta(meta: NoteMeta) {
        val dir = File(notesDir, meta.uuid)
        dir.mkdirs()
        val json = JSONObject()
            .put("uuid", meta.uuid)
            .put("title", meta.title)
            .put("createdAtMillis", meta.createdAtMillis)
            .put("modifiedAtMillis", meta.modifiedAtMillis)
            .put("titleNudgeCount", meta.titleNudgeCount)
            .put("pageCount", meta.pageCount)
            .put("lastPageIndex", meta.lastPageIndex)
            .put("tags", JSONArray(meta.tags))
        meta.notebookId?.let { json.put("notebookId", it) }
        File(dir, "meta.json").writeText(json.toString())
    }

    private fun readMeta(dir: File): NoteMeta? {
        val file = File(dir, "meta.json")
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(file.readText())
            NoteMeta(
                uuid = json.getString("uuid"),
                title = json.getString("title"),
                createdAtMillis = json.getLong("createdAtMillis"),
                modifiedAtMillis = json.getLong("modifiedAtMillis"),
                titleNudgeCount = json.optInt("titleNudgeCount", 0),
                pageCount = json.optInt("pageCount", 1),
                lastPageIndex = json.optInt("lastPageIndex", 0),
                notebookId = json.optString("notebookId").ifEmpty { null },
                tags = json.optJSONArray("tags")?.let { arr ->
                    List(arr.length()) { arr.getString(it) }
                } ?: emptyList(),
            )
        }.getOrNull()
    }

    /**
     * Guarda los trazos de una página en ink.bin. Escritura atómica (archivo
     * temporal + rename) para que un cierre abrupto no corrompa el ink existente.
     * Formato v2 (RF-03/04): un encabezado de versión, y por trazo su color
     * (ARGB) y grosor —el pincel es intrínseco a la tinta, no metadata— seguidos
     * de la codificación nativa de androidx.ink del batch de entradas. Los IDs
     * estables (puente ink↔link) se escriben aparte en annotations.json (ver
     * [saveStrokeIds]).
     */
    fun saveStrokes(uuid: String, pageIndex: Int, strokes: List<IdStroke>) {
        val pagesDir = File(File(notesDir, uuid), "pages")
        pagesDir.mkdirs()
        val tmp = File(pagesDir, "$pageIndex.ink.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(INK_FORMAT_VERSION)
            out.writeInt(strokes.size)
            for (item in strokes) {
                // Familia (pressurePen) y epsilon son constantes del MVP: no se
                // serializan, se reponen al leer. Basta color+grosor para
                // reconstruir el Brush completo.
                out.writeInt(item.stroke.brush.colorIntArgb)
                out.writeFloat(item.stroke.brush.size)
                val bytes = ByteArrayOutputStream()
                    .also { item.stroke.inputs.encode(it) }
                    .toByteArray()
                out.writeInt(bytes.size)
                out.write(bytes)
            }
        }
        tmp.renameTo(File(pagesDir, "$pageIndex.ink"))
        saveStrokeIds(uuid, pageIndex, strokes.map { it.id })
    }

    /**
     * Registro de IDs de trazo por página en annotations.json (`strokeIds`),
     * alineado por posición al orden de los trazos del `.ink`. Mantiene ink.bin
     * inmutable: los IDs (puente ink↔link) viven del lado de anotaciones.
     */
    private fun saveStrokeIds(uuid: String, pageIndex: Int, ids: List<Long>) {
        val dir = File(notesDir, uuid)
        dir.mkdirs()
        val file = File(dir, "annotations.json")
        val json = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
        val map = json.optJSONObject("strokeIds") ?: JSONObject()
        map.put(pageIndex.toString(), JSONArray(ids))
        json.put("strokeIds", map)
        file.writeText(json.toString())
    }

    private fun loadStrokeIds(uuid: String, pageIndex: Int): List<Long>? {
        val file = File(File(notesDir, uuid), "annotations.json")
        if (!file.exists()) return null
        return runCatching {
            val map = JSONObject(file.readText()).optJSONObject("strokeIds") ?: return null
            val arr = map.optJSONArray(pageIndex.toString()) ?: return null
            List(arr.length()) { arr.getLong(it) }
        }.getOrNull()
    }

    /**
     * Carga los trazos de una página como [IdStroke]. El color y grosor de cada
     * trazo se leen de ink.bin (formato v2) y reconstruyen su pincel; [brush]
     * solo aporta las partes constantes del MVP (familia pressurePen y epsilon).
     * Los IDs se adjuntan del registro alineado por posición; si falta o está
     * desalineado, se asignan IDs nuevos estables. Un ink.bin de formato anterior
     * (sin color/grosor) se descarta como página en blanco —la retrocompat se
     * rompió a propósito con la paleta (Fase 6)—; esto además evita interpretar
     * bytes viejos como longitudes enormes. Archivo inexistente o ilegible ⇒
     * lista vacía en vez de tumbar la app.
     */
    fun loadStrokes(uuid: String, pageIndex: Int, brush: Brush): List<IdStroke> {
        val file = File(File(notesDir, uuid), "pages/$pageIndex.ink")
        if (!file.exists()) return emptyList()
        val ids = loadStrokeIds(uuid, pageIndex)
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != INK_FORMAT_VERSION) return@use emptyList<IdStroke>()
                val count = input.readInt()
                buildList {
                    repeat(count) { index ->
                        val colorIntArgb = input.readInt()
                        val size = input.readFloat()
                        val len = input.readInt()
                        val bytes = ByteArray(len)
                        input.readFully(bytes)
                        val batch = StrokeInputBatch.decode(ByteArrayInputStream(bytes))
                        val penBrush = Brush.createWithColorIntArgb(
                            family = brush.family,
                            colorIntArgb = colorIntArgb,
                            size = size,
                            epsilon = brush.epsilon,
                        )
                        val id = ids?.getOrNull(index) ?: newStrokeId()
                        add(IdStroke(id, Stroke(penBrush, batch)))
                    }
            .put("paperStyle", meta.paper.style.name)
            .put("paperSpacing", meta.paper.spacing.toDouble())
            .put(
                "pageSizesMm",
                JSONArray().also { array ->
                    for (size in meta.pageSizes) {
                        array.put(
                            JSONArray()
                                .put(size.widthMm.toDouble())
                                .put(size.heightMm.toDouble()),
                        )
                    }
                },
            )
                }
            }
        }.getOrElse { emptyList() }
    }

    /**
     * Exporta la nota completa a un PDF vectorial en la carpeta pública de
     * Descargas (RF-28/29, UC-10): el ink va como geometría vectorial (no
     * rasterizada) reusando el renderer de la app, y los halos de link (RF-23a)
     * se dibujan como overlay. Devuelve el Uri del archivo, o null si falló.
     */
    fun exportNoteToPdf(meta: NoteMeta): Uri? = PdfExporter(appContext).export(this, meta)
}
                // Ausente (meta.json viejo) o valor inválido ⇒ blanco por default.
                paper = PaperBackground(
                    style = runCatching {
                        PaperStyle.valueOf(json.optString("paperStyle", PaperStyle.BLANK.name))
                    }.getOrDefault(PaperStyle.BLANK),
                    spacing = json.optDouble(
                        "paperSpacing", DEFAULT_PAPER_SPACING.toDouble(),
                    ).toFloat(),
                ),
                // Por página; ausente ⇒ migra el tamaño uniforme legacy o Carta.
                pageSizes = resolvePageSizes(json, json.optInt("pageCount", 1)),
