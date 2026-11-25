# Mesh Viewer

Quick STL / 3MF previewer (Kotlin, Compose Desktop, LWJGL OpenGL 3.3). Windows and Linux.

## Run from source
Open the folder in IntelliJ IDEA (Gradle JVM: JDK 17+), or:

    ./gradlew run                                  # Linux
    .\gradlew.bat run                              # Windows (PowerShell)
    .\gradlew.bat run --args="C:\path\model.3mf"   # open a file directly

## Build an installer (with .stl / .3mf file associations)
Build on the OS you are targeting:

    .\gradlew.bat packageMsi     # Windows -> build\compose\binaries\main\msi\
    ./gradlew packageDeb         # Debian/Ubuntu (packageRpm for Fedora)

## Controls
Left-drag rotate, right/middle or Shift+left-drag pan, wheel zoom, double-click, R or F fit,
W wireframe, G grid, Ctrl+O open, drag & drop files onto the viewport.

## Notes
- 3MF: core spec, components, build transforms, units, Production extension (p:path, Bambu/Orca/Prusa
  project files), basematerials/colorgroup object colours. Per-triangle paint colours are not shown.
- Each double-clicked file opens its own window.
