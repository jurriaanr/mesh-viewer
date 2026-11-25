package meshviewer

import meshviewer.loaders.Model
import meshviewer.loaders.ModelLoader
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11C.GL_BLEND
import org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL11C.GL_DEPTH_BUFFER_BIT
import org.lwjgl.opengl.GL11C.GL_DEPTH_TEST
import org.lwjgl.opengl.GL11C.GL_FILL
import org.lwjgl.opengl.GL11C.GL_FLOAT
import org.lwjgl.opengl.GL11C.GL_FRONT_AND_BACK
import org.lwjgl.opengl.GL11C.GL_LINE
import org.lwjgl.opengl.GL11C.GL_LINES
import org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_ALPHA
import org.lwjgl.opengl.GL11C.GL_SRC_ALPHA
import org.lwjgl.opengl.GL11C.GL_TRIANGLES
import org.lwjgl.opengl.GL11C.GL_UNSIGNED_INT
import org.lwjgl.opengl.GL11C.glBlendFunc
import org.lwjgl.opengl.GL11C.glClear
import org.lwjgl.opengl.GL11C.glClearColor
import org.lwjgl.opengl.GL11C.glDepthMask
import org.lwjgl.opengl.GL11C.glDisable
import org.lwjgl.opengl.GL11C.glDrawArrays
import org.lwjgl.opengl.GL11C.glDrawElements
import org.lwjgl.opengl.GL11C.glEnable
import org.lwjgl.opengl.GL11C.glPolygonMode
import org.lwjgl.opengl.GL11C.glViewport
import org.lwjgl.opengl.GL13C.GL_MULTISAMPLE
import org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER
import org.lwjgl.opengl.GL15C.GL_ELEMENT_ARRAY_BUFFER
import org.lwjgl.opengl.GL15C.GL_STATIC_DRAW
import org.lwjgl.opengl.GL15C.glBindBuffer
import org.lwjgl.opengl.GL15C.glBufferData
import org.lwjgl.opengl.GL15C.glDeleteBuffers
import org.lwjgl.opengl.GL15C.glGenBuffers
import org.lwjgl.opengl.GL20C.GL_COMPILE_STATUS
import org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER
import org.lwjgl.opengl.GL20C.GL_LINK_STATUS
import org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER
import org.lwjgl.opengl.GL20C.glAttachShader
import org.lwjgl.opengl.GL20C.glBindAttribLocation
import org.lwjgl.opengl.GL20C.glCompileShader
import org.lwjgl.opengl.GL20C.glCreateProgram
import org.lwjgl.opengl.GL20C.glCreateShader
import org.lwjgl.opengl.GL20C.glDeleteShader
import org.lwjgl.opengl.GL20C.glEnableVertexAttribArray
import org.lwjgl.opengl.GL20C.glGetProgramInfoLog
import org.lwjgl.opengl.GL20C.glGetProgrami
import org.lwjgl.opengl.GL20C.glGetShaderInfoLog
import org.lwjgl.opengl.GL20C.glGetShaderi
import org.lwjgl.opengl.GL20C.glGetUniformLocation
import org.lwjgl.opengl.GL20C.glLinkProgram
import org.lwjgl.opengl.GL20C.glShaderSource
import org.lwjgl.opengl.GL20C.glUniform1i
import org.lwjgl.opengl.GL20C.glUniform3f
import org.lwjgl.opengl.GL20C.glUniform4f
import org.lwjgl.opengl.GL20C.glUniformMatrix4fv
import org.lwjgl.opengl.GL20C.glUseProgram
import org.lwjgl.opengl.GL20C.glVertexAttribPointer
import org.lwjgl.opengl.GL30C.glBindVertexArray
import org.lwjgl.opengl.GL30C.glDeleteVertexArrays
import org.lwjgl.opengl.GL30C.glGenVertexArrays
import org.lwjgl.opengl.awt.AWTGLCanvas
import org.lwjgl.opengl.awt.GLData
import java.awt.Graphics
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * OpenGL 3.3 core viewport. Renders on demand (only after input or a state change),
 * so it uses no CPU/GPU while idle. All GL work happens on the AWT event thread.
 */
