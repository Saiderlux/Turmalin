# Plan v2 — Turmalin

**Estado:** propuesta, pendiente de aprobación para pasar a diseño/plan de implementación por feature.
**Base:** v1 completa (ver `docs/requerimientos_apps_notas.md`, secciones 2 y 9). Este documento retoma íntegramente las exclusiones del MVP (sección 2 "Explícitamente fuera del MVP" y sección 9.7) y las complementa con gaps detectados frente a GoodNotes 6, Samsung Notes, Noteful y Heptabase (research de julio 2026).

Cada ítem indica: **qué es**, **por qué** (gap detectado o pedido explícito) y **complejidad relativa** (S/M/L, sin estimaciones de tiempo — ver nota de alcance al final).

Todo ítem debe seguir respetando las reglas invariables de `CLAUDE.md`: capa de ink inmutable salvo por trazo directo del usuario, cero red, UUID estable, notificación no bloqueante única para sugerencias/deshacer.

---

## 1. Herramientas de escritura

### 1.1 Tipos de lápiz adicionales (pluma, pincel)
**Qué:** además del lápiz actual (pressurePen), sumar familias de trazo tipo pluma (línea fina, poca variación de presión) y pincel (variación de grosor marcada según presión/velocidad).
**Por qué:** GoodNotes 6 y Samsung Notes ofrecen 3 tipos base (bolígrafo, pluma, pincel) como estándar de la categoría; es el gap más citado por usuarios que migran desde esas apps.
**Complejidad:** M. `androidx.ink` ya expone `BrushFamily` con perfiles de textura distintos (ver decisión 7 del doc de requerimientos sobre cómo el pincel vive en `ink.bin` v2) — el trabajo es sumar un campo de familia al formato existente, no un motor de renderizado nuevo.

### 1.2 Marcatextos (highlighter)
**Qué:** herramienta de resaltado con blend de transparencia (multiply o similar) que no oculta el trazo/texto debajo.
**Por qué:** excluido explícitamente del MVP (sección 2); estándar en toda app de notas manuscritas competidora.
**Complejidad:** M. Requiere modo de blend distinto al de tinta normal en el renderer y una decisión de capa: ¿vive en `ink.bin` (es tinta) o en `annotations.json` (es anotación sobre ink existente)? Recomendación: vive en `ink.bin` como un `Brush` más con blend mode "highlighter" — es trazo del usuario, no metadata, igual que la decisión ya tomada para color/grosor.

### 1.3 Grosor con input numérico además del slider
**Qué:** junto al slider continuo de grosor (RF-03), un campo numérico editable para escribir el valor exacto en mm o pt.
**Por qué:** pedido explícito del usuario — el slider solo es impreciso para quien quiere repetir un grosor exacto entre sesiones.
**Complejidad:** S. Es UI sobre un valor que ya existe (`strokeWidth`), sin tocar el modelo de datos.

---

## 2. Papel y plantillas

### 2.1 Plantilla de puntos (dot grid)
**Qué:** tercer tipo de fondo de página junto a líneas y cuadrícula, con el mismo control de espaciado ajustable (RF-06).
**Por qué:** excluido explícitamente del MVP; es el formato preferido para bullet journaling y diagramación libre, presente en Samsung Notes, GoodNotes y Noteful.
**Complejidad:** S. Reutiliza el mecanismo de `PaperBackground.kt` existente para líneas/cuadrícula — solo cambia el patrón dibujado.

### 2.2 Tamaño de página con input numérico
**Qué:** junto al selector de formatos predefinidos (Carta, Oficio, A4, Legal — RF-06a), permitir escribir ancho × alto exactos en vez de solo elegir de la lista o mover un slider.
**Por qué:** pedido explícito del usuario — mismo argumento que 1.3, aplicado a dimensiones de página en vez de grosor de trazo.
**Complejidad:** S. El modelo ya soporta tamaño personalizado (RF-06a lo menciona); es exponer el campo numérico en la UI de ajustes de página.

