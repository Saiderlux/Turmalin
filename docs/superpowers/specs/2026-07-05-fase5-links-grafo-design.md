# Fase 5 — Links y grafo (diseño)

Fecha: 2026-07-05. Alcance: RF-17..23b, RF-33, UC-05..08 según
`docs/requerimientos_apps_notas.md`. RF-22 fue modificado en esta fase
(interacción por selección estilo Obsidian) y se añadió RF-22a (radio de nodo
dinámico) a pedido del usuario.

## Pilares

### 1. `graph.json` (RF-18, RF-33)

Índice global de links en `vault/graph.json`: diccionario `uuid → [uuids]`.
Cada arista se guarda en **ambas direcciones** (A→B y B→A), así los backlinks
de una nota son una lectura O(1) sin invertir el mapa. En `NoteRepository`:
`loadGraph()`, `addGraphLink(a, b)` (idempotente vía set, ignora a==b),
`addRegionLink(uuid, region)` y `loadRegionLinks(uuid)`. Corrupto o ausente ⇒
mapa/lista vacíos (RNF-07). La capa de ink nunca se toca (RF-23).

### 2. Lazo y overlay (RF-17, RF-23a, UC-05)

- Nueva herramienta `Tool.LASSO` en la barra. Con el lazo activo el S Pen no
  produce tinta: acumula una polilínea en coordenadas de **documento** (vía la
  misma inversa del `CanvasViewport` que usa la pluma), dibujada punteada
  mientras se traza.
- En pen-up (≥3 vértices) se cierra el polígono y abre un `BottomSheet` con el
  mismo filtrado instantáneo de la Fase 4 (`galleryNotes`, título + OCR
  indexado, sin tildes). La nota origen se excluye de las candidatas.
- Al elegir destino: `LinkRegion(targetUuid, page, polygon)` se guarda en el
  `annotations.json` de la nota origen (clave `links`, preservando `ocrPages`)
  y la arista en `graph.json`.
- El overlay se pinta como `Path` con relleno semitransparente de un color
  fijo reservado (`LINK_OVERLAY_COLOR`), excluido de la paleta de plumas
  (RF-23a), dentro del mismo `graphicsLayer` del viewport. El ink debajo queda
  intacto.
- Un tap de **un dedo** dentro de un polígono navega a la nota destino
  (`polygonContains`, ray casting). La palma se descarta porque se mueve, dura
  junto al pen (`stylusIsDown`) o no cae dentro de un polígono.

### 3. Backlinks (RF-23b, UC-06)

Badge `⇄ n` en la barra superior de la nota con el número de conexiones
(lectura directa de `graph.json`). Se abre por **toque, no swipe** (RF-23b:
libera el palm rejection). El panel lista las notas conectadas leyendo sus
títulos de `meta.json`; tocar una navega a esa nota. Crear o seguir un link no
enciende el dirty flag de tinta (el ink no cambia, no se dispara OCR).

### 4. `GraphScreen` (RF-19..22a, UC-07/08)

- **Layout**: Fruchterman–Reingold propio (`layoutGraph`, función pura y
  testeable) — repulsión k²/d entre todos los pares, atracción d²/k por
  arista, temperatura decreciente. Posiciones iniciales sembradas por el hash
  del uuid ⇒ el mismo vault dibuja siempre igual, sin persistir posiciones.
  O(n²) por iteración; suficiente para vaults personales (cientos de notas),
  se calcula una sola vez al abrir. Sin librería: ~60 líneas de Kotlin ganan a
  cualquier dependencia de grafos (todas exigen adaptar su salida a Compose
  Canvas igual).
- **Radio dinámico (RF-22a)**: `nodeRadius(degree)` crece con el número de
  conexiones, con tope.
- **Huérfanos (RF-20)**: grado 0 ⇒ color gris atenuado, radio mínimo.
- **Selección (RF-22)**: `selectedNodeUuid`. Un tap selecciona/deselecciona
  resaltando el nodo y sus vecinos directos a opacidad completa y atenuando el
  resto (nodos, títulos y aristas) al 20%. Doble tap abre la nota.
- **Drag-to-link (RF-21, UC-08)**: arrastrar un nodo lo mueve; soltarlo sobre
  otro crea la arista en `graph.json` y el nodo vuelve a su posición del
  layout.
- **Gestos unificados**: tap, doble tap, arrastre de nodo, pan de un dedo y
  pinch de dos dedos viven en **un solo** `awaitEachGesture`. Dos
  `pointerInput` rivales (p. ej. `detectTapGestures` + un detector de
  arrastre) se roban el gesto y el drag nunca dispara — verificado en
  dispositivo. El doble tap se detecta a mano con una ventana de 300 ms.

## Qué NO cambia / se posterga (v2)

- La capa de ink es solo-lectura para los links (RF-23).
- El UUID de la nota nunca se regenera.
- Links sugeridos (contorno punteado), edición del lazo sobre trazos y física
  animada en vivo del grafo quedan para v2.

## Tests

- `GraphLayoutTest`: layout vacío, determinismo, nodos dentro del lienzo,
  conectados más cerca que sueltos, aristas a uuids desconocidos ignoradas,
  radio creciente con tope.
- `PolygonContainsTest`: dentro/fuera, polígono cóncavo, <3 vértices.

## Verificación en dispositivo (Tab S6 Lite)

Lazo → sheet de búsqueda → destino → `graph.json` + overlay azul con la forma
exacta; tap en la región navega al destino; badge bidireccional; panel de
referencias lista la nota origen; grafo con radios dinámicos y huérfanos
atenuados; selección resalta vecinos y atenúa el resto; doble tap abre; drag
de un nodo sobre otro crea la arista (confirmado por `graph.json` y logs).
