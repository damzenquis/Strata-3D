package app.strata3d.capture.format

import app.strata3d.model.JsonParser
import app.strata3d.model.JsonValue
import app.strata3d.model.asDouble
import app.strata3d.model.asInt
import app.strata3d.model.asList
import app.strata3d.model.asLong
import app.strata3d.model.asBool
import app.strata3d.model.asString
import app.strata3d.model.get

/** Minimal JSON writer: compact output; non-finite numbers are written as null. */
internal class JsonOut {
    private val sb = StringBuilder(256)
    /** For each open object/array: true while nothing has been written into it yet. */
    private val first = ArrayDeque<Boolean>()
    private var afterKey = false

    private fun beginValue() {
        if (afterKey) { afterKey = false; return }
        if (first.isNotEmpty()) {
            if (!first.last()) sb.append(',')
            first[first.lastIndex] = false
        }
    }

    fun obj(block: JsonOut.() -> Unit): JsonOut = apply {
        beginValue(); sb.append('{'); first.addLast(true); block(); first.removeLast(); sb.append('}')
    }

    fun arr(block: JsonOut.() -> Unit): JsonOut = apply {
        beginValue(); sb.append('['); first.addLast(true); block(); first.removeLast(); sb.append(']')
    }

    /** Starts an object member; the next value, object or array written becomes its value. */
    fun key(name: String): JsonOut = apply { beginValue(); string(name); sb.append(':'); afterKey = true }

    fun value(s: String?): JsonOut = apply { beginValue(); if (s == null) sb.append("null") else string(s) }
    fun value(n: Long): JsonOut = apply { beginValue(); sb.append(n) }
    fun value(n: Int): JsonOut = apply { beginValue(); sb.append(n) }
    fun value(b: Boolean): JsonOut = apply { beginValue(); sb.append(b) }
    fun value(d: Double): JsonOut = apply { beginValue(); sb.append(if (d.isFinite()) fmt(d) else "null") }

    fun field(name: String, s: String?) = key(name).value(s)
    fun field(name: String, n: Long) = key(name).value(n)
    fun field(name: String, n: Int) = key(name).value(n)
    fun field(name: String, b: Boolean) = key(name).value(b)
    fun field(name: String, d: Double) = key(name).value(d)

    private fun string(s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u").append(String.format(java.util.Locale.ROOT, "%04x", c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }

    override fun toString() = sb.toString()

    companion object {
        /** Shortest round-tripping decimal, never in a locale-specific format. */
        fun fmt(d: Double): String {
            if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) return d.toLong().toString()
            return d.toString()
        }
    }
}

/** Thrown when a manifest or frame line cannot be decoded. */
class CaptureFormatException(message: String) : Exception(message)

/** Encodes and decodes [CaptureManifest] and [FrameRecord] as JSON (format versions 1 and 2). */
object CaptureJson {

    fun encodeManifest(m: CaptureManifest): String = JsonOut().obj {
        field("format", CaptureManifest.FORMAT)
        field("version", m.version)
        field("id", m.id)
        field("name", m.name)
        field("createdAt", m.createdAt)
        field("updatedAt", m.updatedAt)
        field("appVersion", m.appVersion)
        key("device").obj {
            field("manufacturer", m.device.manufacturer); field("model", m.device.model)
            field("soc", m.device.soc); field("sdkInt", m.device.sdkInt)
        }
        key("camera").obj {
            field("cameraId", m.camera.cameraId)
            field("imageWidth", m.camera.imageWidth); field("imageHeight", m.camera.imageHeight)
            m.camera.focalLengthMm?.let { field("focalLengthMm", it) }
            m.camera.sensorWidthMm?.let { field("sensorWidthMm", it) }
            m.camera.sensorHeightMm?.let { field("sensorHeightMm", it) }
            field("intrinsicsSource", m.camera.intrinsicsSource.id)
        }
        if (m.version < 2) {
            field("poseConvention", "camera_to_object: object frame = the target anchor (origin at the surface base or estimated point, +y up), metres; camera +x right +y up -z forward")
            field("arcoreVersion", m.arcoreVersion)
        } else {
            field("orientationConvention", "device_to_world quaternion [x,y,z,w] from the game rotation vector; world +z up; device +x right +y up +z out of the screen; back camera looks along -z")
        }
        m.objectTap?.let { t ->
            key("objectTap").obj {
                field("u", t.u); field("v", t.v); field("timestampNs", t.timestampNs)
                t.orientation?.let { o -> key("orientation").arr { value(o.x); value(o.y); value(o.z); value(o.w) } }
            }
        }
        m.target?.let { t ->
            key("target").obj {
                key("center").arr { value(t.center.x); value(t.center.y); value(t.center.z) }
                field("radius", t.radius)
                t.base?.let { b -> key("base").arr { value(b.x); value(b.y); value(b.z) } }
                field("placement", t.placement.id)
            }
        }
        field("frameCount", m.frameCount)
        field("coverage", m.coverage)
        field("state", m.state.id)
    }.toString()

