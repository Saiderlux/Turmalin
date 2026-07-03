# Documento de Requerimientos — App de Notas Manuscritas Conectadas

**Nombre de trabajo:** Trazo *(placeholder, cambiar cuando tengas nombre definitivo)*
**Plataforma:** Android nativo (Kotlin + Jetpack Compose)
**Dispositivo de prueba:** Samsung Galaxy Tab S6 Lite (4GB RAM, S Pen)
**Tipo de proyecto:** Personal, sin fecha límite, con intención de evolucionar a producto público

---

## 1. Visión general

Una app de notas para tablets donde el usuario escribe a mano de forma completa y natural — sin transcribir ni procesar — y organiza el conocimiento conectando notas entre sí mediante links manuales, igual que un sistema tipo Obsidian pero con tinta digital como unidad de contenido en vez de texto plano.

**Problema que resuelve:** existe una brecha entre apps de escritura a mano (GoodNotes, Squid, Samsung Notes), que no tienen forma de conectar ideas entre notas, y apps de gestión de conocimiento (Obsidian, Heptabase), que no tienen escritura a mano nativa fluida. No existe ninguna app en Android que resuelva ambas cosas a la vez.

**Principio de diseño rector:** cero fricción en el momento de captura. El usuario nunca debe pensar en organizar mientras escribe — todo lo estructural (título, tags, links) sucede antes o después de escribir, nunca durante.

---

## 2. Alcance del MVP

### Incluido en el MVP
- Escritura a mano fluida con soporte S Pen (Ink API)
- Notas con páginas libres, agregadas manualmente
- Organización en cuadernos (carpetas)
- Creación de links mediante lazo de selección
- Links bidireccionales automáticos
- Vista de grafo con nodos huérfanos diferenciados
- Búsqueda por contenido (vía índice OCR)
- Título editable, sugerido por OCR al cerrar la nota
- Tags manuales por teclado
- Herramientas básicas: lápiz (grosor variable), colores, goma (trazo y área), papel con líneas/cuadrícula
- Exportación a PDF con links visibles como overlay
- Almacenamiento 100% local, vault como carpeta visible del sistema de archivos

### Explícitamente fuera del MVP (ver sección 9 para roadmap)
- Marcatextos (requiere blend de transparencia)
- Tipos de lápiz adicionales (pluma, pincel)
- Sugerencia automática de tags por OCR
- Plantillas de papel adicionales (puntos, isométrico)
- Lasso para mover/redimensionar trazos existentes
- Zoom de escritura amplificada
- Bridge/plugin de exportación a Obsidian
- Sincronización en la nube

---

## 3. Glosario de conceptos clave

| Término | Definición |
|---|---|
| **Vault** | Carpeta raíz local que contiene todos los datos de la app. Visible y copiable desde el explorador de archivos del sistema. |
| **Cuaderno** | Unidad de organización (carpeta). No es un nodo del grafo, solo agrupa notas visualmente. |
| **Nota** | Unidad atómica del grafo. Tiene título, uno o más páginas, y es el objeto que se linkea. Identificada por un UUID estable. |
| **Página** | Subdivisión de una nota. El usuario decide cuántas tiene cada nota, añadidas con botón explícito. |
| **Región linkeada** | Área de ink seleccionada con el lazo que tiene un link asociado. Vive en la capa de anotaciones, nunca modifica el ink. |
| **Link manual** | Conexión creada explícitamente por el usuario (lazo o arrastre en grafo). Fuente de verdad del grafo. |
| **Nota huérfana** | Nota sin ningún link entrante ni saliente. Se muestra en el grafo con color atenuado diferenciado. |
| **Capa de ink** | Datos de trazos vectoriales. Inmutable una vez trazado. |
| **Capa de anotaciones** | Links, highlights, texto OCR indexado. Mutable, vive independiente del ink. |

---

## 4. Requerimientos funcionales

