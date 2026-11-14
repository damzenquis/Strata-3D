package app.strata3d.recent

import app.strata3d.model.JsonParser
import app.strata3d.model.LengthUnit
import app.strata3d.model.JsonValue
import app.strata3d.model.asBool
import app.strata3d.model.asInt
import app.strata3d.model.asList
import app.strata3d.model.asLong
import app.strata3d.model.asString
import app.strata3d.model.get

/** A file opened together with the model, such as a glTF's `.bin` (kept so the model can be reopened whole). */
data class CompanionFile(val name: String, val uri: String)

/**
 * One entry in the Recent Files history. These records describe *opened files* only and are deliberately
 * separate from any future project / scan data structures.
 *
 * The JSON schema is versioned (see [RecentFilesCodec]); new optional fields (for example pinned, collections,
 * notes) can be added in a later schema version without breaking older files.
 */
data class RecentFile(
    val id: String,
    val displayName: String,
    val uri: String,
    val companions: List<CompanionFile> = emptyList(),
    /** Upper-case file type shown to the user, e.g. "STL", "GLB", "ZIP". */
    val format: String,
    /** Size in bytes, or -1 when unknown. */
    val sizeBytes: Long,
    val triangleCount: Int,
    /** Epoch milliseconds of the last successful open. */
    val openedAt: Long,
    /** True only when Android granted persistable read access for every URI of this entry. */
    val persisted: Boolean,
    /** Thumbnail file name in the private thumbnail folder, or null when none has been made. */
    val thumbnail: String? = null,
    /** Units the user chose for this file's coordinates in the measurement card, or null (file / assumed). */
    val unit: LengthUnit? = null,
) {
    val allUris: List<String> get() = listOf(uri) + companions.map { it.uri }
}

/** Pure list rules for the history (unit-tested). */
object RecentFiles {

    /** The history keeps at most this many entries. */
    const val MAX_ENTRIES = 15

    /**
     * Puts [entry] first, replacing an older entry for the same document and the entry being reopened
     * ([replacingId]), and trims the list to [max]. Returns the new list and the entries that were dropped
     * (their permissions and thumbnails can then be released).
     */
    fun add(
        current: List<RecentFile>,
        entry: RecentFile,
        replacingId: String? = null,
        max: Int = MAX_ENTRIES,
    ): Pair<List<RecentFile>, List<RecentFile>> {
        val (replaced, others) = current.partition { it.uri == entry.uri || it.id == replacingId || it.id == entry.id }
        val combined = listOf(entry) + others
        val kept = combined.take(max)
        val dropped = replaced.filter { it.id != entry.id } + combined.drop(max)
        return kept to dropped
    }

    /** URIs of [dropped] entries that no remaining entry still uses (safe to release). */
    fun releasableUris(dropped: List<RecentFile>, remaining: List<RecentFile>): Set<String> {
        val inUse = remaining.flatMap { it.allUris }.toSet()
        return dropped.flatMap { it.allUris }.filterNot { it in inUse }.toSet()
    }
}

/**
 * Versioned JSON encoding of the history.
 *
 * ```
 * { "version": 1, "entries": [ { "id", "name", "uri", "companions": [{ "name", "uri" }], "format",
 *   "size", "triangles", "openedAt", "persisted", "thumbnail", "unit" } ] }
 * ("thumbnail" and "unit" are optional; readers ignore keys they do not know, so adding optional keys keeps
 * version 1.)
 * ```
 */
object RecentFilesCodec {

    const val VERSION = 1

    enum class Status { OK, EMPTY, CORRUPT, NEWER_VERSION }

    class Decoded(val entries: List<RecentFile>, val status: Status)

    fun encode(entries: List<RecentFile>): String = buildString {
        append("{\"version\":").append(VERSION).append(",\"entries\":[")
        entries.forEachIndexed { i, e ->
            if (i > 0) append(',')
            append('{')
            field("id", e.id); append(',')
            field("name", e.displayName); append(',')
            field("uri", e.uri); append(',')
            append("\"companions\":[")
            e.companions.forEachIndexed { j, c ->
                if (j > 0) append(',')
                append('{'); field("name", c.name); append(','); field("uri", c.uri); append('}')
            }
            append("],")
            field("format", e.format); append(',')
            append("\"size\":").append(e.sizeBytes).append(',')
            append("\"triangles\":").append(e.triangleCount).append(',')
            append("\"openedAt\":").append(e.openedAt).append(',')
            append("\"persisted\":").append(e.persisted)
            if (e.thumbnail != null) { append(','); field("thumbnail", e.thumbnail) }
            if (e.unit != null) { append(','); field("unit", e.unit.name) }
            append('}')
        }
        append("]}")
    }

    /** Never throws: unreadable data yields an empty list with a status the caller can act on. */
    fun decode(text: String?): Decoded {
        if (text.isNullOrBlank()) return Decoded(emptyList(), Status.EMPTY)
        val root: JsonValue = try {
            JsonParser.parse(text)
        } catch (e: Exception) {
            return Decoded(emptyList(), Status.CORRUPT)
        }
        val version = root.get("version").asInt() ?: return Decoded(emptyList(), Status.CORRUPT)
        if (version > VERSION) return Decoded(emptyList(), Status.NEWER_VERSION)
        val list = root.get("entries").asList() ?: return Decoded(emptyList(), Status.CORRUPT)
        val entries = list.mapNotNull { e ->
            val id = e.get("id").asString() ?: return@mapNotNull null
            val uri = e.get("uri").asString() ?: return@mapNotNull null
            RecentFile(
                id = id,
                displayName = e.get("name").asString() ?: "model",
                uri = uri,
                companions = e.get("companions").asList().orEmpty().mapNotNull { c ->
                    val cu = c.get("uri").asString() ?: return@mapNotNull null
                    CompanionFile(c.get("name").asString() ?: "", cu)
                },
                format = e.get("format").asString() ?: "",
                sizeBytes = e.get("size").asLong() ?: -1L,
                triangleCount = e.get("triangles").asInt() ?: 0,
                openedAt = e.get("openedAt").asLong() ?: 0L,
                persisted = e.get("persisted").asBool() ?: false,
                thumbnail = e.get("thumbnail").asString(),
                unit = LengthUnit.fromName(e.get("unit").asString()),
            )
        }
        return Decoded(entries.take(RecentFiles.MAX_ENTRIES), Status.OK)
    }

    private fun StringBuilder.field(name: String, value: String) {
        append('"').append(name).append("\":")
        string(value)
    }

    private fun StringBuilder.string(value: String) {
        append('"')
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append(String.format("\\u%04x", c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