    fun decodeManifest(text: String): CaptureManifest {
        val root = parse(text)
        if (root.get("format").asString() != CaptureManifest.FORMAT) throw CaptureFormatException("Not a Strata:3D capture")
        val version = root.get("version").asInt() ?: throw CaptureFormatException("Missing version")
        if (version > CaptureManifest.FORMAT_VERSION) throw CaptureFormatException("Capture format $version is newer than this app")
        val device = root.get("device")
        val camera = root.get("camera")
        val target = root.get("target")?.let { t ->
            val c = t.get("center").asList()?.mapNotNull { it.asDouble() }
            val r = t.get("radius").asDouble()
            val b = t.get("base").asList()?.mapNotNull { it.asDouble() }?.takeIf { it.size == 3 }?.let { Vec3(it[0], it[1], it[2]) }
            if (c != null && c.size == 3 && r != null) {
                CaptureTarget(Vec3(c[0], c[1], c[2]), r, b, Placement.of(t.get("placement").asString()))
            } else null
        }
        return CaptureManifest(
            id = root.get("id").asString() ?: throw CaptureFormatException("Missing id"),
            name = root.get("name").asString() ?: "",
            createdAt = root.get("createdAt").asLong() ?: 0L,
            updatedAt = root.get("updatedAt").asLong() ?: 0L,
            appVersion = root.get("appVersion").asString() ?: "",
            device = DeviceInfo(
                manufacturer = device.get("manufacturer").asString() ?: "",
                model = device.get("model").asString() ?: "",
                soc = device.get("soc").asString(),
                sdkInt = device.get("sdkInt").asInt() ?: 0,
            ),
            camera = CameraInfo(
                cameraId = camera.get("cameraId").asString() ?: "",
                imageWidth = camera.get("imageWidth").asInt() ?: 0,
                imageHeight = camera.get("imageHeight").asInt() ?: 0,
                focalLengthMm = camera.get("focalLengthMm").asDouble(),
                sensorWidthMm = camera.get("sensorWidthMm").asDouble(),
                sensorHeightMm = camera.get("sensorHeightMm").asDouble(),
                intrinsicsSource = IntrinsicsSource.of(camera.get("intrinsicsSource").asString()),
            ),
            arcoreVersion = root.get("arcoreVersion").asString(),
            target = target,
            frameCount = root.get("frameCount").asInt() ?: 0,
            coverage = root.get("coverage").asDouble() ?: 0.0,
            state = CaptureState.of(root.get("state").asString()),
            version = version,
            objectTap = root.get("objectTap")?.let { t ->
                val u = t.get("u").asDouble(); val v = t.get("v").asDouble()
                if (u == null || v == null) null
                else ObjectTap(u, v, t.get("timestampNs").asLong() ?: 0L, orientationOf(t.get("orientation")))
            },
        )
    }

    private fun orientationOf(v: JsonValue?): SensorOrientation? =
        v.asList()?.mapNotNull { it.asDouble() }?.takeIf { it.size == 4 }?.let { SensorOrientation(it[0], it[1], it[2], it[3]) }