### 4.1 Captura de ink
- **RF-01**: El sistema debe capturar trazos del S Pen con datos de posición, presión, inclinación y timestamp, usando `androidx.ink` (Ink API).
- **RF-02**: El sistema debe implementar palm rejection para evitar trazos accidentales al apoyar la mano.
- **RF-03**: El usuario debe poder seleccionar grosor de trazo entre al menos 3 tamaños o mediante slider.
- **RF-04**: El usuario debe poder seleccionar color de un set de 8-10 colores básicos.
- **RF-05**: El usuario debe poder borrar por trazo individual o por área (goma).
- **RF-05c**: La goma se activa de dos formas complementarias: (1) selección persistente desde la barra de herramientas, como cualquier otra herramienta, y (2) atajo temporal manteniendo presionado el botón físico del stylus mientras se dibuja — vía `MotionEvent.isButtonPressed(BUTTON_STYLUS_PRIMARY)`, sin dependencias de SDK propietario. Al soltar el botón, la app vuelve automáticamente a la herramienta que estaba activa antes del atajo.
- **RF-05a**: El borrado de ink no debe eliminar links de forma directa por diseño de capas, pero sí como consecuencia natural: si el borrado es parcial, el overlay del link se recalcula al área de ink remanente. Si el borrado elimina todo el ink de la región, el link se elimina junto con él.
- **RF-05b**: Al eliminarse un link por borrado total de su región, el sistema debe mostrar la notificación no bloqueante estándar ("Link eliminado · Deshacer", ver RF-34) permitiendo revertir la acción sin interrumpir el flujo de borrado.
- **RF-06**: El sistema debe ofrecer papel con líneas y papel con cuadrícula como opciones de fondo.

### 4.2 Notas y páginas
- **RF-07**: El usuario debe poder crear una nota nueva sin ningún campo obligatorio previo — el teclado nunca aparece antes de escribir.
- **RF-08**: Cada nota nueva debe iniciar con título "Sin título" editable en cualquier momento.
- **RF-09**: El usuario debe poder añadir páginas a una nota mediante un botón explícito (no automático por scroll).
- **RF-10**: No debe existir límite duro de páginas por nota; el sistema debe sugerir (no forzar) dividir notas que superen 15 páginas, umbral configurable en `settings.json`, usando la notificación no bloqueante estándar (ver RF-34).
- **RF-11**: Al cerrar una nota que sigue con título "Sin título", el sistema debe mostrar la notificación no bloqueante estándar (ver RF-34) sugiriendo añadir título, sin bloquear ni repetirse más de dos veces.
- **RF-12**: El sistema debe sugerir un título basado en la primera línea del texto OCR, editable por el usuario.

### 4.3 Organización
- **RF-13**: El usuario debe poder crear, renombrar y eliminar cuadernos.
- **RF-14**: El usuario debe poder mover una nota entre cuadernos.
- **RF-15**: La pantalla de inicio debe mostrar galería de notas y cuadernos, ordenable por fecha, título, cuaderno o tags.
- **RF-16**: El usuario debe poder añadir tags a una nota mediante teclado.

### 4.4 Links y grafo
- **RF-17**: El usuario debe poder seleccionar una región de ink (texto o dibujo) con un lazo y crear un link hacia otra nota mediante lista de búsqueda.
- **RF-18**: Los links deben ser bidireccionales: si la Nota A linkea a la Nota B, la Nota B debe mostrar que es referenciada por A sin acción adicional del usuario.
- **RF-19**: El sistema debe proveer una vista de grafo donde cada nota es un nodo.
- **RF-20**: Las notas huérfanas (sin links) deben mostrarse en el grafo con color visualmente distinto y notorio.
- **RF-21**: El usuario debe poder crear un link arrastrando un nodo hacia otro directamente en la vista de grafo (conecta notas completas, no regiones).
- **RF-22**: Tocar un nodo en el grafo debe abrir la nota correspondiente.
- **RF-23**: Los links no deben modificar la capa de ink bajo ninguna circunstancia — se representan como overlay en la capa de anotaciones.
- **RF-23a**: El overlay de un link manual debe seguir la forma exacta de la región seleccionada (vía geometría de Ink API), renderizado como tinte semitransparente en un único color fijo reservado exclusivamente para links, excluido por completo de la paleta de colores de pluma disponible para el usuario (no un color calculado dinámicamente). Links sugeridos (futuro, v2) se distinguen con contorno punteado sin relleno, mismo color base.
- **RF-23b**: La nota debe mostrar un badge en la barra superior con el número de referencias entrantes (backlinks). Tocarlo despliega un panel lateral o bottom sheet listando las notas de origen, sin usar gestos de swipe para evitar conflicto con la navegación entre páginas.