class MeshCanvas : AWTGLCanvas(glData()) {

    var onOpenRequested: () -> Unit = {}
    var onFileDropped: (File) -> Unit = {}
    /** Called when wireframe/grid change (e.g. via keyboard) so the toolbar can follow. */
    var onOptionsChanged: () -> Unit = {}
    /** Called on the event thread when OpenGL cannot start or a frame fails. */
    var onRenderError: (String) -> Unit = {}

    /** True once OpenGL start-up has failed; the viewport then stays blank. */
    @Volatile var glFailed = false
        private set

    var wireframe = false
        set(value) { if (field != value) { field = value; onOptionsChanged(); requestRender() } }
    var showGrid = true
        set(value) { if (field != value) { field = value; onOptionsChanged(); requestRender() } }

    private class GpuPart(val vao: Int, val vbo: Int, val ebo: Int, val count: Int, val color: FloatArray)

    private val camera = OrbitCamera()
    private val gpuParts = ArrayList<GpuPart>()
    private var bounds: Pair<FloatArray, FloatArray>? = null

    private val pendingModel = AtomicReference<Model?>(null)
    private val renderQueued = AtomicBoolean(false)
    private var glReady = false

    private var meshProgram = 0
    private var uMeshView = -1
    private var uMeshProj = -1
    private var uMeshColor = -1
    private var uMeshWire = -1
    private var lineProgram = 0
    private var uLineView = -1
    private var uLineProj = -1
    private var uLineColor = -1

    private var gridVao = 0
    private var gridVbo = 0
    private var gridMinorCount = 0
    private var gridMajorCount = 0

