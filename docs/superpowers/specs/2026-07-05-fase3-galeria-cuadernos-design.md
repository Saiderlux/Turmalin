# Fase 3 — Galería y cuadernos (diseño)

Fecha: 2026-07-05. Alcance: RF-13, RF-14, RF-15 y UC-01/UC-11 según
`docs/requerimientos_apps_notas.md` (sección 7 como fuente de verdad del
modelo de datos).

## Decisiones

- **Cuadernos lógicos, no carpetas físicas.** Las notas viven siempre en
  `vault/notes/{uuid}/`; el cuaderno es el campo `notebookId` en `meta.json`
  (null = raíz). El registro de cuadernos vive en `vault/notebooks.json`
  (lista de `{id, name}`). Mover una nota = reescribir su `meta.json`; el
  UUID y la capa de ink no se tocan nunca (RF-31, regla de arquitectura).
- **UI estilo Obsidian.** La galería raíz muestra tarjetas de cuaderno más
  las notas sueltas; tocar un cuaderno navega a su contenido (misma pantalla,
  estado `openNotebookId`). Crear notas dentro de una carpeta es opcional:
  el FAB crea la nota en el cuaderno abierto, o en raíz.
- **Eliminar cuaderno nunca borra notas**: sus notas pasan a raíz.
- **Sin Material.** El proyecto usa solo `compose.foundation`; el menú de
  orden usa `Popup` y los diálogos `androidx.compose.ui.window.Dialog`.

## Componentes

- `NoteRepository` (extendido): `notebookId` en `NoteMeta`/`meta.json`;
  `Notebook(id, name)`; `listNotebooks`, `createNotebook`, `renameNotebook`,
  `deleteNotebook` (reubica notas a raíz), `moveNote`; `createNote(notebookId)`.
- `GalleryViewModel` (nuevo): `StateFlow<GalleryUiState>` con `notes`,
  `notebooks`, `openNotebookId`, `sortOrder` (`MODIFIED | TITLE | NOTEBOOK`).
  El filtrado/orden es una función pura (`galleryNotes`) testeable sin
  Android. Requiere `androidx.lifecycle:lifecycle-viewmodel-compose`.
- `GalleryScreen` (rehecha): `LazyVerticalGrid` adaptativa; tarjetas de
  cuaderno (solo en raíz) + tarjetas de nota; menú de orden; FAB «+»;
  long-press en nota → mover de cuaderno; long-press en cuaderno →
  renombrar/eliminar; botón «+ Cuaderno» en raíz.
- `MainActivity`: reemplaza el estado local `notes` por el ViewModel;
  la navegación galería↔nota sigue siendo por estado.

## Errores y robustez

- Un `notebookId` huérfano (cuaderno borrado con app cerrada, vault copiado
  a mano) se trata como raíz al listar — nunca desaparece una nota de la UI.
- `notebooks.json` ilegible ⇒ lista vacía (mismo criterio que `readMeta`).

## Tests

- Unit test de `galleryNotes`: filtrado raíz/cuaderno, huérfanos a raíz y
  los tres órdenes.
