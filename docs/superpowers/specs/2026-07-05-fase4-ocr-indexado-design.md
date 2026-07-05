# Fase 4 — OCR e indexado (diseño)

> **Actualización 2026-07-05 (misma fecha, ronda 2):** el motor descrito aquí
> (Text Recognition sobre bitmap) reconocía mal la manuscrita real y fue
> reemplazado por **ML Kit Digital Ink Recognition**: consume los puntos de
> los trazos (sin rasterizar), modelo de español con descarga única vía Play
> Services (excepción aprobada a "cero red", ver CLAUDE.md) y segmentación de
> líneas propia (`groupStrokesIntoLines`). Si el modelo no está descargado,
> el cierre de nota pospone el indexado sin tocar `annotations.json`. La
> búsqueda además normaliza diacríticos ("excepcion" ≡ "excepción"). La
> sección de rasterización de abajo queda como registro histórico.

Fecha: 2026-07-05. Alcance: RF-24..27, RF-11/12, RF-34, UC-04, UC-09,
RNF-02/03/04/07 según `docs/requerimientos_apps_notas.md`.

## Decisiones

- **OCR solo al cerrar y solo si la tinta cambió (dirty flag).** `NoteScreen`
  mantiene `inkChanged`, que se enciende únicamente desde el canvas cuando hay
  trazo nuevo o borrado real (`onInkModified` en los tres puntos de mutación:
  handoff wet→dry, goma de trazo, goma parcial). Abrir a leer o hacer pan/zoom
  no dispara ML Kit ni reescribe `annotations.json`.
- **Rasterización efímera.** Por página: bounding box de la tinta
  (`BoxAccumulator`) + margen de 32 px, bitmap ARGB_8888 con lado mayor ≤
  2048 px (RAM de la Tab S6 Lite), dibujado con el mismo
  `CanvasStrokeRenderer` del canvas, `InputImage.fromBitmap` → ML Kit Latin
  bundled (cero red, RNF-04). El bitmap se recicla; nada se escribe a disco.
- **`annotations.json`** guarda `ocrPages` (texto por página) preservando
  claves futuras (links, highlights). El ink jamás se toca; archivo corrupto
  ⇒ cadena vacía (RNF-07).
- **Título sugerido (RF-12)**: al cerrar una nota "Sin título" (máx. 2 avisos,
  RF-11), el `TransientNotice` existente (RF-34) ofrece la primera línea no
  vacía del OCR como título; «Usar» solo reescribe `meta.json`. Sin OCR útil,
  cae al aviso clásico de añadir título.
- **Búsqueda (RF-26/27)**: campo en la galería; el ViewModel carga en
  `refresh()` el texto OCR de todas las notas y filtra en memoria por título
  + OCR. Con consulta activa la búsqueda es global (ignora el cuaderno
  abierto, UC-09) y las tarjetas de cuaderno se ocultan. Nunca se ejecuta OCR
  al consultar.
- El `await` del `Task` de ML Kit es un wrapper `suspendCancellableCoroutine`
  propio para no añadir coroutines-play-services.

## Componentes

- `OcrIndexer.kt` (nuevo): `rasterizeStrokes`, `OcrIndexer.recognizePage`,
  `firstOcrLine` (pura, testeable).
- `NoteRepository`: `saveOcrText` / `loadOcrText` sobre `annotations.json`.
- `GalleryViewModel`: `query` + `ocrTexts` en el estado, `onNoteClosed(meta,
  inkChanged)` con OCR en `viewModelScope` (`Dispatchers.Default`), y el
  aviso de título (`TitleNudge`) movido aquí desde `MainActivity`.
- `GalleryScreen`: `SearchField` y aviso con título sugerido.

## Tests

- Unit tests de búsqueda (título, OCR, global con cuaderno abierto, consulta
  en blanco) y de `firstOcrLine`.
