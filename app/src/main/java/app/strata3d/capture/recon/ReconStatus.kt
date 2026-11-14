package app.strata3d.capture.recon

import app.strata3d.capture.format.CaptureFormatException
import app.strata3d.capture.format.JsonOut
import app.strata3d.model.JsonParser
import app.strata3d.model.asInt
import app.strata3d.model.asLong
import app.strata3d.model.asString
import app.strata3d.model.get

/**
 * Progress of one reconstruction, written atomically by the reconstruction process to
 * `files/recon/<captureId>/status.json` and read by the app's screens. Plain data, unit-tested.
 */
data class ReconStatus(
    val captureId: String,
    val state: State,
    val stage: ReconPlan.Stage? = null,
    /** Within the depth stage: batch number (1-based) and count. */
    val step: Int = 0,
    val steps: Int = 0,
    val startedAt: Long = 0,
    val updatedAt: Long = 0,
    /** How SfM ran and why (for the user and the device report). */
    val sfmSetting: String? = null,
    /** objbox's method line, e.g. "v2 (gravity, on surface)". */
    val boxMethod: String? = null,
    val error: Error? = null,
    /** Technical detail for an error (tool, exit code); shown under the plain message. */
    val detail: String? = null,
    /** Path of the finished model (OBJ), relative to the capture folder. */
    val model: String? = null,
    /** Photos in the capture, and how many SfM placed (known after the cameras stage). */
    val photos: Int = 0,
    val placed: Int = 0,
    /** Vertices of the finished model. */
    val vertices: Int = 0,
) {
    enum class State(val id: String) {
        RUNNING("running"), DONE("done"), FAILED("failed"), CANCELLED("cancelled"),
        /** Paused after the object box, waiting for the user to check it (nothing runs meanwhile). */
        REVIEW("review");
        companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } }
    }

    /** Why a reconstruction stopped. Each has a plain message in the UI. */
    enum class Error(val id: String) {
        UNSUPPORTED_DEVICE("unsupported_device"),
        LOW_STORAGE("low_storage"),
        LOW_MEMORY("low_memory"),
        TOO_FEW_PHOTOS_PLACED("too_few_photos_placed"),
        NO_OBJECT("no_object"),
        TAP_OUTSIDE("tap_outside"),
        TOOL_FAILED("tool_failed"),
        INTERRUPTED("interrupted");
        companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } }
    }

    fun encode(): String = JsonOut().obj {
        field("captureId", captureId)
        field("state", state.id)
        stage?.let { field("stage", it.id) }
        if (steps > 0) { field("step", step); field("steps", steps) }
        field("startedAt", startedAt)
        field("updatedAt", updatedAt)
        sfmSetting?.let { field("sfmSetting", it) }
        boxMethod?.let { field("boxMethod", it) }
        error?.let { field("error", it.id) }
        detail?.let { field("detail", it) }
        model?.let { field("model", it) }
        if (photos > 0) field("photos", photos)
        if (placed > 0) field("placed", placed)
        if (vertices > 0) field("vertices", vertices)
    }.toString()

    companion object {
        fun decode(text: String): ReconStatus {
            val o = try { JsonParser.parse(text) } catch (e: Exception) { throw CaptureFormatException("Malformed status") }
            return ReconStatus(
                captureId = o.get("captureId").asString() ?: throw CaptureFormatException("Missing captureId"),
                state = State.of(o.get("state").asString()) ?: throw CaptureFormatException("Bad state"),
                stage = ReconPlan.Stage.of(o.get("stage").asString()),
                step = o.get("step").asInt() ?: 0,
                steps = o.get("steps").asInt() ?: 0,
                startedAt = o.get("startedAt").asLong() ?: 0,
                updatedAt = o.get("updatedAt").asLong() ?: 0,
                sfmSetting = o.get("sfmSetting").asString(),
                boxMethod = o.get("boxMethod").asString(),
                error = Error.of(o.get("error").asString()),
                detail = o.get("detail").asString(),
                model = o.get("model").asString(),
                photos = o.get("photos").asInt() ?: 0,
                placed = o.get("placed").asInt() ?: 0,
                vertices = o.get("vertices").asInt() ?: 0,
            )
        }
    }
}
