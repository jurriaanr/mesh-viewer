package meshviewer.loaders

import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

/**
 * 3MF reader: core spec (objects, meshes, components, build items, transforms, units),
 * the Production extension (components referencing other .model files via p:path, as
 * written by Bambu Studio / OrcaSlicer / PrusaSlicer) and basematerials/colorgroup
 * colours resolved per object. Per-triangle colours and textures are ignored.
 */
object ThreeMfLoader {

    private const val DEFAULT_ROOT = "3D/3dmodel.model"
    private const val MAX_VISITS = 1_000_000

    private class Component(val path: String?, val objectId: Int, val transform: FloatArray?)

    private class Obj(val id: Int, val pid: Int?, val pindex: Int?) {
        var vertices: FloatArray? = null
        var triangles: IntArray? = null
        val components = ArrayList<Component>()
    }

    private class Item(val path: String?, val objectId: Int, val transform: FloatArray?)

    private class ModelFile(
        val objects: Map<Int, Obj>,
        val items: List<Item>,
        val unitScale: Float,
        val colors: Map<Int, IntArray>,
    )

    fun load(file: File): Model {
        ZipFile(file).use { zip ->
            val entries = HashMap<String, ZipEntry>()
            for (e in zip.entries().asSequence()) entries[e.name.trimStart('/').lowercase()] = e

            fun open(path: String): InputStream? =
                entries[normalize(path).lowercase()]?.let { zip.getInputStream(it) }

            val rootPath = findRootModel(::open) ?: DEFAULT_ROOT
            val cache = HashMap<String, ModelFile>()
            fun modelFile(path: String): ModelFile? {
                val key = normalize(path).lowercase()
                cache[key]?.let { return it }
                val parsed = open(path)?.use { parseModel(it) } ?: return null
                cache[key] = parsed
                return parsed
            }

            val root = modelFile(rootPath)
                ?: throw IllegalStateException("No 3D model found in ${file.name}")
            val scale = root.unitScale
            val parts = ArrayList<MeshPart>()

            var visits = 0
            fun emit(path: String, objectId: Int, transform: FloatArray, inheritedColor: Int?, depth: Int) {
                if (depth > 32) return
                // Nested components that each reference the next level twice grow exponentially.
                if (++visits > MAX_VISITS) throw IllegalStateException("Invalid 3MF: too many components")
                val mf = modelFile(path) ?: return
                val obj = mf.objects[objectId] ?: return
                val color = resolveColor(mf, obj) ?: inheritedColor
                val verts = obj.vertices
                val tris = obj.triangles
                if (verts != null && tris != null && tris.isNotEmpty()) {
                    parts += MeshPart(transformPositions(verts, transform, scale), tris, color)
                }
                for (c in obj.components) {
                    // 3MF uses row vectors: p' = p * Mcomponent * Mparent
                    val childT = multiply(c.transform ?: IDENTITY, transform)
                    emit(c.path ?: path, c.objectId, childT, color, depth + 1)
                }
            }

            val items = root.items
            if (items.isNotEmpty()) {
                for (item in items) emit(item.path ?: rootPath, item.objectId, item.transform ?: IDENTITY, null, 0)
            } else {
                // No build section: show every object that is not referenced as a component.
                val referenced = root.objects.values.flatMap { o -> o.components.filter { it.path == null }.map { it.objectId } }.toSet()
                for (id in root.objects.keys) if (id !in referenced) emit(rootPath, id, IDENTITY, null, 0)
            }

            if (parts.isEmpty()) throw IllegalStateException("No printable meshes found in ${file.name}")
            return Model(parts, objectCount = if (items.isNotEmpty()) items.size else parts.size)
        }
    }

    private fun normalize(path: String) = path.replace('\\', '/').trimStart('/')