    init {
        isFocusable = true
        background = java.awt.Color(0x2B2E33) // shown until the first GL frame
        installInput()
        dropTarget = DropTarget(this, DnDConstants.ACTION_COPY, FileDropListener(), true)
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = requestRender()
            override fun componentShown(e: ComponentEvent) = requestRender()
        })
    }

    /** Can be called from any thread. The upload happens on the next frame. */
    fun showModel(model: Model) {
        pendingModel.set(model)
        requestRender()
    }

    fun resetView() {
        bounds?.let { (min, max) -> camera.fit(min, max, aspect()) }
        requestRender()
    }

    /**
     * Keyboard shortcuts, shared by the canvas key listener and the Compose window (which
     * receives the keys while the toolbar has focus). Returns true when the key was used.
     */
    fun handleKey(keyCode: Int, ctrlOrMeta: Boolean): Boolean {
        when {
            // Deferred: the file dialog is modal and must not block inside the key event.
            ctrlOrMeta -> if (keyCode == KeyEvent.VK_O) SwingUtilities.invokeLater { onOpenRequested() } else return false
            keyCode == KeyEvent.VK_R || keyCode == KeyEvent.VK_F -> resetView()
            keyCode == KeyEvent.VK_W -> wireframe = !wireframe
            keyCode == KeyEvent.VK_G -> showGrid = !showGrid
            else -> return false
        }
        return true
    }

    /** Coalesces repeated requests into a single frame on the event thread. */
    fun requestRender() {
        if (glFailed || !renderQueued.compareAndSet(false, true)) return
        SwingUtilities.invokeLater {
            renderQueued.set(false)
            if (glFailed || !isDisplayable || width <= 0 || height <= 0) return@invokeLater
            try {
                render()
            } catch (t: Throwable) {
                val root = generateSequence(t) { it.cause }.last()
                val detail = root.message ?: root.javaClass.simpleName
                if (glReady) {
                    onRenderError("Rendering failed: $detail")
                } else {
                    // No context or shaders: retrying every repaint would only repeat the failure.
                    glFailed = true
                    onRenderError("OpenGL 3.3 is not available on this system: $detail")
                }
            }
        }
    }

    private fun aspect(): Double = if (width > 0 && height > 0) width.toDouble() / height else 1.0

    // AWT asks for repaints on expose/resize; route them through the on-demand renderer.
    override fun paint(graphics: Graphics?) = requestRender()
    override fun update(graphics: Graphics?) = requestRender()

    override fun initGL() {
        GL.createCapabilities()
        meshProgram = buildProgram(MESH_VS, MESH_FS)
        uMeshView = glGetUniformLocation(meshProgram, "uView")
        uMeshProj = glGetUniformLocation(meshProgram, "uProj")
        uMeshColor = glGetUniformLocation(meshProgram, "uColor")
        uMeshWire = glGetUniformLocation(meshProgram, "uWire")
        lineProgram = buildProgram(LINE_VS, LINE_FS)
        uLineView = glGetUniformLocation(lineProgram, "uView")
        uLineProj = glGetUniformLocation(lineProgram, "uProj")
        uLineColor = glGetUniformLocation(lineProgram, "uColor")
        glEnable(GL_DEPTH_TEST)
        glEnable(GL_MULTISAMPLE)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glClearColor(0.17f, 0.18f, 0.20f, 1f)
        glReady = true
    }

    override fun paintGL() {
        pendingModel.getAndSet(null)?.let { upload(it) }

        val w = framebufferWidth
        val h = framebufferHeight
        glViewport(0, 0, w, h)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

        if (gpuParts.isNotEmpty() && w > 0 && h > 0) {
            val view = camera.viewMatrix()
            val proj = camera.projectionMatrix(w.toDouble() / h)

            if (showGrid && gridVao != 0) {
                glUseProgram(lineProgram)
                glUniformMatrix4fv(uLineView, false, view)
                glUniformMatrix4fv(uLineProj, false, proj)
                glEnable(GL_BLEND)
                glDepthMask(false) // grid never hides the model
                glBindVertexArray(gridVao)
                glUniform4f(uLineColor, 1f, 1f, 1f, 0.07f)
                glDrawArrays(GL_LINES, 0, gridMinorCount)
                glUniform4f(uLineColor, 1f, 1f, 1f, 0.18f)
                glDrawArrays(GL_LINES, gridMinorCount, gridMajorCount)
                glDepthMask(true)
                glDisable(GL_BLEND)
            }

            glUseProgram(meshProgram)
            glUniformMatrix4fv(uMeshView, false, view)
            glUniformMatrix4fv(uMeshProj, false, proj)
            glUniform1i(uMeshWire, if (wireframe) 1 else 0)
            if (wireframe) glPolygonMode(GL_FRONT_AND_BACK, GL_LINE)
            for (p in gpuParts) {
                glUniform3f(uMeshColor, p.color[0], p.color[1], p.color[2])
                glBindVertexArray(p.vao)
                if (p.ebo != 0) glDrawElements(GL_TRIANGLES, p.count, GL_UNSIGNED_INT, 0L)
                else glDrawArrays(GL_TRIANGLES, 0, p.count)
            }
            if (wireframe) glPolygonMode(GL_FRONT_AND_BACK, GL_FILL)
            glBindVertexArray(0)
        }
        swapBuffers()
    }

    override fun removeNotify() {
        if (glReady) runInContext { freeMesh(); freeGrid() }
        glReady = false
        super.removeNotify()
    }

    // ---- GPU upload -------------------------------------------------------

    private fun upload(model: Model) {
        freeMesh()
        for (part in model.parts) {
            val vao = glGenVertexArrays()
            glBindVertexArray(vao)
            val vbo = glGenBuffers()
            glBindBuffer(GL_ARRAY_BUFFER, vbo)
            glBufferData(GL_ARRAY_BUFFER, part.positions, GL_STATIC_DRAW)
            glEnableVertexAttribArray(0)
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0L)
            var ebo = 0
            val indices = part.indices
            val count: Int
            if (indices != null) {
                ebo = glGenBuffers()
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo) // recorded in the VAO
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW)
                count = indices.size
            } else {
                count = part.positions.size / 3
            }
            glBindVertexArray(0)
            gpuParts += GpuPart(vao, vbo, ebo, count, rgb(part.color ?: DEFAULT_COLOR))
        }
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        bounds = model.min.copyOf() to model.max.copyOf()
        buildGrid(model.min, model.max)
        camera.fit(model.min, model.max, aspect())
    }

    /**
     * Grid on the plane under the model, a major line every 5th. The spacing is 10 mm for
     * ordinary models, 1 mm for tiny ones, and grows by powers of ten for huge ones so the
     * number of lines stays bounded whatever the model size.
     */
    private fun buildGrid(min: FloatArray, max: FloatArray) {
        freeGrid()
        val cx = (min[0] + max[0]) / 2f
        val cy = (min[1] + max[1]) / 2f
        val extent = max(max[0] - min[0], max[1] - min[1])
        var step = if (extent < 20f) 1f else 10f
        while (extent > step * 200f) step *= 10f
        val majorEvery = 5
        val half = max(ceil(extent * 0.75f / step) * step + 2 * step, 6 * step)
        val x0 = floor((cx - half) / step) * step
        val x1 = ceil((cx + half) / step) * step
        val y0 = floor((cy - half) / step) * step
        val y1 = ceil((cy + half) / step) * step
        val z = min[2] - 0.01f * step

        val minor = ArrayList<Float>()
        val major = ArrayList<Float>()
        fun line(list: MutableList<Float>, ax: Float, ay: Float, bx: Float, by: Float) {
            list += ax; list += ay; list += z; list += bx; list += by; list += z
        }
        // Counted loops: adding `step` to a float far from the origin may not change it at all.
        val nx = Math.round((x1 - x0) / step).coerceIn(0, MAX_GRID_LINES)
        for (i in 0..nx) {
            val x = x0 + i * step
            val isMajor = Math.round(x / step) % majorEvery == 0
            line(if (isMajor) major else minor, x, y0, x, y1)
        }
        val ny = Math.round((y1 - y0) / step).coerceIn(0, MAX_GRID_LINES)
        for (i in 0..ny) {
            val y = y0 + i * step
            val isMajor = Math.round(y / step) % majorEvery == 0
            line(if (isMajor) major else minor, x0, y, x1, y)
        }
        val data = (minor + major).toFloatArray()
        gridMinorCount = minor.size / 3
        gridMajorCount = major.size / 3

        gridVao = glGenVertexArrays()
        glBindVertexArray(gridVao)
        gridVbo = glGenBuffers()
        glBindBuffer(GL_ARRAY_BUFFER, gridVbo)
        glBufferData(GL_ARRAY_BUFFER, data, GL_STATIC_DRAW)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0L)
        glBindVertexArray(0)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
    }

    private fun freeMesh() {
        for (p in gpuParts) {
            glDeleteVertexArrays(p.vao)
            glDeleteBuffers(p.vbo)
            if (p.ebo != 0) glDeleteBuffers(p.ebo)
        }
        gpuParts.clear()
    }

    private fun freeGrid() {
        if (gridVao != 0) glDeleteVertexArrays(gridVao)
        if (gridVbo != 0) glDeleteBuffers(gridVbo)
        gridVao = 0; gridVbo = 0; gridMinorCount = 0; gridMajorCount = 0
    }

    // ---- Input ------------------------------------------------------------

    private fun installInput() {
        val mouse = object : MouseAdapter() {
            private var lastX = 0
            private var lastY = 0

            override fun mousePressed(e: MouseEvent) {
                requestFocusInWindow()
                lastX = e.x; lastY = e.y
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) resetView()
            }

            override fun mouseDragged(e: MouseEvent) {
                val dx = e.x - lastX
                val dy = e.y - lastY
                lastX = e.x; lastY = e.y
                val pan = SwingUtilities.isRightMouseButton(e) || SwingUtilities.isMiddleMouseButton(e) ||
                    (SwingUtilities.isLeftMouseButton(e) && e.isShiftDown)
                if (pan) camera.pan(dx, dy, height) else if (SwingUtilities.isLeftMouseButton(e)) camera.orbit(dx, dy)
                requestRender()
            }

            override fun mouseWheelMoved(e: MouseWheelEvent) {
                camera.zoom(e.preciseWheelRotation)
                requestRender()
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener(mouse)

        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (!e.isAltDown) handleKey(e.keyCode, e.isControlDown || e.isMetaDown)
            }
        })
    }

    private inner class FileDropListener : DropTargetAdapter() {
        override fun dragEnter(e: DropTargetDragEvent) {
            if (e.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) e.acceptDrag(DnDConstants.ACTION_COPY)
            else e.rejectDrag()
        }

        override fun drop(e: DropTargetDropEvent) {
            try {
                e.acceptDrop(DnDConstants.ACTION_COPY)
                val files = e.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
                val dropped = files?.filterIsInstance<File>().orEmpty()
                // Nothing supported: pass the first file on anyway so the status bar says why.
                val file = dropped.firstOrNull { ModelLoader.isSupported(it) } ?: dropped.firstOrNull()
                e.dropComplete(file != null)
                if (file != null) onFileDropped(file)
            } catch (ex: Exception) {
                e.dropComplete(false)
            }
        }
    }

    // ---- Shaders ----------------------------------------------------------

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = glCreateShader(type)
            glShaderSource(s, src)
            glCompileShader(s)
            if (glGetShaderi(s, GL_COMPILE_STATUS) == 0) throw IllegalStateException("Shader compile failed: ${glGetShaderInfoLog(s)}")
            return s
        }
        val v = compile(GL_VERTEX_SHADER, vs)
        val f = compile(GL_FRAGMENT_SHADER, fs)
        val p = glCreateProgram()
        glAttachShader(p, v)
        glAttachShader(p, f)
        glBindAttribLocation(p, 0, "aPos")
        glLinkProgram(p)
        if (glGetProgrami(p, GL_LINK_STATUS) == 0) throw IllegalStateException("Shader link failed: ${glGetProgramInfoLog(p)}")
        glDeleteShader(v)
        glDeleteShader(f)
        return p
    }

    private companion object {
        const val DEFAULT_COLOR = 0x8FA3BF
        const val MAX_GRID_LINES = 2000

        fun glData() = GLData().apply {
            majorVersion = 3
            minorVersion = 3
            profile = GLData.Profile.CORE
            samples = 4
        }

        fun rgb(c: Int) = floatArrayOf(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)

        const val MESH_VS = """#version 330 core
in vec3 aPos;
uniform mat4 uView;
uniform mat4 uProj;
out vec3 vViewPos;
void main() {
    vec4 p = uView * vec4(aPos, 1.0);
    vViewPos = p.xyz;
    gl_Position = uProj * p;
}"""

        // Flat shading from screen-space derivatives: no normals needed in the vertex data,
        // and inverted/inconsistent winding in the source file cannot produce black faces.
        const val MESH_FS = """#version 330 core
in vec3 vViewPos;
uniform vec3 uColor;
uniform int uWire;
out vec4 fragColor;
void main() {
    if (uWire == 1) { fragColor = vec4(mix(uColor, vec3(1.0), 0.35), 1.0); return; }
    vec3 n = normalize(cross(dFdx(vViewPos), dFdy(vViewPos)));
    vec3 v = normalize(-vViewPos);
    vec3 key = normalize(vec3(-0.45, 0.65, 0.65));
    vec3 fill = normalize(vec3(0.6, -0.3, 0.5));
    float diff = max(dot(n, key), 0.0) * 0.70 + max(dot(n, fill), 0.0) * 0.25;
    float spec = pow(max(dot(n, normalize(key + v)), 0.0), 40.0) * 0.18;
    float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0) * 0.12;
    vec3 c = uColor * (0.28 + diff) + vec3(spec + rim);
    fragColor = vec4(c, 1.0);
}"""

        const val LINE_VS = """#version 330 core
in vec3 aPos;
uniform mat4 uView;
uniform mat4 uProj;
void main() { gl_Position = uProj * uView * vec4(aPos, 1.0); }"""

        const val LINE_FS = """#version 330 core
uniform vec4 uColor;
out vec4 fragColor;
void main() { fragColor = uColor; }"""
    }
}
