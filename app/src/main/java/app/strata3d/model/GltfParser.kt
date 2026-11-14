package app.strata3d.model

import com.openize.drako.Draco
import com.openize.drako.DracoMesh
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class GltfParseException(message: String, cause: Throwable? = null) : ModelParseException(message, cause)

/**
 * Parser for glTF 2.0 (`.gltf`) and its binary container GLB (`.glb`).
 *
 * Supports:
 * - GLB (JSON + BIN chunks), base64 `data:` URI buffers, and external buffer files (e.g. `scene.bin`) when the
 *   caller supplies them through [ModelResources] (multi-file pick or a `.zip` package).
 * - `buffers` / `bufferViews` / `accessors` / `meshes` / `nodes` / `scenes` resolution.
 * - POSITION accessors stored as float or as (normalized) 8/16-bit integers (`KHR_mesh_quantization`),
 *   sparse accessors, and accessors without a bufferView (all zeros, as the spec defines).
 * - Draco-compressed primitives (`KHR_draco_mesh_compression`) via the Openize Drako decoder.
 * - meshoptimizer-compressed buffer views (`EXT_meshopt_compression` / `KHR_meshopt_compression`) via [MeshoptDecoder].
 * - Indexed and non-indexed triangle primitives, plus triangle strip/fan modes.
 * - Node transforms via `matrix` or `translation`/`rotation`/`scale`, composed up the scene graph.
 *
 * Materials, textures and vertex attributes other than POSITION are ignored, matching the single-colour
 * renderer used for the other formats.
 */
object GltfParser {

    private const val GLB_MAGIC = 0x46546C67L  // "glTF"
    private const val CHUNK_JSON = 0x4E4F534A  // "JSON"
    private const val CHUNK_BIN = 0x004E4942   // "BIN\0"

    private const val COMP_BYTE = 5120
    private const val COMP_UNSIGNED_BYTE = 5121
    private const val COMP_SHORT = 5122
    private const val COMP_UNSIGNED_SHORT = 5123
    private const val COMP_UNSIGNED_INT = 5125
    private const val COMP_FLOAT = 5126

    private const val MODE_TRIANGLES = 4
    private const val MODE_TRIANGLE_STRIP = 5
    private const val MODE_TRIANGLE_FAN = 6

    private const val EXT_DRACO = "KHR_draco_mesh_compression"
    private val EXT_MESHOPT = listOf("EXT_meshopt_compression", "KHR_meshopt_compression")

    /** Extensions that only add data this viewer ignores (or that it decodes itself), so they are safe. */
    private val SUPPORTED_REQUIRED_EXTENSIONS = setOf(
        EXT_DRACO,
        "EXT_meshopt_compression",
        "KHR_meshopt_compression",
        "KHR_mesh_quantization",
        "KHR_texture_transform",
        "KHR_texture_basisu",
        "KHR_materials_unlit",
        "KHR_materials_emissive_strength",
        "KHR_materials_variants",
        "EXT_texture_webp",
        "EXT_texture_avif",
    )