    private fun xmlFactory(): XMLInputFactory = XMLInputFactory.newInstance().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
    }

    private fun XMLStreamReader.attr(localName: String): String? {
        for (i in 0 until attributeCount) {
            if (getAttributeLocalName(i) == localName) return getAttributeValue(i)
        }
        return null
    }

    private fun XMLStreamReader.req(name: String): String =
        attr(name) ?: throw IllegalStateException("Invalid 3MF: <$localName> has no '$name' attribute")

    /** Reads _rels/.rels and returns the target of the 3D model relationship. */
    private fun findRootModel(open: (String) -> InputStream?): String? {
        val stream = open("_rels/.rels") ?: return null
        stream.use {
            val r = xmlFactory().createXMLStreamReader(it)
            try {
                while (r.hasNext()) {
                    if (r.next() == XMLStreamConstants.START_ELEMENT && r.localName == "Relationship") {
                        val type = r.attr("Type") ?: continue
                        if (type.endsWith("/3dmodel")) return r.attr("Target")?.let(::normalize)
                    }
                }
            } finally {
                r.close()
            }
        }
        return null
    }

    private fun parseModel(input: InputStream): ModelFile {
        val r = xmlFactory().createXMLStreamReader(input)
        val objects = HashMap<Int, Obj>()
        val items = ArrayList<Item>()
        val colors = HashMap<Int, IntArray>()
        var unitScale = 1f

        var current: Obj? = null
        var verts: FloatList? = null
        var tris: IntList? = null
        var colorGroupId: Int? = null
        var colorGroup: IntList? = null

        try {
            while (r.hasNext()) {
                when (r.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (r.localName) {
                        "vertex" -> verts?.let { v ->
                            v.add(r.req("x").toFloat())
                            v.add(r.req("y").toFloat())
                            v.add(r.req("z").toFloat())
                        }
                        "triangle" -> tris?.let { t ->
                            t.add(r.req("v1").toInt())
                            t.add(r.req("v2").toInt())
                            t.add(r.req("v3").toInt())
                        }
                        "model" -> unitScale = unitToMm(r.attr("unit"))
                        "object" -> current = Obj(
                            r.req("id").toInt(),
                            r.attr("pid")?.toIntOrNull(),
                            r.attr("pindex")?.toIntOrNull(),
                        )
                        "mesh" -> { verts = FloatList(1 shl 12); tris = IntList(1 shl 12) }
                        "component" -> current?.components?.add(
                            Component(r.attr("path"), r.req("objectid").toInt(), parseTransform(r.attr("transform")))
                        )
                        "item" -> items += Item(r.attr("path"), r.req("objectid").toInt(), parseTransform(r.attr("transform")))
                        "basematerials", "colorgroup" -> {
                            colorGroupId = r.attr("id")?.toIntOrNull()
                            colorGroup = IntList(16)
                        }
                        "base" -> colorGroup?.add(parseColor(r.attr("displaycolor")))
                        "color" -> colorGroup?.add(parseColor(r.attr("color")))
                    }

                    XMLStreamConstants.END_ELEMENT -> when (r.localName) {
                        "mesh" -> {
                            val v = verts?.toArray()
                            val t = tris?.toArray()
                            if (v != null && t != null) {
                                // An index past the vertex buffer is undefined behaviour in the GPU driver.
                                val vertexCount = v.size / 3
                                for (index in t) {
                                    if (index < 0 || index >= vertexCount) {
                                        throw IllegalStateException("Invalid 3MF: a triangle refers to a vertex that does not exist")
                                    }
                                }
                            }
                            current?.vertices = v
                            current?.triangles = t
                            verts = null; tris = null
                        }
                        "object" -> {
                            current?.let { objects[it.id] = it }
                            current = null
                        }
                        "basematerials", "colorgroup" -> {
                            val id = colorGroupId
                            val group = colorGroup
                            if (id != null && group != null) colors[id] = group.toArray()
                            colorGroupId = null; colorGroup = null
                        }
                    }
                }
            }
        } catch (e: NumberFormatException) {
            throw IllegalStateException("Invalid 3MF: malformed number (${e.message})")
        } finally {
            r.close()
        }
        return ModelFile(objects, items, unitScale, colors)
    }

    private fun resolveColor(mf: ModelFile, obj: Obj): Int? {
        val pid = obj.pid ?: return null
        val c = mf.colors[pid]?.getOrNull(obj.pindex ?: 0) ?: return null
        return if (c < 0) null else c
    }

    /** "#RRGGBB" or "#RRGGBBAA" -> 0xRRGGBB, -1 when missing/invalid. */
    private fun parseColor(s: String?): Int {
        if (s == null || !s.startsWith("#") || s.length < 7) return -1
        return s.substring(1, 7).toIntOrNull(16) ?: -1
    }

    private fun unitToMm(unit: String?): Float = when (unit?.lowercase()) {
        "micron" -> 0.001f
        "centimeter" -> 10f
        "inch" -> 25.4f
        "foot" -> 304.8f
        "meter" -> 1000f
        else -> 1f // millimeter (default)
    }

    // Transforms are the 12 numbers of a 3MF matrix: m00 m01 m02 m10 m11 m12 m20 m21 m22 m30 m31 m32,
    // i.e. a 4x3 row-major matrix whose implicit 4th column is (0, 0, 0, 1).
    private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f)

    private fun parseTransform(s: String?): FloatArray? {
        if (s.isNullOrBlank()) return null
        val parts = s.trim().split(Regex("\\s+"))
        if (parts.size != 12) return null
        return FloatArray(12) { parts[it].toFloat() }
    }

    /** a * b for 3MF 4x3 matrices. */
    private fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val c = FloatArray(12)
        for (row in 0..3) {
            for (col in 0..2) {
                var sum = 0f
                for (k in 0..2) sum += a[row * 3 + k] * b[k * 3 + col]
                if (row == 3) sum += b[9 + col] // a[3][3] == 1
                c[row * 3 + col] = sum
            }
        }
        return c
    }

    private fun transformPositions(v: FloatArray, m: FloatArray, scale: Float): FloatArray {
        val out = FloatArray(v.size)
        var i = 0
        while (i < v.size) {
            val x = v[i]; val y = v[i + 1]; val z = v[i + 2]
            out[i] = (x * m[0] + y * m[3] + z * m[6] + m[9]) * scale
            out[i + 1] = (x * m[1] + y * m[4] + z * m[7] + m[10]) * scale
            out[i + 2] = (x * m[2] + y * m[5] + z * m[8] + m[11]) * scale
            i += 3
        }
        return out
    }
}
