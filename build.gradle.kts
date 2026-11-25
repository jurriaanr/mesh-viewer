import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
}

group = "jurriaanr"
version = "1.0.0"

val lwjglVersion = "3.4.3"

// LWJGL native libraries for the OS this is built on (jpackage builds per OS anyway).
val lwjglNatives: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").lowercase().let { it == "aarch64" || it == "arm64" }
    when {
        os.contains("win") -> if (arm) "natives-windows-arm64" else "natives-windows"
        os.contains("mac") -> if (arm) "natives-macos-arm64" else "natives-macos"
        else -> if (arm) "natives-linux-arm64" else "natives-linux"
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material:material:1.12.2")

    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-opengl")
    implementation("org.lwjgl:lwjgl-jawt")
    runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")

    // OpenGL canvas that can live inside AWT/Swing (and therefore inside Compose via SwingPanel)
    implementation("org.jmonkeyengine:lwjgl3-awt:0.2.4") {
        exclude(group = "org.lwjgl") // use the LWJGL version pinned above
    }
}

compose.desktop {
    application {
        mainClass = "meshviewer.MainKt"
        // Large meshes: allow the heap to use up to half of system RAM
        jvmArgs += listOf("-XX:MaxRAMPercentage=50")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "MeshViewer"
            packageVersion = "1.0.0"
            description = "Quick STL / 3MF previewer"
            vendor = "jurriaanr"

            // java.xml: StAX parser for 3MF. jdk.unsupported: needed by LWJGL.
            // java.instrument: listed by `gradlew suggestRuntimeModules`.
            modules("java.xml", "jdk.unsupported", "java.instrument")

            fileAssociation(mimeType = "model/stl", extension = "stl", description = "STL model")
            fileAssociation(mimeType = "model/3mf", extension = "3mf", description = "3MF model")

            windows {
                menu = true
                shortcut = true
                perUserInstall = true
                // Keep this constant so new MSI versions upgrade the old install instead of side-by-side
                upgradeUuid = "6f1d3c2e-8b4a-4f7e-9a51-3c2b7d9e0a14"
            }
            linux {
                shortcut = true
            }
        }
    }
}