    /** One line of `frames.jsonl` (no trailing newline). */
    fun encodeFrame(f: FrameRecord): String = JsonOut().obj {
        field("index", f.index)
        field("image", f.image)
        field("timestampNs", f.timestampNs)
        f.pose?.let { p ->
            key("pose").obj {
                key("t").arr { value(p.tx); value(p.ty); value(p.tz) }
                key("q").arr { value(p.qx); value(p.qy); value(p.qz); value(p.qw) }
            }
        }
        key("intrinsics").obj {
            field("fx", f.intrinsics.fx); field("fy", f.intrinsics.fy)
            field("cx", f.intrinsics.cx); field("cy", f.intrinsics.cy)
            field("width", f.intrinsics.width); field("height", f.intrinsics.height)
        }
        f.tracking?.let { field("tracking", it.id) }
        f.orientation?.let { o -> key("orientation").arr { value(o.x); value(o.y); value(o.z); value(o.w) } }
        if (f.rotationDegrees != 0) field("rotationDegrees", f.rotationDegrees)
        f.exposureNs?.let { field("exposureNs", it) }
        f.iso?.let { field("iso", it) }
        f.focusDiopters?.let { field("focusDiopters", it) }
        field("sharpness", f.sharpness)
        if (f.manual) field("manual", true)
        f.depth?.let { d ->
            key("depth").obj {
                field("file", d.file)
                d.confidenceFile?.let { field("confidence", it) }
                field("width", d.width); field("height", d.height)
            }
        }
    }.toString()

    fun decodeFrame(line: String): FrameRecord {
        val o = parse(line)
        val i = o.get("intrinsics")
        val pose = o.get("pose")?.let { p ->
            val t = p.get("t").asList()?.mapNotNull { it.asDouble() }
            val q = p.get("q").asList()?.mapNotNull { it.asDouble() }
            if (t != null && q != null && t.size == 3 && q.size == 4) Pose(t[0], t[1], t[2], q[0], q[1], q[2], q[3]) else null
        }
        val depth = o.get("depth")?.let { d ->
            val file = d.get("file").asString() ?: return@let null
            DepthRef(file, d.get("confidence").asString(), d.get("width").asInt() ?: 0, d.get("height").asInt() ?: 0)
        }
        return FrameRecord(
            index = o.get("index").asInt() ?: throw CaptureFormatException("Missing index"),
            image = o.get("image").asString() ?: throw CaptureFormatException("Missing image"),
            timestampNs = o.get("timestampNs").asLong() ?: 0L,
            pose = pose,
            intrinsics = Intrinsics(
                fx = i.get("fx").asDouble() ?: throw CaptureFormatException("Missing intrinsics"),
                fy = i.get("fy").asDouble() ?: throw CaptureFormatException("Missing intrinsics"),
                cx = i.get("cx").asDouble() ?: throw CaptureFormatException("Missing intrinsics"),
                cy = i.get("cy").asDouble() ?: throw CaptureFormatException("Missing intrinsics"),
                width = i.get("width").asInt() ?: 0,
                height = i.get("height").asInt() ?: 0,
            ),
            tracking = o.get("tracking").asString()?.let { Tracking.of(it) },
            exposureNs = o.get("exposureNs").asLong(),
            iso = o.get("iso").asInt(),
            focusDiopters = o.get("focusDiopters").asDouble(),
            sharpness = o.get("sharpness").asDouble() ?: 0.0,
            manual = o.get("manual").asBool() ?: false,
            depth = depth,
            orientation = orientationOf(o.get("orientation")),
            rotationDegrees = (o.get("rotationDegrees").asInt() ?: 0).let { if (it in setOf(0, 90, 180, 270)) it else 0 },
        )
    }

    private fun parse(text: String): JsonValue = try {
        JsonParser.parse(text)
    } catch (e: Exception) {
        throw CaptureFormatException("Malformed JSON: ${e.message}")
    }
}
