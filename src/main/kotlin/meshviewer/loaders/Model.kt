package meshviewer.loaders

import java.io.File

/** One drawable mesh. Positions are xyz triplets in millimetres, already in world space. */
class MeshPart(
    val positions: FloatArray,
    /** Triangle indices, or null when [positions] is a plain triangle soup (STL). */
    val indices: IntArray?,
    /** 0xRRGGBB, or null for the default colour. */
    val color: Int?,
) {
    val triangleCount: Int get() = (indices?.size ?: (positions.size / 3)) / 3
}

class Model(
    val parts: List<MeshPart>,
    val objectCount: Int,
) {
    val min = FloatArray(3) { Float.POSITIVE_INFINITY }
    val max = FloatArray(3) { Float.NEGATIVE_INFINITY }
    val triangleCount: Long = parts.sumOf { it.triangleCount.toLong() }

    init {
        for (p in parts) {
            val a = p.positions
            var i = 0
            while (i < a.size) {
                for (k in 0..2) {
                    val v = a[i + k]
                    // NaN/Infinity (corrupt file) would break the camera and the grid.
                    if (!v.isFinite()) throw IllegalStateException("The model contains invalid coordinates")
                    if (v < min[k]) min[k] = v
                    if (v > max[k]) max[k] = v
                }
                i += 3
            }
        }
        if (parts.isEmpty() || min[0] > max[0]) {
            for (k in 0..2) { min[k] = 0f; max[k] = 0f }
        }
    }

    val isEmpty: Boolean get() = triangleCount == 0L
}

object ModelLoader {
    val extensions = setOf("stl", "3mf")

    fun isSupported(file: File) = file.extension.lowercase() in extensions

    fun load(file: File): Model = when (file.extension.lowercase()) {
        "stl" -> StlLoader.load(file)
        "3mf" -> ThreeMfLoader.load(file)
        else -> throw IllegalArgumentException("Unsupported file type: .${file.extension}")
    }
}

/** Growable primitive arrays, avoids boxing for meshes with millions of vertices. */
internal class FloatList(initial: Int = 1024) {
    var data = FloatArray(initial.coerceAtLeast(16)); private set
    var size = 0; private set
    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }
    fun toArray(): FloatArray = data.copyOf(size)
}

internal class IntList(initial: Int = 1024) {
    var data = IntArray(initial.coerceAtLeast(16)); private set
    var size = 0; private set
    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }
    fun toArray(): IntArray = data.copyOf(size)
}
