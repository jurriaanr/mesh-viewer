package meshviewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import meshviewer.loaders.Model
import meshviewer.loaders.ModelLoader
import java.awt.FileDialog
import java.io.File

private val Accent = Color(0xFF7AA2F7)
private val Surface = Color(0xFF232428)
private val Background = Color(0xFF1C1D20)
private val ErrorText = Color(0xFFFF8A80)

fun main(args: Array<String>) {
    // File association / command line: first argument is the file to open.
    val initialFile = args.firstOrNull()?.let(::File)?.takeIf { it.isFile }

    application {
        val windowState = rememberWindowState(width = 1100.dp, height = 760.dp)
        val scope = rememberCoroutineScope()

        var title by remember { mutableStateOf("Mesh Viewer") }
        var status by remember { mutableStateOf("Open or drop an .stl or .3mf file") }
        var isError by remember { mutableStateOf(false) }
        var wireframe by remember { mutableStateOf(false) }
        var grid by remember { mutableStateOf(true) }
        var lastDir by remember { mutableStateOf(initialFile?.parent) }

        // Set before the canvas is ever shown, so a failed OpenGL start-up is always reported.
        val canvas = remember {
            MeshCanvas().apply { onRenderError = { message -> isError = true; status = message } }
        }
        val loadJob = remember { arrayOfNulls<Job>(1) }

        fun load(file: File) {
            lastDir = file.parent
            isError = false
            status = "Loading ${file.name}…"
            // A slow earlier load must not replace the file that was opened after it.
            loadJob[0]?.cancel()
            loadJob[0] = scope.launch {
                val start = System.nanoTime()
                val result = withContext(Dispatchers.IO) { runCatching { ModelLoader.load(file) } }
                val ms = (System.nanoTime() - start) / 1_000_000
                result.onSuccess { model ->
                    if (canvas.glFailed) return@onSuccess // keep the OpenGL error in the status bar
                    canvas.showModel(model)
                    canvas.requestFocusInWindow()
                    title = "${file.name} - Mesh Viewer"
                    status = describe(file, model, ms)
                }.onFailure { e ->
                    isError = true
                    status = "Could not open ${file.name}: " + when (e) {
                        is OutOfMemoryError -> "out of memory"
                        else -> e.message ?: e.javaClass.simpleName
                    }
                }
            }
        }

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = title,
            // The viewport handles its own keys; this covers the case where the toolbar has focus.
            onKeyEvent = { e ->
                e.type == KeyEventType.KeyDown && !e.isAltPressed &&
                    canvas.handleKey(e.key.nativeKeyCode, e.isCtrlPressed || e.isMetaPressed)
            },
        ) {
            fun openDialog() {
                val dialog = FileDialog(window, "Open 3D model", FileDialog.LOAD)
                lastDir?.let { dialog.directory = it }
                // Windows ignores FilenameFilter but honours a wildcard file name; GTK is the reverse.
                if (System.getProperty("os.name").lowercase().contains("win")) dialog.file = "*.stl;*.3mf"
                dialog.setFilenameFilter { _, name -> ModelLoader.isSupported(File(name)) }
                dialog.isVisible = true
                val name = dialog.file ?: return
                load(File(dialog.directory, name))
            }

            LaunchedEffect(Unit) {
                canvas.onOpenRequested = { openDialog() }
                canvas.onFileDropped = { load(it) }
                canvas.onOptionsChanged = { wireframe = canvas.wireframe; grid = canvas.showGrid }
                initialFile?.let { load(it) }
            }

            MaterialTheme(colors = darkColors(primary = Accent, surface = Surface, background = Background)) {
                Column(Modifier.fillMaxSize().background(Background)) {
                    Row(
                        Modifier.fillMaxWidth().height(44.dp).background(Surface).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = { openDialog() }) { Text("Open…") }
                        TextButton(onClick = { canvas.resetView() }) { Text("Reset view") }
                        Toggle("Wireframe", wireframe) { canvas.wireframe = it; wireframe = it }
                        Toggle("Grid", grid) { canvas.showGrid = it; grid = it }
                        Spacer(Modifier.weight(1f))
                        Text(
                            "Drag: rotate · Right-drag: pan · Wheel: zoom · Double-click: fit",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.45f),
                        )
                    }

                    SwingPanel(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        factory = { canvas },
                    )

                    Row(
                        Modifier.fillMaxWidth().height(28.dp).background(Surface).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            status,
                            fontSize = 12.sp,
                            color = if (isError) ErrorText else Color.White.copy(alpha = 0.75f),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    TextButton(
        onClick = { onChange(!checked) },
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (checked) Accent else Color.White.copy(alpha = 0.55f),
        ),
    ) {
        Text(if (checked) "✓ $label" else label)
    }
}

private fun describe(file: File, model: Model, ms: Long): String {
    val dx = model.max[0] - model.min[0]
    val dy = model.max[1] - model.min[1]
    val dz = model.max[2] - model.min[2]
    val objects = if (model.objectCount == 1) "1 object" else "${model.objectCount} objects"
    // The file name is appended, not formatted: it may contain '%'.
    return file.name + "    %,d triangles    %s    %.1f × %.1f × %.1f mm    %d ms"
        .format(model.triangleCount, objects, dx, dy, dz, ms)
}
