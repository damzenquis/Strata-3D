package app.strata3d.capture.format

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A capture folder plus its manifest, as listed by [CaptureStore]. */
data class StoredCapture(val dir: File, val manifest: CaptureManifest, val sizeBytes: Long) {
    val id: String get() = manifest.id
}

/**
 * Captures on disk, under `<root>/<id>/`. Plain java.io so it is unit-testable; callers run it off the main
 * thread. Writes are crash-safe: the manifest is replaced atomically, and frames are appended one line at a time
 * to `frames.jsonl`, so a crash loses at most the photo being written.
 */
class CaptureStore(val root: File) {

    fun dirOf(id: String) = File(root, id)

    /** Creates a new capture folder with an id based on the creation time (unique within this store). */
    fun create(template: CaptureManifest): StoredCapture {
        root.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(template.createdAt))
        var id = stamp
        var n = 2
        while (dirOf(id).exists()) id = "$stamp-${n++}"
        val dir = dirOf(id)
        if (!File(dir, IMAGES).mkdirs()) throw IOException("Cannot create $dir")
        val manifest = template.copy(id = id)
        writeManifest(dir, manifest)
        return StoredCapture(dir, manifest, 0L)
    }

    /** Replaces the manifest atomically (write to a temporary file, then rename over the old one). */
    fun writeManifest(dir: File, manifest: CaptureManifest) {
        val target = File(dir, MANIFEST)
        val temp = File(dir, "$MANIFEST.tmp")
        FileOutputStream(temp).use { out ->
            out.write(CaptureJson.encodeManifest(manifest).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun readManifest(dir: File): CaptureManifest = CaptureJson.decodeManifest(File(dir, MANIFEST).readText(Charsets.UTF_8))

    /** Appends one frame line and syncs it to storage. */
    fun appendFrame(dir: File, frame: FrameRecord) {
        FileOutputStream(File(dir, FRAMES), true).use { out ->
            out.write((CaptureJson.encodeFrame(frame) + "\n").toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }

    /**
     * All readable frames. A damaged line (for example the last one, if the app died mid-write) is skipped, and so
     * is a frame whose image file is missing.
     */
    fun readFrames(dir: File): List<FrameRecord> {
        val f = File(dir, FRAMES)
        if (!f.isFile) return emptyList()
        return f.readLines(Charsets.UTF_8).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            try { CaptureJson.decodeFrame(line) } catch (e: CaptureFormatException) { null }
        }.filter { File(dir, it.image).isFile }
    }

    /**
     * Lists captures, newest first. A capture still marked [CaptureState.RECORDING] that is not [activeId] was cut
     * short (the app was killed): it is repaired to [CaptureState.INTERRUPTED] with its real frame count.
     * Folders without a readable manifest are skipped (never deleted).
     */
    fun list(activeId: String? = null): List<StoredCapture> {
        val dirs = root.listFiles { f -> f.isDirectory }.orEmpty()
        return dirs.mapNotNull { dir ->
            val manifest = try { readManifest(dir) } catch (e: Exception) { return@mapNotNull null }
            val repaired = if (manifest.state == CaptureState.RECORDING && manifest.id != activeId) {
                val fixed = manifest.copy(state = CaptureState.INTERRUPTED, frameCount = readFrames(dir).size)
                try { writeManifest(dir, fixed) } catch (e: IOException) { /* listing still works read-only */ }
                fixed
            } else manifest
            StoredCapture(dir, repaired, sizeOf(dir))
        }.sortedByDescending { it.manifest.createdAt }
    }

    fun rename(dir: File, name: String): CaptureManifest {
        val m = readManifest(dir).copy(name = name.trim(), updatedAt = System.currentTimeMillis())
        writeManifest(dir, m)
        return m
    }

    /** Deletes a capture folder. Only ever deletes inside [root]. */
    fun delete(dir: File): Boolean {
        val canonicalRoot = root.canonicalFile
        val target = dir.canonicalFile
        require(target.parentFile == canonicalRoot) { "Refusing to delete outside the capture store" }
        return target.deleteRecursively()
    }

    fun sizeOf(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        /** Folder name of the store inside the app's files directory. */
        const val DIR = "captures"
        const val MANIFEST = "manifest.json"
        const val FRAMES = "frames.jsonl"
        const val IMAGES = "images"
        const val DEPTH = "depth"

        fun imageName(index: Int) = "$IMAGES/%06d.jpg".format(Locale.ROOT, index)
        fun depthName(index: Int) = "$DEPTH/%06d.d16".format(Locale.ROOT, index)
        fun confidenceName(index: Int) = "$DEPTH/%06d.c8".format(Locale.ROOT, index)
    }
}
