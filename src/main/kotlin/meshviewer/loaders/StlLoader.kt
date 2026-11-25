package meshviewer.loaders

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Binary and ASCII STL. Stored facet normals are ignored; shading uses screen-space normals. */
object StlLoader {

    fun load(file: File): Model {
        val bytes = file.readBytes()
        val positions = if (isBinary(bytes)) parseBinary(bytes) else parseAscii(bytes)
        if (positions.isEmpty()) throw IllegalStateException("No triangles found in ${file.name}")
        return Model(listOf(MeshPart(positions, null, null)), objectCount = 1)
    }

    private fun isBinary(b: ByteArray): Boolean {
        if (b.size < 84) return false
        val count = ByteBuffer.wrap(b, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        if (84L + count * 50L == b.size.toLong()) return true
        // Size does not match exactly: decide by content. Many binary exporters also start
        // their header with "solid", so look for ASCII keywords as well.
        val headLen = minOf(b.size, 1024)
        val head = String(b, 0, headLen, Charsets.US_ASCII)
        val looksAscii = head.trimStart().startsWith("solid", ignoreCase = true) &&
            (head.contains("facet", ignoreCase = true) || head.contains("endsolid", ignoreCase = true))
        return !looksAscii
    }

    private fun parseBinary(b: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val declared = buf.getInt(80).toLong() and 0xFFFFFFFFL
        val fits = (b.size - 84L) / 50L
        val count = minOf(declared, fits).toInt()
        val out = FloatArray(count * 9)
        var o = 0
        for (t in 0 until count) {
            val base = 84 + t * 50 + 12 // skip the facet normal
            for (k in 0 until 9) out[o++] = buf.getFloat(base + k * 4)
        }
        return out
    }

    private fun parseAscii(b: ByteArray): FloatArray {
        val out = FloatList(1 shl 16)
        val n = b.size
        var i = 0

        fun nextToken(): String? {
            while (i < n && (b[i].toInt() and 0xFF) <= 32) i++
            if (i >= n) return null
            val start = i
            while (i < n && (b[i].toInt() and 0xFF) > 32) i++
            return String(b, start, i - start, Charsets.US_ASCII)
        }

        while (true) {
            val t = nextToken() ?: break
            if (t.equals("vertex", ignoreCase = true)) {
                for (k in 0..2) {
                    val v = nextToken() ?: throw IllegalStateException("Truncated ASCII STL")
                    out.add(v.toFloat())
                }
            }
        }
        val arr = out.toArray()
        return if (arr.size % 9 == 0) arr else arr.copyOf(arr.size - arr.size % 9)
    }
}