### 2.3 Plantillas de papel personalizadas y guardables (tipos de nota)
**Qué:** el usuario define una combinación de fondo (líneas/cuadrícula/puntos + espaciado + tamaño de página) y la guarda como preset con nombre propio (ej. "Apuntes de clase", "Diagramas técnicos"). Al crear una nota nueva, puede elegir un preset en vez de reconfigurar cada vez.
**Por qué:** pedido explícito del usuario ("plantillas personalizadas... tipos de notas con su tamaño de cuadros o rayas"); ni GoodNotes ni Samsung Notes separan bien "preset reutilizable" de "plantilla decorativa" — es una oportunidad real de diferenciación, no solo paridad.
**Complejidad:** M. Nuevo archivo `templates.json` en el vault (mismo patrón que `settings.json`), CRUD simple de presets, y un paso opcional en el flujo de "nueva nota" que sigue sin bloquear la escritura inmediata (una nota nueva sin preset elegido sigue iniciando en Carta + blanco por default, ver RF-06a/RF-07 — el preset es atajo, nunca paso obligatorio).

---

## 3. UX/UI

### 3.1 Iconos en lugar de botones con texto
**Qué:** reemplazar los controles de texto de la toolbar (`InkToolbar.kt`) y menús por iconografía estándar Material, con tooltip/long-press para el label.
**Por qué:** pedido explícito del usuario; además reduce el ancho ocupado por la barra de herramientas en tablet, dejando más área de canvas visible — relevante en la Tab S6 Lite que ya tiene pantalla reducida.
**Complejidad:** S. Es swap de composables existentes (`Components.kt`, `InkToolbar.kt`) por `Icon` + `IconButton`, sin tocar lógica.

### 3.2 Revisión general de UX/UI
**Qué:** pase de pulido visual sobre pantallas existentes (galería, nota, grafo, ajustes) una vez estén claras las adiciones de v2 — jerarquía visual, espaciado, estados de carga/vacío, feedback táctil.
**Por qué:** pedido explícito del usuario; una vez la toolbar crece con más herramientas (1.1, 1.2) y plantillas (2.3), la superficie de UI actual necesita reorganizarse, no solo iconizarse.
**Complejidad:** L, y depende de que 1.1–2.3 estén definidos primero — recomiendo abordarlo al final de esta fase, no en paralelo. Candidato a diseño propio (invocar `frontend-design` cuando se llegue a planificar esta sección).

---

## 4. Gestión del conocimiento

Sección con gaps que **no pidió el usuario explícitamente** pero surgen de comparar con Heptabase/Obsidian, filtrados por lo que respeta "cero red, cero complejidad ajena al ink":

### 4.1 Sugerencia automática de tags por OCR — descartado
**Qué:** al cerrar una nota, sugerir tags candidatos extraídos del texto OCR.
**Por qué se descarta:** evaluado y rechazado por el usuario — se percibe como engorroso (una sugerencia más que interrumpe/hay que descartar, sobre un campo que ya es rápido de llenar a mano vía chips, ver RF-16). No se retoma salvo que surja una razón nueva.

### 4.2 Vista de tabla / propiedades de notas
**Qué:** vista alternativa a la galería tipo grid, listando notas como filas con columnas ordenables/filtrables (título, cuaderno, tags, fecha, nº de links entrantes/salientes).
**Por qué:** gap detectado frente a Heptabase (Table view) y Obsidian (Bases/Dataview) — para vaults grandes, la galería en grid deja de ser suficiente para auditar el conocimiento acumulado.
**Complejidad:** M. Es una vista de solo lectura sobre metadata que ya existe en `meta.json` y `graph.json`, sin nuevo almacenamiento.

### 4.3 Repaso espaciado sobre notas (spaced repetition)
**Qué:** marcar una nota o región como "tarjeta de repaso" y que el sistema la resurfacee periódicamente para reforzar memoria, similar a las flashcards de Heptabase.
**Por qué:** gap detectado; encaja con el perfil de usuario que estudia escribiendo a mano, pero es la feature de mayor riesgo de scope creep — convierte a Turmalin en una app de flashcards además de notas.
**Complejidad:** L. Requiere un scheduler propio (algoritmo tipo SM-2), persistencia de estado de repaso por nota/región, y una superficie de UI nueva (cola de repaso diario). **Recomendación:** evaluar como v3, no v2 — no viene pedido por el usuario y compite en alcance con el resto de esta fase.

### 4.4 Multi-pertenencia: una nota visible desde varios cuadernos sin duplicar — aprobado
**Qué:** hoy una nota vive en un único cuaderno (RF-14, "mover"). Permitir que aparezca listada en más de un cuaderno sin duplicar archivo ni UUID — el cuaderno pasa de "carpeta exclusiva" a "colección"/etiqueta de agrupación visual.
**Por qué:** gap detectado frente al modelo de "card en múltiples whiteboards" de Heptabase; evita el dilema de "¿en qué cuaderno la pongo si toca a dos temas?" que hoy se resuelve solo con tags. Confirmado por el usuario como buena idea a implementar.
**Complejidad:** M. Cambia el modelo de datos: `notebookId: String?` único en `meta.json` pasaría a `notebookIds: List<String>`. Es una decisión de esquema que conviene tomar temprano en v2 si se va a hacer, para no migrar datos dos veces — candidata a priorizarse antes que otros ítems M/L de esta lista.

