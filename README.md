# Turmalin

<img src="TURMALIN_logo.svg" alt="Turmalin logo" width="200"/>

*[Leer esto en español](README.es.md)*

**Handwritten notes that link to each other.**

Turmalin is a note-taking app for Android tablets, built for people who think and study by writing by hand, without giving up the idea-linking that makes tools like Obsidian so valuable. It's essentially an Obsidian-style knowledge management system — except the unit of content isn't plain text, it's real digital ink captured with an S Pen.

## The problem it solves

Today there are two worlds that don't talk to each other:

- **Handwriting apps** (GoodNotes, Squid, Samsung Notes) — great for taking notes with a pen, but every note lives in isolation. There's no way to say "this idea connects to that other one."
- **Knowledge management apps** (Obsidian, Heptabase, Roam) — perfect for weaving a network of ideas through links, but built for typed text, not fluid handwriting.

Turmalin isn't a compromise between the two: you write by hand completely naturally, and on top of that you can decide which parts of your ink connect to which notes, exactly as you would with a link in Obsidian.

## Design principle

**Zero friction at the moment of writing.** You're never asked for a title, a tag, or organization before you start writing — all of that happens before or after, never interrupting the stroke. The keyboard doesn't appear unless you ask for it.

## What Turmalin can do today (v1)

### Handwriting
- Fluid capture with the S Pen (pressure, tilt, low latency), with automatic rejection of the palm resting on the screen.
- Palette of 8 pen colors and 3 stroke widths.
- Full-stroke eraser (tap and erase the whole line) and partial eraser (trims just the part you touch).
- Eraser shortcut by holding down the S Pen's physical button, without lifting the pen.
- Blank paper per note; each note can have several pages, added explicitly with a button.
- Page navigation and zoom/pan with two-finger touch gestures — the pen is always reserved for writing or erasing, never for moving the canvas.

### Notes and organization
- Each note is created empty and ready to write on immediately, with an editable "Untitled" title.
- When you close an untitled note, Turmalin suggests one based on the first thing you wrote (see OCR below).
- Manual tags per note, typed on the keyboard, comma-separated.
- Notebooks to group notes visually (a note can live in a notebook or loose at the root).
- Home gallery with all your notes and notebooks, sortable by date, title, or notebook.

### Links and the knowledge graph
This is Turmalin's core piece: **connecting handwritten ideas to each other.**

- Lasso-select any region of your ink — a word, a drawing, an entire paragraph — and link it to another note, just as you'd select text for a link in Obsidian.
- Links are **directed**: if Note A points to Note B, that doesn't automatically create an arrow back. The "backlink" (who points to this note) is an instant query, not duplicated data — the same mental model Obsidian uses for its backlinks.
- Every note shows a count of incoming references and a panel to jump straight to the notes that mention it.
- Full **graph view**, with live physics (nodes settle into place on their own, like in Obsidian): orphan notes (with no connections at all) highlighted in a different color, node size proportional to how many connections it has, and a settings panel to tune forces, node distance, line thickness, and label visibility by zoom level.
- You can also create a link by dragging one node onto another directly in the graph view, without going through the lasso.
- Links never touch your original ink: they live in a separate layer, so deleting or moving a link never alters what you wrote.

### Search
- Handwriting recognition (on-device OCR) that only runs when you close a note — never while you're writing, so it doesn't compete for resources with real-time stroke capture.
- Instant search in the gallery by title, tags, and the content of what you wrote, with no extra processing at search time.

### Export
- Export any note to a **vector** PDF (not a screenshot): the ink stays sharp at any zoom level.
- Links remain visible in the PDF as the same colored halo you see in the app, so the network of connections survives outside Turmalin.
- The PDF is saved directly to the device's Downloads folder, ready to share.

### Privacy and portability
- All handwriting recognition runs on-device — none of your handwriting or notes is sent to any server.
- No backend, no account, no network connection except the one-time (optional) download of the Spanish recognition model.
- Your notes live as a normal, visible folder in the device's storage: you can copy or back it up manually at any time, without relying on any export feature.

## What's not included yet (roadmap)

Out of scope for this first version, planned for later: highlighters, additional pen types (brush, fountain pen), automatic tag suggestions, paper templates with lines or grids, editing existing strokes with a lasso, and a direct export bridge to Obsidian.

## Installation

Requirements:
- Android tablet with S Pen support (or equivalent stylus).
- Android Studio with the Android SDK configured.

Steps:

```bash
git clone <repository-url>
cd Turmalin
./gradlew installDebug   # installs on the device connected via USB (adb devices)
```

No account, API key, or backend of your own is needed: Turmalin works completely offline from the first launch.

## Project status

Turmalin is a personal project, with no fixed release date, intended to eventually become a public app. This v1 covers the full cycle: write, organize, link, search, and export.
