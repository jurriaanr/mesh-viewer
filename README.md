# Mesh Viewer

Quick STL / 3MF previewer (Kotlin, Compose Desktop, LWJGL OpenGL 3.3). Windows and Linux.

## Install
Windows:

    winget install OutlawsITSoftware.MeshViewer
    winget upgrade OutlawsITSoftware.MeshViewer    # later, for a newer version

Or download the `.msi` from the [releases](https://github.com/jurriaanr/mesh-viewer/releases).

## Run from source
Open the folder in IntelliJ IDEA (Gradle JVM: JDK 17+), or:

    ./gradlew run                                  # Linux
    .\gradlew.bat run                              # Windows (PowerShell)
    .\gradlew.bat run --args="C:\path\model.3mf"   # open a file directly

## Build an installer (with .stl / .3mf file associations)
Build on the OS you are targeting:

    .\gradlew.bat packageMsi     # Windows -> build\compose\binaries\main\msi\
    ./gradlew packageDeb         # Debian/Ubuntu (packageRpm for Fedora)

## Releasing
`make release` (or `release-minor` / `release-major`) bumps the version, builds, tags and pushes
(`make release-local` stops before the push). The push runs `.github/workflows/release.yml`, which builds the MSI, attaches
it to a GitHub release and opens a pull request on
[microsoft/winget-pkgs](https://github.com/microsoft/winget-pkgs) for the new version.

One-time winget setup:
1. Create a classic GitHub token with the `public_repo` scope and store it as the repository secret
   `WINGET_TOKEN`. Without it the workflow still publishes the release and skips winget.
2. winget can only *update* a package that already exists, so submit the first version by hand once
   its release is published: `winget install Microsoft.WingetCreate`, then
   `wingetcreate new <msi release URL>`, with package identifier `OutlawsITSoftware.MeshViewer`
   (it must match `WINGET_ID` in the workflow), publisher `Outlaws IT Software`, scope `user`.

## Controls
Left-drag rotate, right/middle or Shift+left-drag pan, wheel zoom, double-click, R or F fit,
W wireframe, G grid, Ctrl+O open, drag & drop files onto the viewport.

## Notes
- 3MF: core spec, components, build transforms, units, Production extension (p:path, Bambu/Orca/Prusa
  project files), basematerials/colorgroup object colours. Per-triangle paint colours are not shown.
- Each double-clicked file opens its own window.