### 4.5 Búsqueda e indexado
- **RF-24**: El sistema debe ejecutar OCR local (ML Kit) sobre cada nota al momento de cerrarla, no en tiempo real durante la escritura.
- **RF-25**: El texto OCR resultante debe guardarse como índice de búsqueda, separado del ink original.
- **RF-26**: El usuario debe poder buscar notas por contenido de texto (título, tags, y texto OCR indexado).
- **RF-27**: Los resultados de búsqueda deben ser instantáneos, sin ejecutar OCR en el momento de la consulta.

### 4.6 Exportación y portabilidad
- **RF-28**: El usuario debe poder exportar una nota a PDF con el ink en formato vectorial (no rasterizado).
- **RF-29**: El PDF exportado debe incluir los links como elementos visibles (overlay), no solo el ink puro.
- **RF-30**: El vault debe residir como carpeta accesible del sistema de archivos del dispositivo, sin sandboxing que impida backup manual.
- **RF-31**: El UUID de cada nota debe ser estable y no cambiar al renombrar, mover de cuaderno, o editar tags.

### 4.7 Almacenamiento
- **RF-32**: Todos los datos deben almacenarse localmente, sin dependencia de servidor o cuenta de usuario.
- **RF-33**: La estructura de datos debe separar en archivos independientes: ink (`ink.bin`, usando la serialización nativa de `androidx.ink.storage` organizada por página — no un formato binario propio), anotaciones/links/OCR (`annotations.json`), metadata (`meta.json`), y un índice global de links (`graph.json`).

---

### 4.8 Patrón de interacción: notificaciones no bloqueantes

- **RF-34**: El sistema debe implementar un único componente reutilizable de notificación no bloqueante, usado en todos los casos de sugerencia o deshacer (sugerencia de título, sugerencia de dividir nota, deshacer de link eliminado, y futuros casos similares). Características:
  - Aparece en una esquina inferior de la pantalla, fuera del área activa de escritura.
  - Incluye una barra de progreso visual indicando el tiempo restante antes de auto-descartarse (duración de referencia: 4-5 segundos).
  - Ofrece una única acción principal contextual (ej. "Deshacer", "Añadir título", "Dividir nota").
  - Se descarta sola al completar la barra de progreso si el usuario la ignora, sin requerir interacción.
  - Nunca bloquea el canvas de escritura ni intercepta gestos de ink en curso.

---

## 5. Requerimientos no funcionales

- **RNF-01 (Rendimiento — ink):** La latencia percibida entre el trazo del S Pen y su renderizado en pantalla debe ser imperceptible (objetivo referencial: por debajo de 20ms) en la Tab S6 Lite con 4GB de RAM.
- **RNF-02 (Rendimiento — OCR):** El procesamiento OCR no debe ejecutarse mientras el usuario escribe activamente; solo al cerrar la nota, para no competir por recursos con el renderizado de ink.
- **RNF-03 (Rendimiento — búsqueda):** Las consultas de búsqueda deben resolver contra índice pre-computado, sin analizar ink en tiempo de consulta.
- **RNF-04 (Privacidad):** Ningún dato del usuario debe transmitirse a servidores externos. Toda la inteligencia (OCR, búsqueda, sugerencias) corre on-device.
- **RNF-05 (Portabilidad):** El usuario debe poder copiar manualmente su vault completo a otro almacenamiento sin depender de una función de exportación de la app.
- **RNF-06 (Compatibilidad):** La app debe funcionar correctamente en Android con soporte S Pen; el target mínimo de API debe alinearse con los requisitos de `androidx.ink`.
- **RNF-07 (Resiliencia de datos):** Un error en la capa de anotaciones (links corruptos, índice OCR dañado) nunca debe comprometer la legibilidad de la capa de ink.

---

## 6. Casos de uso

### UC-01 — Crear una nota nueva
- **Actor:** Usuario
- **Precondición:** La app está abierta en la pantalla de inicio o dentro de un cuaderno.
- **Flujo principal:**
  1. El usuario toca "Nueva nota".
  2. El sistema crea una nota vacía con título "Sin título" y una página en blanco.
  3. El usuario comienza a escribir inmediatamente, sin campos previos.
- **Postcondición:** Existe una nota nueva con UUID asignado, visible en la galería.

