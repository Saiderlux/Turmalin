---
name: verify
description: Verificar cambios de Turmalin en la Tab S6 Lite real vía adb — build, lanzamiento, tinta por stylus y captura de evidencia
---

# Verificar Turmalin en el dispositivo físico

Tablet: Samsung Galaxy Tab S6 Lite, serial `R52R90JAE5V`, pantalla 1200×2000.
No usar emulador (latencia de ink solo es real en el dispositivo).

## Receta

```bash
adb devices                          # debe listar R52R90JAE5V como device
./gradlew installDebug               # compila e instala
adb shell input keyevent KEYCODE_WAKEUP && adb shell wm dismiss-keyguard
adb shell am force-stop com.saider.turmalin
adb shell monkey -p com.saider.turmalin -c android.intent.category.LAUNCHER 1
adb exec-out screencap -p > shot.png # evidencia; leer la imagen para ver la UI
```

- La pantalla suele estar apagada: sin WAKEUP el screencap sale negro.
- Tinta real por adb: `adb shell input stylus swipe x1 y1 x2 y2 ms` — pasa el
  palm rejection (toolType stylus). `input tap` normal sirve para botones de
  Compose (barra, chips, paginación) pero NUNCA produce tinta en el canvas.
- OCR con texto legible requiere S Pen a mano; los palotes de adb no lo dan.

## Coordenadas útiles (retrato, densidad por defecto)

- FAB "+" nueva nota en galería: (1119, 1848)
- Barra de la nota (dock TOP): Pluma (255,178), Goma trazo (408,178),
  Goma parcial (600,178), Lazo (815,178), Deshacer ↶ (960,178), Rehacer ↷ (1021,178)
- Volver (←): (55,80) · "+ Página": (1110,1880) · Página anterior ◀: (860,1880)
- Un trazo diagonal cómodo: `input stylus swipe 300 700 800 1200 400`

## Flujos que vale la pena manejar

- Trazo → cerrar nota: dispara guardado + OCR ("Nota guardada" + sugerencia de
  título en la esquina inferior).
- Cambiar de página guarda la actual; reabrir la nota restaura la última página.
- Los screenshots se leen bien con la herramienta Read (PNG directo).