---

## 5. Lasso de edición de trazos existentes

**Qué:** un segundo lazo, distinto del "Lazo de vínculo" (RF-17), cuyo propósito es seleccionar trazos ya escritos para moverlos, redimensionarlos o rotarlos — sin crear un link. Debe quedar nombrado y iconografiado de forma inconfundible frente al Lazo de vínculo (ver decisión de nombres en el glosario, sección 3 del doc de requerimientos) para que el usuario nunca confunda "seleccionar para reorganizar" con "seleccionar para vincular".
**Por qué:** excluido explícitamente del MVP (sección 2); gap frente a toda app de notas manuscritas madura — hoy en Turmalin un trazo mal ubicado solo se puede borrar y rehacer, no reposicionar.
**Complejidad:** L. Es la feature de mayor riesgo técnico de todo el plan: mover/redimensionar trazos existentes interactúa directamente con la regla invariable "la capa de ink es inmutable salvo por trazo directo del usuario" — mover SÍ es una escritura nueva sobre esa capa, hay que definir con cuidado si cuenta como "trazo nuevo" para RF-37 (undo) y qué pasa con una región linkeada (RF-23a) cuando su ink de referencia se desplaza: ¿el overlay se mueve con el trazo, o el link se invalida? Requiere diseño dedicado antes de estimarse en detalle — no alcanza con una sección de este documento.

---

## 6. Bridge / exportación a Obsidian

**Qué:** exportar la estructura del vault (notas, links, tags) a un formato que Obsidian pueda importar — probablemente un `.md` por nota con front-matter de tags y un link `[[uuid o título]]` por cada `LinkRegion`, más adjuntar el PDF de cada nota como referencia visual ya que el ink no es texto editable en Obsidian.
**Por qué:** excluido explícitamente del MVP; el propio pitch del proyecto se apoya en el modelo mental de Obsidian, así que un puente de salida (no sync, solo export unidireccional) cierra el círculo para quien ya usa Obsidian para el resto de su conocimiento en texto.
**Complejidad:** M. No rompe "cero red" (RF-32) porque es exportación a disco local, igual que el PDF (RF-28) — reutiliza el mismo flujo de SAF (`ACTION_CREATE_DOCUMENT`) ya construido para PDF. El riesgo no es técnico sino de alcance: definir bien que es exportación unidireccional (snapshot), no sincronización — sync bidireccional con Obsidian sí sería una dependencia de complejidad injustificada para este proyecto.

---

## Resumen de complejidad

| Sección | Ítem | Complejidad |
|---|---|---|
| 1. Escritura | 1.1 Tipos de lápiz | M |
| | 1.2 Marcatextos | M |
| | 1.3 Grosor numérico | S |
| 2. Papel/plantillas | 2.1 Puntos | S |
| | 2.2 Tamaño numérico | S |
| | 2.3 Plantillas guardables | M |
| 3. UX/UI | 3.1 Iconos | S |
| | 3.2 Revisión UX/UI general | L (depende del resto) |
| 4. Gestión conocimiento | 4.1 Tags sugeridos por OCR | descartado |
| | 4.2 Vista de tabla | M |
| | 4.3 Repaso espaciado | L — recomendado diferir a v3 |
| | 4.4 Multi-pertenencia a cuadernos | M — aprobado |
| 5. Lasso de edición | Mover/redimensionar trazos | L — requiere diseño dedicado |
| 6. Bridge Obsidian | Export unidireccional | M |

## Nota de alcance

Este documento es un mapa de candidatos, no un compromiso de orden de implementación ni de plazos — el proyecto es personal y sin fecha límite (`CLAUDE.md`). El siguiente paso natural es tomar los ítems S/M de mayor valor percibido (recomendación: empezar por 1.3, 2.1, 2.2, 3.1 — quick wins de complejidad S que no requieren diseño previo) y llevarlos uno a uno por `brainstorming` → `writing-plans`, en vez de planificar v2 completa de una sola vez.

Los ítems marcados L (3.2, 4.3, 5) necesitan su propia sesión de brainstorming dedicada antes de estimarse con más detalle — este documento los deja identificados, no diseñados.
