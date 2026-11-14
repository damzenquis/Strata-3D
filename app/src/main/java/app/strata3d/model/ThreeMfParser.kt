package app.strata3d.model

import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

class ThreeMfParseException(message: String, cause: Throwable? = null) : ModelParseException(message, cause)

/**
 * Parser for the 3D Manufacturing Format (`.3mf`).
 *
 * A 3MF file is an OPC (Open Packaging Conventions) ZIP archive holding one or more `*.model` XML parts.
 * Every model part is read with SAX. The root part (named by `_rels/.rels`, normally `3D/3dmodel.model`)
 * supplies the `<build>` items; each item points at an object that is either a `<mesh>` or a set of
 * `<components>`. Components may reference objects in other parts through the Production extension's
 * `p:path` attribute (the layout Bambu Studio and Orca Slicer use). Transforms are 4×3 row-vector matrices
 * and are composed from the component up to the build item. Materials, textures and metadata are ignored,
 * matching the single-colour renderer used for STL/OBJ.
 */
object ThreeMfParser {

    private const val DEFAULT_ROOT = "3d/3dmodel.model"
    private const val MAX_COMPONENT_DEPTH = 32

    private val IDENTITY = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f,
        0f, 0f, 0f,
    )

    fun parse(input: InputStream, knownLength: Long = -1L): Mesh {
        val stream = input as? BufferedInputStream ?: BufferedInputStream(input, 1 shl 16)
        val zip = ZipInputStream(stream)

        // Part name (lower-case, no leading '/') -> parsed contents.
        val parts = LinkedHashMap<String, ModelPart>()
        var rootFromRels: String? = null

        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory) {
                val name = normalizePath(entry.name)
                when {
                    name.endsWith(".model") -> parts[name] = parseModelPart(NonClosingStream(zip), name)
                    name == "_rels/.rels" -> rootFromRels = findRootPart(String(zip.readBytes(), Charsets.UTF_8))
                }
            }
            entry = zip.nextEntry
        }
        if (parts.isEmpty()) {
            throw ThreeMfParseException("No 3D model part (*.model) found in the 3MF package")
        }

        val rootName = listOfNotNull(rootFromRels, DEFAULT_ROOT).firstOrNull { it in parts }
            ?: parts.entries.firstOrNull { it.value.buildItems.isNotEmpty() }?.key
            ?: parts.keys.first()
        val root = parts.getValue(rootName)

        val builder = MeshBuilder(1024)
        val tri = FloatArray(9)
        for (item in root.buildItems) {
            emitObject(parts, rootName, item.objectId, item.transform, builder, tri, depth = 0)
        }
        if (builder.triangleCount == 0) {
            throw ThreeMfParseException("The 3MF file does not contain any buildable triangles")
        }
        return builder.build(LengthUnit.from3mf(root.unit))
    }

    /** Emits an object's mesh (and, recursively, its components) with [transform] applied. */
    private fun emitObject(
        parts: Map<String, ModelPart>,
        partName: String,
        objectId: String,
        transform: FloatArray,
        builder: MeshBuilder,
        tri: FloatArray,
        depth: Int,
    ) {
        if (depth > MAX_COMPONENT_DEPTH) {
            throw ThreeMfParseException("3MF components are nested too deeply (or reference themselves)")
        }
        val part = parts[partName]
            ?: throw ThreeMfParseException("3MF component references a missing part '/$partName'")
        val obj = part.objects[objectId] ?: return

        val t = transform
        val vertices = obj.vertices
        val indices = obj.triangles
        var i = 0
        while (i < indices.size) {
            for (corner in 0 until 3) {
                val vi = indices.get(i + corner)
                if (vi < 0 || vi * 3 + 2 >= vertices.size) {
                    throw ThreeMfParseException("Triangle vertex index $vi out of bounds in object '${obj.id}'")
                }
                val x = vertices.get(vi * 3)
                val y = vertices.get(vi * 3 + 1)
                val z = vertices.get(vi * 3 + 2)
                tri[corner * 3] = t[0] * x + t[3] * y + t[6] * z + t[9]
                tri[corner * 3 + 1] = t[1] * x + t[4] * y + t[7] * z + t[10]
                tri[corner * 3 + 2] = t[2] * x + t[5] * y + t[8] * z + t[11]
            }
            builder.addTriangle(tri)
            i += 3
        }

        for (component in obj.components) {
            val childPart = component.path ?: partName
            emitObject(parts, childPart, component.objectId, compose(component.transform, transform), builder, tri, depth + 1)
        }
    }

    /**
     * Combines two 3MF row-vector transforms: the result applies [first], then [second].
     * Layout (both inputs and output): rows m0..m3, three columns each; row 3 is the translation.
     */
    internal fun compose(first: FloatArray, second: FloatArray): FloatArray {
        val out = FloatArray(12)
        for (r in 0 until 4) {
            for (c in 0 until 3) {
                var sum = 0f
                for (k in 0 until 3) sum += first[r * 3 + k] * second[k * 3 + c]
                if (r == 3) sum += second[9 + c]
                out[r * 3 + c] = sum
            }
        }
        return out
    }

    private fun parseModelPart(xml: InputStream, partName: String): ModelPart {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        val handler = Handler()
        try {
            factory.newSAXParser().parse(xml, handler)
        } catch (e: ThreeMfParseException) {
            throw e
        } catch (e: Exception) {
            throw ThreeMfParseException("Invalid 3MF model XML in '/$partName': ${e.message}", e)
        }
        return ModelPart(handler.objects, handler.buildItems, handler.unit)
    }

    /** Finds the 3D model part named as the package's start part in `_rels/.rels`. */
    private fun findRootPart(relsXml: String): String? {
        val relationship = Regex("<Relationship\\b[^>]*>", RegexOption.IGNORE_CASE)
        for (match in relationship.findAll(relsXml)) {
            val tag = match.value
            val type = Regex("Type\\s*=\\s*\"([^\"]*)\"").find(tag)?.groupValues?.get(1) ?: continue
            if (!type.endsWith("/3dmodel")) continue
            val target = Regex("Target\\s*=\\s*\"([^\"]*)\"").find(tag)?.groupValues?.get(1) ?: continue
            return normalizePath(target)
        }
        return null
    }

    private fun normalizePath(path: String): String = path.trim().removePrefix("/").replace('\\', '/').lowercase()

    private fun parseTransform(value: String?): FloatArray {
        if (value == null || value.isBlank()) return IDENTITY
        val parts = value.trim().split(Regex("\\s+"))
        if (parts.size != 12) {
            throw ThreeMfParseException("3MF transform must contain 12 values, found ${parts.size}")
        }
        return FloatArray(12) { i ->
            parts[i].toFloatOrNull()
                ?: throw ThreeMfParseException("Invalid 3MF transform value '${parts[i]}'")
        }
    }

    // --- Parsed structures ----------------------------------------------------

    private class ModelPart(val objects: Map<String, Object3mf>, val buildItems: List<BuildItem>, val unit: String?)

    private class Object3mf(val id: String) {
        val vertices = FloatList()
        val triangles = IntList()
        val components = ArrayList<Component>()
    }

    /** [path] is the referenced part (Production extension `p:path`), or null for the same part. */
    private class Component(val objectId: String, val path: String?, val transform: FloatArray)

    private class BuildItem(val objectId: String, val transform: FloatArray)

    // --- SAX handler ----------------------------------------------------------

    private class Handler : DefaultHandler() {
        val objects = HashMap<String, Object3mf>()
        val buildItems = ArrayList<BuildItem>()
        var unit: String? = null
        private var currentObject: Object3mf? = null

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            if (attributes == null) return
            val name = localName?.ifEmpty { null } ?: qName?.substringAfterLast(':') ?: return
            when (name) {
                "model" -> unit = attr(attributes, "unit")
                "object" -> {
                    val id = attr(attributes, "id") ?: return
                    currentObject = Object3mf(id)
                }
                "vertex" -> currentObject?.let { obj ->
                    val x = parseFloat(attr(attributes, "x"), "vertex x")
                    val y = parseFloat(attr(attributes, "y"), "vertex y")
                    val z = parseFloat(attr(attributes, "z"), "vertex z")
                    obj.vertices.add(x); obj.vertices.add(y); obj.vertices.add(z)
                }
                "triangle" -> currentObject?.let { obj ->
                    val v1 = parseInt(attr(attributes, "v1"), "triangle v1")
                    val v2 = parseInt(attr(attributes, "v2"), "triangle v2")
                    val v3 = parseInt(attr(attributes, "v3"), "triangle v3")
                    obj.triangles.add(v1); obj.triangles.add(v2); obj.triangles.add(v3)
                }
                "component" -> currentObject?.let { obj ->
                    val objectId = attr(attributes, "objectid") ?: return@let
                    val path = attr(attributes, "path")?.takeIf { it.isNotBlank() }
                        ?.trim()?.removePrefix("/")?.replace('\\', '/')?.lowercase()
                    obj.components.add(Component(objectId, path, parseTransform(attr(attributes, "transform"))))
                }
                "item" -> {
                    val objectId = attr(attributes, "objectid")
                    if (objectId != null) {
                        buildItems.add(BuildItem(objectId, parseTransform(attr(attributes, "transform"))))
                    }
                }
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val name = localName?.ifEmpty { null } ?: qName?.substringAfterLast(':') ?: return
            if (name == "object") {
                currentObject?.let { objects[it.id] = it }
                currentObject = null
            }
        }

        private fun attr(attributes: Attributes, name: String): String? {
            for (i in 0 until attributes.length) {
                val local = attributes.getLocalName(i)
                val qn = attributes.getQName(i)
                if (local == name || qn == name || qn.substringAfterLast(':') == name) {
                    return attributes.getValue(i)
                }
            }
            return null
        }

        private fun parseFloat(value: String?, what: String): Float =
            value?.toFloatOrNull() ?: throw ThreeMfParseException("Invalid $what in 3MF model")

        private fun parseInt(value: String?, what: String): Int =
            value?.toIntOrNull() ?: throw ThreeMfParseException("Invalid $what in 3MF model")
    }

    /** Lets the SAX parser read one ZIP entry without closing the whole archive when it finishes. */
    private class NonClosingStream(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }

    // --- Growable primitive lists ---------------------------------------------

    private class FloatList(initialCapacity: Int = 256) {
        private var data = FloatArray(initialCapacity)
        var size = 0
            private set

        fun add(v: Float) {
            if (size >= data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        fun get(i: Int): Float = data[i]
    }

    private class IntList(initialCapacity: Int = 256) {
        private var data = IntArray(initialCapacity)
        var size = 0
            private set

        fun add(v: Int) {
            if (size >= data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        fun get(i: Int): Int = data[i]
    }
}
