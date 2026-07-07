# Turmalin

*[Read this in English](README.md)*

**Notas manuscritas que se conectan entre sí.**

Turmalin es una app de notas para tablet Android pensada para quienes piensan y estudian escribiendo a mano, pero no quieren perder la capacidad de conectar ideas que hace tan valiosas a herramientas como Obsidian. Es, en esencia, un sistema de gestión del conocimiento tipo Obsidian — pero donde la unidad de contenido no es texto plano, sino tinta digital real, capturada con S Pen.

## El problema que resuelve

Hoy existen dos mundos que no se hablan:

- **Apps de escritura a mano** (GoodNotes, Squid, Samsung Notes) — excelentes para tomar notas con lápiz, pero cada nota vive aislada. No hay forma de decir "esta idea se conecta con aquella otra".
- **Apps de gestión del conocimiento** (Obsidian, Heptabase, Roam) — perfectas para tejer una red de ideas mediante links, pero pensadas para texto tecleado, no para escritura a mano fluida.

Turmalin no es un compromiso entre las dos: escribes a mano de forma completamente natural, y por encima de eso puedes decidir qué partes de tu tinta se conectan con qué otras notas, exactamente como harías con un link en Obsidian.

## Principio de diseño

**Cero fricción en el momento de escribir.** Nunca se te pide un título, un tag o una organización antes de empezar a escribir — todo eso ocurre antes o después, nunca interrumpiendo el trazo. El teclado no aparece a menos que tú lo pidas.

## Qué puede hacer Turmalin hoy (v1)

### Escritura a mano
- Captura fluida con S Pen (presión, inclinación, baja latencia), con rechazo automático de la palma apoyada sobre la pantalla.
- Paleta de 8 colores de pluma y 3 grosores de trazo.
- Goma de trazo completo (toca y borra la línea entera) y goma de borrado parcial (recorta justo la parte que tocas).
- Atajo de goma manteniendo presionado el botón físico del S Pen, sin soltar el lápiz.
- Papel en blanco por nota; cada nota puede tener varias páginas, añadidas explícitamente con un botón.
- Navegación entre páginas y zoom/pan con gestos táctiles de dos dedos — el lápiz siempre queda reservado para escribir o borrar, nunca para mover el lienzo.

### Notas y organización
- Cada nota se crea vacía y lista para escribir de inmediato, con título "Sin título" editable en cualquier momento.
- Al cerrar una nota sin título, Turmalin sugiere uno basado en lo primero que escribiste (ver OCR más abajo).
- Tags manuales por nota, escritos por teclado, separados por comas.
- Cuadernos para agrupar notas visualmente (una nota puede vivir en un cuaderno o suelta en la raíz).
- Galería de inicio con todas tus notas y cuadernos, ordenable por fecha, título o cuaderno.

### Links y el grafo de conocimiento
Esta es la pieza central de Turmalin: **conectar ideas manuscritas entre sí.**

- Selecciona con un lazo cualquier región de tu tinta —una palabra, un dibujo, un párrafo entero— y créale un link hacia otra nota, tal como seleccionarías texto para un link en Obsidian.
- Los links son **dirigidos**: si la Nota A apunta a la Nota B, eso no crea automáticamente una flecha de vuelta. La "referencia inversa" (quién apunta a esta nota) es una consulta instantánea, no un dato duplicado — el mismo modelo mental que usa Obsidian con sus backlinks.
- Cada nota muestra un contador de referencias entrantes y un panel para saltar directo a las notas que la mencionan.
- **Vista de grafo** completa, con física en vivo (los nodos se acomodan solos, como en Obsidian): notas huérfanas (sin ninguna conexión) resaltadas en un color distinto, tamaño de nodo proporcional a cuántas conexiones tiene, y un panel de ajustes para afinar fuerzas, distancia entre nodos, grosor de las líneas y visibilidad de etiquetas según el zoom.
- También puedes crear un link arrastrando un nodo hacia otro directamente en la vista de grafo, sin pasar por el lazo.
- Los links nunca tocan tu tinta original: viven en una capa aparte, así que borrar o mover un link jamás altera lo que escribiste.

### Búsqueda
- Reconocimiento de escritura a mano (OCR on-device) que corre solo al cerrar una nota — nunca mientras escribes, para no competir por recursos con el trazo en tiempo real.
- Búsqueda instantánea en la galería por título, tags y contenido de lo que escribiste, sin ningún procesamiento adicional en el momento de buscar.

### Exportación
- Exporta cualquier nota a PDF **vectorial** (no una foto de la pantalla): la tinta se mantiene nítida a cualquier nivel de zoom.
- Los links quedan visibles en el PDF como el mismo halo de color que ves en la app, así que la red de conexiones sobrevive fuera de Turmalin.
- El PDF se guarda directamente en la carpeta de Descargas del dispositivo, listo para compartir.

### Privacidad y portabilidad
- Todo el reconocimiento de escritura corre on-device — nada de tu letra ni tus notas se envía a ningún servidor.
- Cero backend, cero cuenta, cero conexión de red salvo la descarga única (y opcional) del modelo de reconocimiento en español.
- Tus notas viven como una carpeta normal y visible en el almacenamiento del dispositivo: puedes copiarla o respaldarla manualmente en cualquier momento, sin depender de ninguna función de exportación.

## Qué no incluye todavía (roadmap)

Fuera del alcance de esta primera versión, pensado para más adelante: marcatextos, tipos de pluma adicionales (pincel, pluma estilográfica), sugerencia automática de tags, plantillas de papel con líneas o cuadrícula, edición de trazos existentes con lazo, y un puente de exportación directo a Obsidian.

## Instalación

Requisitos:
- Tablet Android con soporte S Pen (o stylus equivalente).
- Android Studio con el SDK de Android configurado.

Pasos:

```bash
git clone <url-del-repositorio>
cd Turmalin
./gradlew installDebug   # instala en el dispositivo conectado por USB (adb devices)
```

No hace falta ninguna cuenta, clave de API ni backend propio: Turmalin funciona completamente offline desde el primer arranque.

## Estado del proyecto

Turmalin es un proyecto personal, sin fecha de lanzamiento fija, con intención de convertirse eventualmente en una app pública. Esta v1 cubre el ciclo completo: escribir, organizar, conectar, buscar y exportar.
