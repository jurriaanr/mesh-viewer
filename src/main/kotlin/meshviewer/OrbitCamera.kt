package meshviewer

import kotlin.math.*

/** Z-up orbit camera (3D printing convention). Matrices are column-major for OpenGL. */
class OrbitCamera {
    val fovY = Math.toRadians(35.0)

    private val target = doubleArrayOf(0.0, 0.0, 0.0)
    private var yaw = DEFAULT_YAW
    private var pitch = DEFAULT_PITCH
    private var distance = 100.0
    private var radius = 50.0

    /** Frames the bounding sphere. [aspect] is viewport width / height. */
    fun fit(min: FloatArray, max: FloatArray, aspect: Double = 1.0) {
        for (k in 0..2) target[k] = (min[k] + max[k]) / 2.0
        val dx = (max[0] - min[0]).toDouble()
        val dy = (max[1] - min[1]).toDouble()
        val dz = (max[2] - min[2]).toDouble()
        radius = max(0.5 * sqrt(dx * dx + dy * dy + dz * dz), 1.0)
        // In a window taller than wide the horizontal field of view is the narrow one.
        val halfFov = if (aspect < 1.0) atan(tan(fovY / 2) * aspect) else fovY / 2
        distance = radius / sin(halfFov) * 1.05
        yaw = DEFAULT_YAW
        pitch = DEFAULT_PITCH
    }

    fun orbit(dxPixels: Int, dyPixels: Int) {
        yaw -= dxPixels * 0.008
        pitch = (pitch + dyPixels * 0.008).coerceIn(-PITCH_LIMIT, PITCH_LIMIT)
    }

    /** Moves the target so the model follows the cursor. */
    fun pan(dxPixels: Int, dyPixels: Int, viewportHeightPixels: Int) {
        if (viewportHeightPixels <= 0) return
        val unitsPerPixel = 2.0 * distance * tan(fovY / 2) / viewportHeightPixels
        val (right, up) = basis()
        for (k in 0..2) target[k] += (-right[k] * dxPixels + up[k] * dyPixels) * unitsPerPixel
    }

    fun zoom(wheelRotation: Double) {
        distance = (distance * 1.12.pow(wheelRotation)).coerceIn(radius * 0.02, radius * 200)
    }

    fun eye(): DoubleArray {
        val cp = cos(pitch)
        return doubleArrayOf(
            target[0] + distance * cp * cos(yaw),
            target[1] + distance * cp * sin(yaw),
            target[2] + distance * sin(pitch),
        )
    }

    fun viewMatrix(): FloatArray {
        val eye = eye()
        val f = normalize(doubleArrayOf(target[0] - eye[0], target[1] - eye[1], target[2] - eye[2]))
        val s = normalize(cross(f, UP))
        val u = cross(s, f)
        val m = FloatArray(16)
        m[0] = s[0].toFloat(); m[4] = s[1].toFloat(); m[8] = s[2].toFloat()
        m[1] = u[0].toFloat(); m[5] = u[1].toFloat(); m[9] = u[2].toFloat()
        m[2] = -f[0].toFloat(); m[6] = -f[1].toFloat(); m[10] = -f[2].toFloat()
        m[12] = (-dot(s, eye)).toFloat()
        m[13] = (-dot(u, eye)).toFloat()
        m[14] = dot(f, eye).toFloat()
        m[15] = 1f
        return m
    }

    fun projectionMatrix(aspect: Double): FloatArray {
        val near = max(distance * 0.01, 0.01)
        val far = distance * 10 + radius * 4
        val f = 1.0 / tan(fovY / 2)
        val m = FloatArray(16)
        m[0] = (f / aspect).toFloat()
        m[5] = f.toFloat()
        m[10] = ((far + near) / (near - far)).toFloat()
        m[11] = -1f
        m[14] = (2 * far * near / (near - far)).toFloat()
        return m
    }

    private fun basis(): Pair<DoubleArray, DoubleArray> {
        val eye = eye()
        val f = normalize(doubleArrayOf(target[0] - eye[0], target[1] - eye[1], target[2] - eye[2]))
        val s = normalize(cross(f, UP))
        return s to cross(s, f)
    }

    private companion object {
        val UP = doubleArrayOf(0.0, 0.0, 1.0)
        val DEFAULT_YAW = Math.toRadians(-60.0)  // front-right
        val DEFAULT_PITCH = Math.toRadians(25.0)
        val PITCH_LIMIT = Math.toRadians(89.0)

        fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0],
        )

        fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

        fun normalize(a: DoubleArray): DoubleArray {
            val l = sqrt(dot(a, a)).takeIf { it > 0 } ?: 1.0
            return doubleArrayOf(a[0] / l, a[1] / l, a[2] / l)
        }
    }
}