### UC-02 — Escribir en una nota
- **Actor:** Usuario
- **Precondición:** Una nota está abierta en modo edición.
- **Flujo principal:**
  1. El usuario traza con el S Pen sobre la página.
  2. El sistema captura los trazos vía Ink API y los renderiza en tiempo real con baja latencia.
  3. El sistema descarta contactos de palma detectados como accidentales.
- **Postcondición:** Los trazos quedan almacenados en la capa de ink de la página activa.

### UC-03 — Añadir página a una nota
- **Actor:** Usuario
- **Precondición:** Una nota está abierta.
- **Flujo principal:**
  1. El usuario toca el botón "Añadir página".
  2. El sistema crea una página nueva en blanco y navega a ella.
- **Postcondición:** La nota tiene una página adicional; las regiones linkeadas de páginas anteriores mantienen su referencia página+coordenadas.

### UC-04 — Cerrar nota y asignar título
- **Actor:** Usuario
- **Precondición:** Una nota está abierta con al menos contenido en una página.
- **Flujo principal:**
  1. El usuario cierra la nota (navega hacia atrás o a la galería).
  2. El sistema ejecuta OCR en background sobre el contenido de la nota.
  3. Si el título sigue en "Sin título", el sistema sugiere un título basado en la primera línea del OCR.
  4. El sistema muestra un toast discreto invitando a confirmar o editar el título si no se ha personalizado.
- **Flujo alternativo:** El usuario ignora el toast — la nota conserva el título sugerido o "Sin título" sin bloqueo.
- **Postcondición:** El texto OCR queda indexado para búsqueda; el título queda asignado o pendiente.

### UC-05 — Crear un link mediante lazo de selección
- **Actor:** Usuario
- **Precondición:** Una nota está abierta y contiene al menos una región de ink.
- **Flujo principal:**
  1. El usuario activa la herramienta de lazo.
  2. El usuario dibuja un lazo alrededor de una región de ink (texto o dibujo).
  3. El sistema abre una lista de búsqueda de notas existentes.
  4. El usuario selecciona la nota destino.
  5. El sistema crea el link en la capa de anotaciones, asociado a las coordenadas de la región seleccionada.
- **Postcondición:** La región queda marcada visualmente (overlay) como linkeada; el link es bidireccional y visible desde ambas notas.

### UC-06 — Ver referencia inversa (backlink)
- **Actor:** Usuario
- **Precondición:** La Nota B es destino de un link creado desde la Nota A.
- **Flujo principal:**
  1. El usuario abre la Nota B.
  2. El sistema muestra, en un panel o gesto dedicado, que la Nota A la referencia.
- **Postcondición:** El usuario puede navegar a la Nota A desde ese indicador.

### UC-07 — Ver la vista de grafo
- **Actor:** Usuario
- **Precondición:** Existe al menos una nota en el vault.
- **Flujo principal:**
  1. El usuario navega a la vista de grafo.
  2. El sistema renderiza cada nota como nodo; nodos con links en color normal, nodos huérfanos en color atenuado.
  3. El usuario puede hacer zoom, pan, y tocar un nodo para abrir la nota.
- **Postcondición:** Ninguna (vista de solo lectura salvo interacción de UC-08).

### UC-08 — Crear link desde la vista de grafo
- **Actor:** Usuario
- **Precondición:** Vista de grafo abierta con al menos dos notas.
- **Flujo principal:**
  1. El usuario arrastra un nodo A hacia un nodo B.
  2. El sistema crea un link bidireccional entre la Nota A y la Nota B completas.
- **Postcondición:** El grafo refleja el nuevo link; ninguna de las dos notas queda como huérfana si antes lo era.

### UC-09 — Buscar una nota por contenido
- **Actor:** Usuario
- **Precondición:** Existen notas con OCR ya indexado.
- **Flujo principal:**
  1. El usuario escribe un término en el buscador.
  2. El sistema consulta el índice de texto (título, tags, OCR) y devuelve coincidencias instantáneas.
  3. El usuario toca un resultado para abrir la nota correspondiente.
- **Postcondición:** Ninguna, salvo navegación.

### UC-10 — Exportar una nota a PDF
- **Actor:** Usuario
- **Precondición:** Una nota está abierta o seleccionada desde la galería.
- **Flujo principal:**
  1. El usuario selecciona "Exportar a PDF".
  2. El sistema genera un PDF con el ink en formato vectorial y los links visibles como overlay.
  3. El sistema guarda el PDF en almacenamiento accesible por el usuario.