    private val IDENTITY_MATRIX = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    /** +90° about X, column-major: glTF's +Y up becomes +Z up, (x, y, z) → (x, −z, y). */
    private val Y_UP_TO_Z_UP = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 0f, 1f)

    /**
     * Parses a glTF or GLB. With [upright] (the default) the scene is turned from glTF's +Y up to the viewer's +Z up;
     * tests of the parsing itself read the file's own frame with `upright = false`.
     */
    fun parse(input: InputStream, knownLength: Long = -1L, resources: ModelResources? = null, upright: Boolean = true): Mesh {
        val stream = input as? BufferedInputStream ?: BufferedInputStream(input, 1 shl 16)
        val header = ByteArray(4)
        stream.mark(4)
        val n = readFully(stream, header, 0, 4)
        stream.reset()
        val isGlb = n == 4 && header[0] == 'g'.code.toByte() && header[1] == 'l'.code.toByte() &&
            header[2] == 'T'.code.toByte() && header[3] == 'F'.code.toByte()

        return if (isGlb) {
            val (jsonText, bin) = readGlb(stream)
            build(jsonText, bin, resources, upright)
        } else {
            build(readAllText(stream), null, resources, upright)
        }
    }

    // --- Container handling ---------------------------------------------------

    private fun readGlb(stream: InputStream): Pair<String, ByteArray?> {
        val din = DataInputStream(stream)
        try {
            val magic = readUInt32LE(din)
            if (magic != GLB_MAGIC) throw GltfParseException("Not a GLB file (bad magic)")
            val version = readUInt32LE(din)
            if (version != 2L) throw GltfParseException("Unsupported GLB version $version")
            readUInt32LE(din) // total length

            var jsonChunk: ByteArray? = null
            var binChunk: ByteArray? = null
            while (true) {
                val chunkLength: Long = try {
                    readUInt32LE(din)
                } catch (e: EOFException) {
                    break
                }
                val chunkType = readUInt32LE(din).toInt()
                if (chunkLength > Int.MAX_VALUE) throw GltfParseException("GLB chunk is too large")
                val data = ByteArray(chunkLength.toInt())
                din.readFully(data)
                when (chunkType) {
                    CHUNK_JSON -> jsonChunk = data
                    CHUNK_BIN -> binChunk = data
                }
                val padding = ((4 - (chunkLength % 4)) % 4).toInt()
                if (padding > 0) din.skipBytes(padding)
            }
            if (jsonChunk == null) throw GltfParseException("GLB file is missing the JSON chunk")
            return String(jsonChunk, Charsets.UTF_8) to binChunk
        } catch (e: GltfParseException) {
            throw e
        } catch (e: EOFException) {
            throw GltfParseException("Unexpected end of GLB file", e)
        }
    }

    private fun readUInt32LE(din: DataInputStream): Long =
        din.readUnsignedByte().toLong() or
            (din.readUnsignedByte().toLong() shl 8) or
            (din.readUnsignedByte().toLong() shl 16) or
            (din.readUnsignedByte().toLong() shl 24)

    private fun readAllText(stream: InputStream): String {
        val bytes = stream.readBytes()
        val offset = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) 3 else 0
        return String(bytes, offset, bytes.size - offset, Charsets.UTF_8)
    }

    // --- glTF entity parsing --------------------------------------------------

    private class BufferView(
        val buffer: Int,
        val byteOffset: Int,
        val byteLength: Int,
        val byteStride: Int?,
        val meshopt: MeshoptView?,
    )

    /** A buffer view whose real contents are meshopt-compressed bytes stored in another buffer. */
    private class MeshoptView(
        val buffer: Int,
        val byteOffset: Int,
        val byteLength: Int,
        val byteStride: Int,
        val count: Int,
        val mode: String,
        val filter: String?,
    )

    /** The bytes behind a buffer view: [data] from [offset] for [length] bytes, with an optional [stride]. */
    private class ViewBytes(val data: ByteArray, val offset: Int, val length: Int, val stride: Int?)

    /** Resolves buffer views to bytes, decoding (and caching) meshopt-compressed views on first use. */
    private class Views(private val bufferViews: List<BufferView>, private val buffers: Buffers) {
        private val decoded = HashMap<Int, ByteArray>()

        operator fun get(viewIdx: Int): ViewBytes {
            val view = bufferViews.getOrNull(viewIdx) ?: throw GltfParseException("Invalid bufferView index $viewIdx")
            val meshopt = view.meshopt
            if (meshopt != null) {
                val data = decoded.getOrPut(viewIdx) {
                    if (meshopt.buffer !in buffers.indices) throw GltfParseException("Invalid buffer index ${meshopt.buffer}")
                    val source = buffers[meshopt.buffer]
                    if (meshopt.byteOffset < 0 || meshopt.byteOffset + meshopt.byteLength > source.size) {
                        throw GltfParseException("Meshopt data exceeds buffer bounds")
                    }
                    MeshoptDecoder.decode(
                        source.copyOfRange(meshopt.byteOffset, meshopt.byteOffset + meshopt.byteLength),
                        meshopt.count, meshopt.byteStride, meshopt.mode, meshopt.filter,
                    )
                }
                return ViewBytes(data, 0, data.size, view.byteStride)
            }
            if (view.buffer !in buffers.indices) throw GltfParseException("Invalid buffer index ${view.buffer}")
            return ViewBytes(buffers[view.buffer], view.byteOffset, view.byteLength, view.byteStride)
        }
    }

    private class Sparse(
        val count: Int,
        val indicesView: Int,
        val indicesOffset: Int,
        val indicesComponentType: Int,
        val valuesView: Int,
        val valuesOffset: Int,
    )

    private class Accessor(
        val bufferView: Int?,
        val byteOffset: Int,
        val componentType: Int,
        val normalized: Boolean,
        val count: Int,
        val type: String,
        val sparse: Sparse?,
    )

    private class DracoPrimitive(val bufferView: Int, val positionId: Int)

    private class Primitive(
        val positionsAccessor: Int,
        val indicesAccessor: Int?,
        val mode: Int,
        val draco: DracoPrimitive?,
    )

    private class GltfMesh(val primitives: List<Primitive>)
    private class Node(
        val mesh: Int?,
        val children: IntArray,
        val matrix: FloatArray?,
        val translation: FloatArray?,
        val rotation: FloatArray?,
        val scale: FloatArray?,
    )
    private class Scene(val nodes: IntArray)

    /** Buffers are loaded on first use, so a file only fails on a buffer it actually needs. */
    private class Buffers(private val loaders: List<() -> ByteArray>) {
        private val cache = arrayOfNulls<ByteArray>(loaders.size)
        val indices get() = loaders.indices
        operator fun get(i: Int): ByteArray = cache[i] ?: loaders[i]().also { cache[i] = it }
    }

    private fun build(jsonText: String, binChunk: ByteArray?, resources: ModelResources?, upright: Boolean): Mesh {
        val root = JsonParser.parse(jsonText)
        checkRequiredExtensions(root)
        val buffers = resolveBuffers(root, binChunk, resources)
        val bufferViews = Views(parseBufferViews(root), buffers)
        val accessors = parseAccessors(root)
        val meshes = parseMeshes(root)
        val nodes = parseNodes(root)
        val scenes = parseScenes(root)

        val builder = MeshBuilder(1024)
        val visited = HashSet<Int>()
        for (rootIdx in resolveSceneRoots(root, scenes, nodes)) {
            // glTF is +Y up; the viewer (like STL and 3D printing) is +Z up. Turning the whole scene +90° about X
            // stands glTF models upright, as their authors intended.
            renderNode(rootIdx, if (upright) Y_UP_TO_Z_UP else IDENTITY_MATRIX, nodes, meshes, accessors, bufferViews, builder, visited)
        }
        return builder.build(LengthUnit.METER) // glTF 2.0 defines all distances in metres
    }

    private fun checkRequiredExtensions(root: JsonValue) {
        val required = root.get("extensionsRequired").asList()?.mapNotNull { it.asString() }.orEmpty()
        val unsupported = required.filter { it !in SUPPORTED_REQUIRED_EXTENSIONS }
        if (unsupported.isNotEmpty()) {
            throw GltfParseException(
                "This glTF needs ${unsupported.joinToString()}, which Strata:3D does not support yet",
            )
        }
    }

    private fun resolveBuffers(root: JsonValue, binChunk: ByteArray?, resources: ModelResources?): Buffers {
        val buffersJson = root.get("buffers")?.asList()
            ?: throw GltfParseException("glTF has no buffers array")
        val loaders = buffersJson.mapIndexed { i, b ->
            val uri = b.get("uri")?.asString()
            val isMeshoptFallback = EXT_MESHOPT.any { b.get("extensions").get(it).get("fallback")?.asBool() == true }
            when {
                isMeshoptFallback && uri == null -> {
                    { throw GltfParseException("Buffer $i is a meshopt placeholder with no data of its own") }
                }
                uri == null -> {
                    { binChunk ?: throw GltfParseException("Buffer $i has no uri and the file has no GLB BIN chunk") }
                }
                uri.startsWith("data:", ignoreCase = true) -> {
                    { decodeDataUri(uri) }
                }
                else -> {
                    { loadExternalBuffer(uri, resources) }
                }
            }
        }
        return Buffers(loaders)
    }

    private fun loadExternalBuffer(uri: String, resources: ModelResources?): ByteArray {
        val stream = resources?.open(uri) ?: throw GltfParseException(
            "This glTF keeps its data in a separate file (\"${ModelResources.fileName(uri)}\"). " +
                "Select the .gltf together with that file in the file picker, or open a .zip that contains both.",
        )
        return stream.use { it.readBytes() }
    }

    private fun decodeDataUri(uri: String): ByteArray {
        val comma = uri.indexOf(',')
        if (comma < 0) throw GltfParseException("Malformed data URI for buffer")
        val meta = uri.substring(0, comma)
        val payload = uri.substring(comma + 1)
        if (!meta.contains("base64", ignoreCase = true)) {
            throw GltfParseException("Only base64 data URIs are supported for glTF buffers")
        }
        return try {
            Base64.getDecoder().decode(payload)
        } catch (e: IllegalArgumentException) {
            throw GltfParseException("Invalid base64 in glTF buffer data URI", e)
        }
    }

    private fun parseBufferViews(root: JsonValue): List<BufferView> =
        root.get("bufferViews")?.asList()?.mapNotNull { bv ->
            val buffer = bv.get("buffer")?.asInt() ?: return@mapNotNull null
            val byteOffset = bv.get("byteOffset")?.asInt() ?: 0
            val byteLength = bv.get("byteLength")?.asInt() ?: return@mapNotNull null
            val byteStride = bv.get("byteStride")?.asInt()
            val meshopt = EXT_MESHOPT.firstNotNullOfOrNull { bv.get("extensions").get(it) }?.let { ext ->
                MeshoptView(
                    buffer = ext.get("buffer")?.asInt() ?: throw GltfParseException("Meshopt bufferView has no buffer"),
                    byteOffset = ext.get("byteOffset")?.asInt() ?: 0,
                    byteLength = ext.get("byteLength")?.asInt() ?: throw GltfParseException("Meshopt bufferView has no byteLength"),
                    byteStride = ext.get("byteStride")?.asInt() ?: throw GltfParseException("Meshopt bufferView has no byteStride"),
                    count = ext.get("count")?.asInt() ?: throw GltfParseException("Meshopt bufferView has no count"),
                    mode = ext.get("mode")?.asString() ?: throw GltfParseException("Meshopt bufferView has no mode"),
                    filter = ext.get("filter")?.asString(),
                )
            }
            BufferView(buffer, byteOffset, byteLength, byteStride, meshopt)
        } ?: emptyList()

    private fun parseAccessors(root: JsonValue): List<Accessor> =
        root.get("accessors")?.asList()?.map { a ->
            val componentType = a.get("componentType")?.asInt() ?: -1
            val count = a.get("count")?.asInt() ?: 0
            val type = a.get("type")?.asString() ?: ""
            val sparse = a.get("sparse")?.let { s ->
                val indices = s.get("indices")
                val values = s.get("values")
                val sparseCount = s.get("count")?.asInt()
                val indicesView = indices.get("bufferView")?.asInt()
                val valuesView = values.get("bufferView")?.asInt()
                if (sparseCount == null || indicesView == null || valuesView == null) {
                    throw GltfParseException("Malformed sparse accessor")
                }
                Sparse(
                    count = sparseCount,
                    indicesView = indicesView,
                    indicesOffset = indices.get("byteOffset")?.asInt() ?: 0,
                    indicesComponentType = indices.get("componentType")?.asInt() ?: COMP_UNSIGNED_INT,
                    valuesView = valuesView,
                    valuesOffset = values.get("byteOffset")?.asInt() ?: 0,
                )
            }
            Accessor(
                bufferView = a.get("bufferView")?.asInt(),
                byteOffset = a.get("byteOffset")?.asInt() ?: 0,
                componentType = componentType,
                normalized = a.get("normalized")?.asBool() ?: false,
                count = count,
                type = type,
                sparse = sparse,
            )
        } ?: emptyList()

    private fun parseMeshes(root: JsonValue): List<GltfMesh> =
        root.get("meshes")?.asList()?.mapNotNull { m ->
            val prims = m.get("primitives")?.asList()?.mapNotNull { p ->
                val attrs = p.get("attributes") ?: return@mapNotNull null
                val posAcc = attrs.get("POSITION")?.asInt() ?: return@mapNotNull null
                val idxAcc = p.get("indices")?.asInt()
                val mode = p.get("mode")?.asInt() ?: MODE_TRIANGLES
                val draco = p.get("extensions").get(EXT_DRACO)?.let { ext ->
                    val view = ext.get("bufferView")?.asInt()
                        ?: throw GltfParseException("Draco primitive has no bufferView")
                    val positionId = ext.get("attributes").get("POSITION")?.asInt()
                        ?: throw GltfParseException("Draco primitive has no POSITION attribute")
                    DracoPrimitive(view, positionId)
                }
                Primitive(posAcc, idxAcc, mode, draco)
            } ?: emptyList()
            GltfMesh(prims)
        } ?: emptyList()

    private fun parseNodes(root: JsonValue): List<Node> =
        root.get("nodes")?.asList()?.map { n ->
            val mesh = n.get("mesh")?.asInt()
            val children = n.get("children")?.asIntArray() ?: IntArray(0)
            val matrix = n.get("matrix")?.asFloatArray()?.takeIf { it.size == 16 }
            val translation = n.get("translation")?.asFloatArray()?.takeIf { it.size == 3 }
            val rotation = n.get("rotation")?.asFloatArray()?.takeIf { it.size == 4 }
            val scale = n.get("scale")?.asFloatArray()?.takeIf { it.size == 3 }
            Node(mesh, children, matrix, translation, rotation, scale)
        } ?: emptyList()

    private fun parseScenes(root: JsonValue): List<Scene> =
        root.get("scenes")?.asList()?.mapNotNull { s ->
            val nodes = s.get("nodes")?.asIntArray() ?: IntArray(0)
            Scene(nodes)
        } ?: emptyList()

    private fun resolveSceneRoots(root: JsonValue, scenes: List<Scene>, nodes: List<Node>): IntArray {
        val sceneIdx = root.get("scene")?.asInt() ?: 0
        if (scenes.isNotEmpty() && sceneIdx in scenes.indices) {
            return scenes[sceneIdx].nodes
        }
        // Fallback for files without a declared scene: render nodes that are not children of another node.
        val children = HashSet<Int>()
        nodes.forEach { it.children.forEach { c -> children.add(c) } }
        return nodes.indices.filter { it !in children }.toIntArray()
    }

    // --- Scene-graph rendering -------------------------------------------------

    private fun renderNode(
        nodeIdx: Int,
        parentMatrix: FloatArray,
        nodes: List<Node>,
        meshes: List<GltfMesh>,
        accessors: List<Accessor>,
        bufferViews: Views,
        builder: MeshBuilder,
        visited: MutableSet<Int>,
    ) {
        if (!visited.add(nodeIdx)) return
        val node = nodes.getOrNull(nodeIdx) ?: return
        val local = node.matrix ?: trsMatrix(node.translation, node.rotation, node.scale)
        val world = multiplyMatrices(parentMatrix, local)
        node.mesh?.let { meshIdx ->
            val mesh = meshes.getOrNull(meshIdx) ?: return@let
            mesh.primitives.forEach { primitive ->
                renderPrimitive(primitive, world, accessors, bufferViews, builder)
            }
        }
        node.children.forEach { child ->
            renderNode(child, world, nodes, meshes, accessors, bufferViews, builder, visited)
        }
    }

    private fun renderPrimitive(
        primitive: Primitive,
        world: FloatArray,
        accessors: List<Accessor>,
        bufferViews: Views,
        builder: MeshBuilder,
    ) {
        if (primitive.mode != MODE_TRIANGLES && primitive.mode != MODE_TRIANGLE_STRIP &&
            primitive.mode != MODE_TRIANGLE_FAN
        ) {
            return // skip points/lines
        }

        val positions: FloatArray
        val indices: IntArray
        if (primitive.draco != null) {
            val decoded = decodeDraco(primitive.draco, bufferViews)
            positions = decoded.first
            indices = decoded.second
        } else {
            val positionsAccessor = accessors.getOrNull(primitive.positionsAccessor)
                ?: throw GltfParseException("Invalid POSITION accessor ${primitive.positionsAccessor}")
            if (positionsAccessor.type != "VEC3") {
                throw GltfParseException("POSITION accessor must be VEC3, found '${positionsAccessor.type}'")
            }
            positions = readAccessor(positionsAccessor, 3, bufferViews)
            indices = if (primitive.indicesAccessor != null) {
                val idxAccessor = accessors.getOrNull(primitive.indicesAccessor)
                    ?: throw GltfParseException("Invalid indices accessor ${primitive.indicesAccessor}")
                readIndices(idxAccessor, bufferViews)
            } else {
                IntArray(positionsAccessor.count) { it }
            }
        }

        // Transform positions once per primitive.
        val transformed = FloatArray(positions.size)
        for (i in 0 until positions.size step 3) {
            val x = positions[i]; val y = positions[i + 1]; val z = positions[i + 2]
            transformed[i] = world[0] * x + world[4] * y + world[8] * z + world[12]
            transformed[i + 1] = world[1] * x + world[5] * y + world[9] * z + world[13]
            transformed[i + 2] = world[2] * x + world[6] * y + world[10] * z + world[14]
        }

        val tri = FloatArray(9)
        when (primitive.mode) {
            MODE_TRIANGLES -> {
                var i = 0
                while (i + 2 < indices.size) {
                    emitTriangle(indices[i], indices[i + 1], indices[i + 2], transformed, builder, tri)
                    i += 3
                }
            }
            MODE_TRIANGLE_STRIP -> {
                var i = 0
                while (i + 2 < indices.size) {
                    if (i % 2 == 0) emitTriangle(indices[i], indices[i + 1], indices[i + 2], transformed, builder, tri)
                    else emitTriangle(indices[i], indices[i + 2], indices[i + 1], transformed, builder, tri)
                    i += 1
                }
            }
            MODE_TRIANGLE_FAN -> {
                var i = 1
                while (i + 1 < indices.size) {
                    emitTriangle(indices[0], indices[i], indices[i + 1], transformed, builder, tri)
                    i += 1
                }
            }
        }
    }

    private fun emitTriangle(i0: Int, i1: Int, i2: Int, positions: FloatArray, builder: MeshBuilder, tri: FloatArray) {
        val count = positions.size / 3
        if (i0 < 0 || i0 >= count || i1 < 0 || i1 >= count || i2 < 0 || i2 >= count) {
            throw GltfParseException("Triangle index out of bounds in glTF primitive")
        }
        tri[0] = positions[i0 * 3]; tri[1] = positions[i0 * 3 + 1]; tri[2] = positions[i0 * 3 + 2]
        tri[3] = positions[i1 * 3]; tri[4] = positions[i1 * 3 + 1]; tri[5] = positions[i1 * 3 + 2]
        tri[6] = positions[i2 * 3]; tri[7] = positions[i2 * 3 + 1]; tri[8] = positions[i2 * 3 + 2]
        builder.addTriangle(tri)
    }

    // --- Draco ------------------------------------------------------------------

    /** Returns (positions xyz per point, triangle point indices) for a KHR_draco_mesh_compression primitive. */
    private fun decodeDraco(
        draco: DracoPrimitive,
        bufferViews: Views,
    ): Pair<FloatArray, IntArray> {
        val view = bufferViews[draco.bufferView]
        if (view.offset < 0 || view.offset + view.length > view.data.size) {
            throw GltfParseException("Draco bufferView exceeds buffer bounds")
        }
        val bytes = view.data.copyOfRange(view.offset, view.offset + view.length)

        val mesh = try {
            Draco.decode(bytes)
        } catch (e: Exception) {
            throw GltfParseException("Could not decode Draco-compressed mesh: ${e.message}", e)
        } as? DracoMesh ?: throw GltfParseException("Draco data is a point cloud, not a mesh")

        val attribute = (0 until mesh.numAttributes).map { mesh.attribute(it) }
            .firstOrNull { it.uniqueId.toInt() == draco.positionId }
            ?: throw GltfParseException("Draco mesh has no attribute with id ${draco.positionId}")
        if (attribute.componentsCount < 3) throw GltfParseException("Draco POSITION has fewer than 3 components")

        val points = mesh.numPoints
        val positions = FloatArray(points * 3)
        val value = FloatArray(attribute.componentsCount)
        for (p in 0 until points) {
            attribute.getValue(attribute.mappedIndex(p), value)
            positions[p * 3] = value[0]; positions[p * 3 + 1] = value[1]; positions[p * 3 + 2] = value[2]
        }

        val faces = mesh.numFaces
        val indices = IntArray(faces * 3)
        val face = IntArray(3)
        for (f in 0 until faces) {
            mesh.readFace(f, face)
            indices[f * 3] = face[0]; indices[f * 3 + 1] = face[1]; indices[f * 3 + 2] = face[2]
        }
        return positions to indices
    }

    // --- Accessor data reading -------------------------------------------------

    private fun componentSize(componentType: Int): Int = when (componentType) {
        COMP_BYTE, COMP_UNSIGNED_BYTE -> 1
        COMP_SHORT, COMP_UNSIGNED_SHORT -> 2
        COMP_UNSIGNED_INT, COMP_FLOAT -> 4
        else -> throw GltfParseException("Unsupported accessor component type $componentType")
    }

    /** Reads one component as a float, applying glTF's normalized-integer rules when [normalized]. */
    private fun readComponent(bb: ByteBuffer, offset: Int, componentType: Int, normalized: Boolean): Float =
        when (componentType) {
            COMP_FLOAT -> bb.getFloat(offset)
            COMP_BYTE -> bb.get(offset).toFloat().let { if (normalized) maxOf(it / 127f, -1f) else it }
            COMP_UNSIGNED_BYTE -> (bb.get(offset).toInt() and 0xFF).toFloat().let { if (normalized) it / 255f else it }
            COMP_SHORT -> bb.getShort(offset).toFloat().let { if (normalized) maxOf(it / 32767f, -1f) else it }
            COMP_UNSIGNED_SHORT -> (bb.getShort(offset).toInt() and 0xFFFF).toFloat().let { if (normalized) it / 65535f else it }
            COMP_UNSIGNED_INT -> (bb.getInt(offset).toLong() and 0xFFFFFFFFL).toFloat()
            else -> throw GltfParseException("Unsupported accessor component type $componentType")
        }

    /** Reads a vertex accessor (dense data, optional sparse substitution) into [components] floats per element. */
    private fun readAccessor(accessor: Accessor, components: Int, bufferViews: Views): FloatArray {
        val size = componentSize(accessor.componentType)
        val out = FloatArray(accessor.count * components)

        if (accessor.bufferView != null) {
            val view = bufferViews[accessor.bufferView]
            val buffer = view.data
            val stride = view.stride ?: (size * components)
            val base = view.offset + accessor.byteOffset
            val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until accessor.count) {
                val off = base + i * stride
                if (off < 0 || off + size * components > buffer.size) {
                    throw GltfParseException("Accessor data exceeds buffer bounds")
                }
                for (c in 0 until components) {
                    out[i * components + c] = readComponent(bb, off + c * size, accessor.componentType, accessor.normalized)
                }
            }
        } // else: no bufferView means the base data is all zeros.

        accessor.sparse?.let { sparse ->
            val idxSize = componentSize(sparse.indicesComponentType)
            val idxView = bufferViews[sparse.indicesView]
            val valView = bufferViews[sparse.valuesView]
            val idxBuf = ByteBuffer.wrap(idxView.data).order(ByteOrder.LITTLE_ENDIAN)
            val valBuf = ByteBuffer.wrap(valView.data).order(ByteOrder.LITTLE_ENDIAN)
            val idxBase = idxView.offset + sparse.indicesOffset
            val valBase = valView.offset + sparse.valuesOffset
            for (s in 0 until sparse.count) {
                val io = idxBase + s * idxSize
                val vo = valBase + s * size * components
                if (io + idxSize > idxBuf.capacity() || vo + size * components > valBuf.capacity()) {
                    throw GltfParseException("Sparse accessor exceeds buffer bounds")
                }
                val target = readIndex(idxBuf, io, sparse.indicesComponentType)
                if (target < 0 || target >= accessor.count) throw GltfParseException("Sparse accessor index out of range")
                for (c in 0 until components) {
                    out[target * components + c] = readComponent(valBuf, vo + c * size, accessor.componentType, accessor.normalized)
                }
            }
        }
        return out
    }

    private fun readIndices(accessor: Accessor, bufferViews: Views): IntArray {
        if (accessor.type != "SCALAR") throw GltfParseException("Indices accessor must be SCALAR")
        if (accessor.componentType != COMP_UNSIGNED_BYTE && accessor.componentType != COMP_UNSIGNED_SHORT &&
            accessor.componentType != COMP_UNSIGNED_INT
        ) {
            throw GltfParseException("Unsupported index component type ${accessor.componentType}")
        }
        if (accessor.bufferView == null && accessor.sparse == null) {
            throw GltfParseException("Indices accessor has no data")
        }
        // Read as exact integers (floats would lose precision above 2^24 for 32-bit indices).
        val size = componentSize(accessor.componentType)
        val out = IntArray(accessor.count)
        if (accessor.bufferView != null) {
            val view = bufferViews[accessor.bufferView]
            val buffer = view.data
            val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
            val stride = view.stride ?: size
            val base = view.offset + accessor.byteOffset
            for (i in 0 until accessor.count) {
                val off = base + i * stride
                if (off < 0 || off + size > buffer.size) throw GltfParseException("Indices accessor exceeds buffer bounds")
                out[i] = readIndex(bb, off, accessor.componentType)
            }
        }
        accessor.sparse?.let { sparse ->
            val idxSize = componentSize(sparse.indicesComponentType)
            val idxView = bufferViews[sparse.indicesView]
            val valView = bufferViews[sparse.valuesView]
            val idxBuf = ByteBuffer.wrap(idxView.data).order(ByteOrder.LITTLE_ENDIAN)
            val valBuf = ByteBuffer.wrap(valView.data).order(ByteOrder.LITTLE_ENDIAN)
            for (s in 0 until sparse.count) {
                val io = idxView.offset + sparse.indicesOffset + s * idxSize
                val vo = valView.offset + sparse.valuesOffset + s * size
                if (io + idxSize > idxBuf.capacity() || vo + size > valBuf.capacity()) {
                    throw GltfParseException("Sparse accessor exceeds buffer bounds")
                }
                val target = readIndex(idxBuf, io, sparse.indicesComponentType)
                if (target < 0 || target >= accessor.count) throw GltfParseException("Sparse accessor index out of range")
                out[target] = readIndex(valBuf, vo, accessor.componentType)
            }
        }
        return out
    }

    private fun readIndex(bb: ByteBuffer, offset: Int, componentType: Int): Int = when (componentType) {
        COMP_UNSIGNED_BYTE -> bb.get(offset).toInt() and 0xFF
        COMP_UNSIGNED_SHORT -> bb.getShort(offset).toInt() and 0xFFFF
        COMP_UNSIGNED_INT -> bb.getInt(offset)
        else -> throw GltfParseException("Unsupported index component type $componentType")
    }

    // --- 4x4 matrix math (column-major, glTF convention) -----------------------

    private fun multiplyMatrices(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a[k * 4 + row] * b[col * 4 + k]
                out[col * 4 + row] = sum
            }
        }
        return out
    }

    private fun trsMatrix(translation: FloatArray?, rotation: FloatArray?, scale: FloatArray?): FloatArray {
        val t = translation ?: floatArrayOf(0f, 0f, 0f)
        val s = scale ?: floatArrayOf(1f, 1f, 1f)
        val m = FloatArray(16)
        if (rotation != null) {
            val x = rotation[0]; val y = rotation[1]; val z = rotation[2]; val w = rotation[3]
            val x2 = x + x; val y2 = y + y; val z2 = z + z
            val xx = x * x2; val xy = x * y2; val xz = x * z2
            val yy = y * y2; val yz = y * z2; val zz = z * z2
            val wx = w * x2; val wy = w * y2; val wz = w * z2
            m[0] = 1f - (yy + zz); m[4] = xy - wz; m[8] = xz + wy; m[12] = t[0]
            m[1] = xy + wz; m[5] = 1f - (xx + zz); m[9] = yz - wx; m[13] = t[1]
            m[2] = xz - wy; m[6] = yz + wx; m[10] = 1f - (xx + yy); m[14] = t[2]
            m[3] = 0f; m[7] = 0f; m[11] = 0f; m[15] = 1f
        } else {
            m[0] = 1f; m[1] = 0f; m[2] = 0f; m[3] = 0f
            m[4] = 0f; m[5] = 1f; m[6] = 0f; m[7] = 0f
            m[8] = 0f; m[9] = 0f; m[10] = 1f; m[11] = 0f
            m[12] = t[0]; m[13] = t[1]; m[14] = t[2]; m[15] = 1f
        }
        m[0] *= s[0]; m[1] *= s[0]; m[2] *= s[0]
        m[4] *= s[1]; m[5] *= s[1]; m[6] *= s[1]
        m[8] *= s[2]; m[9] *= s[2]; m[10] *= s[2]
        return m
    }

    // --- Low-level stream helpers ----------------------------------------------

    private fun readFully(input: InputStream, dst: ByteArray, offset: Int, length: Int): Int {
        var total = 0
        while (total < length) {
            val n = input.read(dst, offset + total, length - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}
