# CLAUDE.md

Instrucciones persistentes para Claude Code en este proyecto. Se cargan al inicio de cada sesión — mantener corto y con información que cambia decisiones, no lo que ya es inferible leyendo el código.

## Qué es este proyecto

App de notas manuscritas para tablet Android que conecta ideas mediante links, como Obsidian pero con tinta digital como unidad de contenido en vez de texto plano. Proyecto personal, sin fecha límite, con intención de eventualmente publicarse.

Documento completo de requerimientos y casos de uso: `docs/requerimientos_app_notas.md` — consultarlo antes de implementar cualquier feature nueva, es la fuente de verdad del alcance del MVP.

## Stack técnico

- Kotlin + Jetpack Compose
- Motor de ink: `androidx.ink` (Ink API de Google) — usar sus módulos de strokes, geometry, brush, rendering y storage. No reinventar serialización de trazos ni selección por lazo, la librería ya lo resuelve.
- OCR: ML Kit, on-device, ejecutado solo al cerrar una nota — nunca en tiempo real durante la escritura.
- Sin backend, sin red, sin dependencias de servidor. Todo el almacenamiento es local.

## Dispositivo de prueba

Samsung Galaxy Tab S6 Lite (4GB RAM, S Pen), conectada por USB. `adb devices` debe mostrarla como `device` (serial `R52R90JAE5V`). No usar emulador para medir latencia de ink — solo el dispositivo físico da datos reales de S Pen.

## Comandos

```bash
./gradlew assembleDebug        # compilar
./gradlew installDebug         # instalar en la tablet conectada
./gradlew test                 # tests unitarios
./gradlew connectedAndroidTest # tests instrumentados en el dispositivo físico
adb logcat -s TrazoApp         # logs filtrados (ajustar tag real del proyecto)
```

## Reglas invariables de arquitectura

- La capa de ink (`ink.bin`, trazos vía `androidx.ink.storage`) es inmutable por diseño. Ninguna operación de link, tag o metadata escribe ni modifica esta capa.
- Links, highlights y texto OCR viven exclusivamente en `annotations.json`, independientes del ink.
- El UUID de una nota es su identificador permanente. Nunca se regenera al renombrar, mover de cuaderno, o editar tags.
- Notificaciones de sugerencia o deshacer (título, dividir nota, link eliminado) usan siempre el mismo componente no bloqueante — no crear variantes ad hoc por caso de uso (ver RF-34 en el doc de requerimientos).
- Cero llamadas de red. Si una feature nueva pareciera necesitarlas, es señal de que no pertenece al MVP — confirmar antes de añadir esa dependencia.

## Alcance

Implementar solo lo definido como MVP en `docs/requerimientos_app_notas.md` (sección 2). Features de v2 (marcatextos, tipos de lápiz adicionales, sugerencias automáticas de tags, plantillas de papel, lasso de edición de trazos, zoom de escritura, bridge a Obsidian) no se implementan salvo pedido explícito.

## Convenciones de código

- Identificadores (clases, funciones, variables) en inglés, convención estándar de Kotlin/Android.
- Comentarios y mensajes de commit en español.
- Preferir Composables pequeños de responsabilidad única sobre pantallas monolíticas.