- **Postcondición:** Existe un archivo PDF portable fuera del vault propietario de la app.

### UC-11 — Crear y organizar cuadernos
- **Actor:** Usuario
- **Precondición:** Ninguna.
- **Flujo principal:**
  1. El usuario crea un cuaderno nuevo con nombre.
  2. El usuario mueve notas existentes o nuevas dentro de ese cuaderno.
- **Postcondición:** Las notas quedan agrupadas visualmente en la galería sin afectar su rol como nodos independientes del grafo.

### UC-12 — Borrar contenido de una nota
- **Actor:** Usuario
- **Precondición:** Una nota está abierta en modo edición.
- **Flujo principal:**
  1. El usuario activa la goma.
  2. El usuario borra por trazo individual (toca un trazo) o por área (arrastra sobre una zona).
- **Postcondición:** Los trazos afectados se eliminan de la capa de ink. Si algún trazo borrado tenía una región linkeada asociada, el overlay se recalcula (borrado parcial) o el link se elimina junto con un snackbar de deshacer (borrado total, ver RF-05a y RF-05b).

---

## 7. Modelo de datos (resumen)

```
vault/
  notes/
    {uuid}/
      ink.bin          → trazos vectoriales por página (inmutable)
      annotations.json → links, highlights, texto OCR y sus regiones
      meta.json         → título, timestamps, tags, cuaderno asociado
      thumb.webp        → miniatura generada para galería
  graph.json            → índice global de links entre notas (uuid → uuid[])
  search_index.json     → índice invertido de términos OCR (v2, para sugerencias)
  settings.json
```

Reglas clave:
- El UUID de la nota es el identificador permanente (RF-31); el título es un alias legible que puede cambiar libremente.
- La capa de ink nunca se modifica por operaciones de linkeo — solo por escritura o borrado directo del usuario.
- `graph.json` existe para no tener que leer todas las notas al abrir la vista de grafo.

---

## 8. Stack técnico definido

| Componente | Elección |
|---|---|
| Lenguaje | Kotlin |
| UI framework | Jetpack Compose |
| Motor de ink | `androidx.ink` (Ink API de Google) |
| Selección/lazo | Geometry module de Ink API |
| OCR | ML Kit (on-device) |
| Vista de grafo | Custom sobre Compose Canvas |
| Almacenamiento | Sistema de archivos local, formato propio |
| Entorno de desarrollo | Android Studio (Arch Linux), dispositivo físico Tab S6 Lite vía adb |

---

## 9. Roadmap por fases

1. **Prototipo de ink** — canvas mínimo, medir latencia real en la Tab S6 Lite.
2. **Motor de notas** — crear/escribir/guardar/leer, múltiples páginas.
3. **Galería y cuadernos** — pantalla de inicio, títulos, orden.
4. **OCR e indexado** — ML Kit al cerrar nota, búsqueda funcional.
5. **Links y grafo** — lazo, bidireccionalidad, vista de grafo, huérfanas.
6. **Paleta y exportación** — herramientas de dibujo del MVP, tags, PDF con links.
7. **v2** — marcatextos, tipos de lápiz, sugerencias de tags por OCR, plantillas, lasso de edición, zoom de escritura, bridge a Obsidian.

---

## 10. Decisiones resueltas en esta ronda

1. **Borrado de ink con link asociado:** borrado parcial recalcula el overlay; borrado total elimina el link, con snackbar de deshacer no bloqueante.
2. **Formato de `ink.bin`:** se usa la serialización nativa de `androidx.ink.storage`, no un formato propio desde cero.
3. **Overlay visual del link:** tinte semitransparente siguiendo la forma exacta de la selección, en un único color fijo excluido de la paleta de plumas — sin cálculo dinámico de contraste.
4. **Umbral de páginas:** 15 páginas, configurable, sugerencia única no bloqueante, sin variar según si la nota tiene links (se valida con uso real una vez exista el prototipo).
5. **Interacción de backlinks:** badge en barra superior + panel lateral/bottom sheet, sin gestos de swipe.

Todas las preguntas abiertas anteriores quedaron resueltas. El documento está listo para pasar al prototipo.
