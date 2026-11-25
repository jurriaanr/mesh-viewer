# AGENTS.md

Guidance for AI coding agents working in this repository. User-facing docs live in `README.md`.

## Project

Mesh Viewer: a small desktop previewer for STL and 3MF files. Kotlin on the JVM, Compose Desktop for the
window chrome, LWJGL OpenGL 3.3 core for the viewport. Targets Windows and Linux (macOS natives resolve but
are not packaged or tested).

## Commands

Use `.\gradlew.bat` on Windows (PowerShell), `./gradlew` elsewhere. JDK 17+.

| Task | Command |
|---|---|
| Compile (fastest check) | `gradlew compileKotlin` |
| Full build | `gradlew build` |
| Run | `gradlew run` |
| Run with a file | `gradlew run --args="C:\path\model.3mf"` |
| Windows installer | `gradlew packageMsi` → `build/compose/binaries/main/msi/` |
| Linux packages | `gradlew packageDeb` / `gradlew packageRpm` |

There are no automated tests. Verify a change by compiling, then by running the app and opening a model.
Installers must be built on the OS they target (jpackage and the LWJGL natives are per-OS).

## Layout

All sources are in `src/main/kotlin/meshviewer/`:

- `Main.kt` — entry point, Compose window, toolbar/status bar, file dialog, async loading on `Dispatchers.IO`.
- `MeshCanvas.kt` — `AWTGLCanvas` (from `lwjgl3-awt`) embedded via `SwingPanel`. GPU upload, grid, shaders
  (inline GLSL strings in the companion object), mouse/keyboard input, drag & drop.
- `OrbitCamera.kt` — Z-up orbit camera; produces column-major view/projection matrices.
- `loaders/Model.kt` — `Model`, `MeshPart`, `ModelLoader` (dispatch by extension), `FloatList`/`IntList`.
- `loaders/StlLoader.kt` — binary + ASCII STL.
- `loaders/ThreeMfLoader.kt` — 3MF zip + StAX XML parsing.

## Conventions and invariants

**Coordinates and units**
- World space is Z-up, in millimetres (3D-printing convention). Loaders convert units and apply all
  transforms themselves; `MeshPart.positions` are final world-space xyz triplets.
- `MeshPart.indices == null` means a plain triangle soup (STL); otherwise indexed triangles (3MF).
- `MeshPart.color` is `0xRRGGBB` or `null` for the default colour.

**Rendering / threading**
- All OpenGL calls happen on the AWT event thread inside the canvas's GL context (`paintGL`, `initGL`,
  `runInContext`). Never touch GL from a coroutine or from Compose code.
- Rendering is on demand: after changing anything visible, call `requestRender()`. Do not add a render loop
  or call `render()` directly; idle CPU/GPU use is meant to be zero.
- `showModel()` is the only thread-safe entry point for new geometry; the upload happens on the next frame.
- Meshes carry no normals. The fragment shader computes flat normals from `dFdx`/`dFdy` of view-space
  position so bad winding in source files cannot produce black faces. Keep it that way unless deliberately
  adding smooth shading.
- `OrbitCamera` matrices are column-major float arrays passed to `glUniformMatrix4fv(..., false, ...)`.

**3MF specifics**
- 3MF matrices are 4x3 row-major with row vectors: `p' = p * M_component * M_parent`. Keep the
  `multiply(child, parent)` order.
- Components may reference other `.model` files in the archive via `p:path` (Production extension —
  Bambu/Orca/Prusa project files). Zip entry lookup is case-insensitive and slash-normalised.
- The XML factory disables DTDs and external entities; keep that (untrusted input).
- Object colours come from `basematerials`/`colorgroup` via the object's `pid`/`pindex`. Per-triangle
  colours and textures are intentionally ignored.

**Performance**
- Models can have millions of triangles. Use `FloatArray`/`IntArray` and the `FloatList`/`IntList` helpers;
  avoid boxed collections (`List<Float>`) on per-vertex paths.
- The packaged app runs with `-XX:MaxRAMPercentage=50`; `OutOfMemoryError` is reported in the status bar.

**Code style**
- Kotlin official code style (`kotlin.code.style=official`), 4-space indent, trailing commas in multi-line
  argument lists.
- Explicit single-symbol imports for LWJGL GL functions (use the core-profile `GLxxC` classes).
- Comments explain *why* (platform quirks, spec details), not what.

## Build and packaging gotchas

- LWJGL natives are picked from the build machine's OS/arch in `build.gradle.kts`.
- jpackage ships a trimmed runtime: `modules("java.xml", "jdk.unsupported")`. If new code uses another JDK
  module (e.g. `java.net.http`, `java.prefs`), add it there — `gradlew run`
  will still work but the installed app will fail.
- `upgradeUuid` in the Windows section must never change, or new MSIs install side-by-side instead of
  upgrading.
- Bump `version` and `packageVersion` together.
- `lwjgl3-awt` excludes its own LWJGL; all LWJGL artifacts follow `lwjglVersion` via the BOM.
- `FileDialog` filtering differs per platform: Windows honours a wildcard filename, GTK honours
  `FilenameFilter`; both are set on purpose.

## When adding features

- New file format: add a loader object in `loaders/` returning a `Model`, register its extension in
  `ModelLoader.extensions` and `ModelLoader.load`, and add a `fileAssociation` in `build.gradle.kts`.
- New keyboard shortcut: `MeshCanvas.handleKey` (shared by the canvas key listener and the Compose
  window's `onKeyEvent`, so it works whichever has focus); if it toggles state shown in the toolbar, fire
  `onOptionsChanged` so `Main.kt` stays in sync. Update the Controls section of `README.md`.
- Keep `README.md` notes on 3MF support accurate when loader capabilities change.
