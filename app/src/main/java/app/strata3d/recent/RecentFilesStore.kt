package app.strata3d.recent

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import android.util.Log
import app.strata3d.model.LengthUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** Whether a recent entry can be opened directly or needs the user to select the file again. */
enum class RecentStatus { AVAILABLE, NEEDS_REOPEN }

/** A recent entry with its current availability. */
data class RecentEntry(val file: RecentFile, val status: RecentStatus)

/**
 * Persists the Recent Files history in a private, versioned JSON file written atomically, plus cached
 * thumbnails. It only ever touches its own private files: removing entries or clearing the history never
 * deletes or modifies the user's documents, it just releases this app's read permission for them.
 *
 * All methods run on [Dispatchers.IO] and are serialised by a mutex.
 */
class RecentFilesStore(context: Context) {

    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val dir = File(appContext.filesDir, "recent")
    private val thumbDir = File(dir, "thumbnails")
    private val file = AtomicFile(File(dir, "recent-files.json"))
    private val mutex = Mutex()

    private var entries: List<RecentFile>? = null

    /** When the stored file was written by a newer app version, it is left untouched (read-only mode). */
    private var readOnly = false

    suspend fun load(): List<RecentEntry> = withContext(Dispatchers.IO) {
        mutex.withLock { withStatus(ensureLoaded()) }
    }

    /**
     * Records a successfully opened model. Read permission is persisted only where Android actually grants
     * it (documents picked through the system picker); other URIs (for example from "Open with" or Share)
     * are recorded as needing to be reopened after a restart.
     */
    suspend fun record(
        displayName: String,
        uri: Uri,
        companions: List<Pair<String, Uri>>,
        sizeBytes: Long,
        triangleCount: Int,
        replacingId: String?,
    ): RecentFile = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = ensureLoaded()
            val all = listOf(uri) + companions.map { it.second }
            val persisted = all.map { takePersistable(it) }.all { it }
            val previous = current.firstOrNull { it.uri == uri.toString() }
            val entry = RecentFile(
                id = previous?.id ?: UUID.randomUUID().toString(),
                displayName = displayName,
                uri = uri.toString(),
                companions = companions.map { CompanionFile(it.first, it.second.toString()) },
                format = displayName.substringAfterLast('.', "").uppercase(),
                sizeBytes = sizeBytes,
                triangleCount = triangleCount,
                openedAt = System.currentTimeMillis(),
                persisted = persisted,
                thumbnail = previous?.thumbnail?.takeIf { File(thumbDir, it).isFile },
                unit = previous?.unit ?: current.firstOrNull { it.id == replacingId }?.unit,
            )
            val (kept, dropped) = RecentFiles.add(current, entry, replacingId)
            release(dropped, kept)
            save(kept)
            entry
        }
    }

    /** The units the user chose earlier for this document (matched by URI, or by the entry being reopened). */
    suspend fun unitFor(uri: Uri, replacingId: String?): LengthUnit? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = ensureLoaded()
            (current.firstOrNull { it.uri == uri.toString() } ?: current.firstOrNull { it.id == replacingId })?.unit
        }
    }

    /** Remembers (or with null, forgets) the units chosen for an entry's coordinates. */
    suspend fun setUnit(id: String, unit: LengthUnit?): List<RecentEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = ensureLoaded()
            if (current.none { it.id == id && it.unit != unit }) return@withLock withStatus(current)
            val updated = current.map { if (it.id == id) it.copy(unit = unit) else it }
            save(updated)
            withStatus(updated)
        }
    }

    /** Marks an entry as needing to be reopened (its document is gone or access was revoked). */
    suspend fun markUnavailable(id: String): List<RecentEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = ensureLoaded()
            val target = current.firstOrNull { it.id == id } ?: return@withLock withStatus(current)
            val updated = current.map { if (it.id == id) it.copy(persisted = false) else it }
            releaseUris(target.allUris.toSet() - updated.filter { it.id != id }.flatMap { it.allUris }.toSet())
            save(updated)
            withStatus(updated)
        }
    }

    suspend fun saveThumbnail(id: String, bitmap: Bitmap): List<RecentEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = ensureLoaded()
            if (current.none { it.id == id }) {
                bitmap.recycle()
                return@withLock withStatus(current)
            }
            thumbDir.mkdirs()
            val name = "$id.png"
            val target = File(thumbDir, name)
            val temp = File(thumbDir, "$name.tmp")
            try {
                val trimmed = trimToContent(bitmap)
                try {
                    temp.outputStream().buffered().use { trimmed.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally {
                    if (trimmed !== bitmap) trimmed.recycle()
                }
                // Atomic replace, so a reader never sees a half-written thumbnail.
                java.nio.file.Files.move(
                    temp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (e: IOException) {
                temp.delete()
                return@withLock withStatus(current)
            } finally {
                bitmap.recycle()
            }
            val updated = current.map { if (it.id == id) it.copy(thumbnail = name) else it }
            save(updated)
            withStatus(updated)
        }
    }

    /**
     * Crops a transparent render to the model's visible pixels plus a small margin, so thumbnails show the
     * model, not the empty viewport around it. Returns [bitmap] itself when there is nothing to trim.
     */
    private fun trimToContent(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val row = IntArray(w)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                if ((row[x] ushr 24) > 8) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    maxY = y
                }
            }
        }
        if (maxX < 0) return bitmap
        val pad = (maxOf(maxX - minX, maxY - minY) * 0.06f).toInt() + 1
        val left = (minX - pad).coerceAtLeast(0)
        val top = (minY - pad).coerceAtLeast(0)
        val right = (maxX + pad).coerceAtMost(w - 1)
        val bottom = (maxY + pad).coerceAtMost(h - 1)
        if (left == 0 && top == 0 && right == w - 1 && bottom == h - 1) return bitmap
        return Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
    }

    /** Decodes a cached thumbnail, or returns null when it is missing or damaged. Never opens the model. */
    suspend fun loadThumbnail(file: RecentFile): Bitmap? = withContext(Dispatchers.IO) {
        val name = file.thumbnail ?: return@withContext null
        try {
            BitmapFactory.decodeFile(File(thumbDir, name).path)
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    suspend fun remove(id: String): List<RecentEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = ensureLoaded()
            val (removed, kept) = current.partition { it.id == id }
            release(removed, kept)
            save(kept)
            withStatus(kept)
        }
    }

    /** Clears the history and its thumbnails. The user's original files are never touched. */
    suspend fun clear(): List<RecentEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = ensureLoaded()
            release(current, emptyList())
            save(emptyList())
            thumbDir.listFiles()?.forEach { it.delete() }
            emptyList()
        }
    }

    // --- internals ---------------------------------------------------------------------------------

    private fun ensureLoaded(): List<RecentFile> {
        entries?.let { return it }
        val text = try {
            if (file.baseFile.exists()) String(file.readFully(), Charsets.UTF_8) else null
        } catch (e: IOException) {
            null
        }
        val decoded = RecentFilesCodec.decode(text)
        when (decoded.status) {
            RecentFilesCodec.Status.CORRUPT -> {
                // Keep the damaged file for diagnosis and start a fresh history.
                Log.w(TAG, "Recent files history was unreadable; starting a new one")
                file.baseFile.renameTo(File(dir, "recent-files.corrupt.json"))
            }
            RecentFilesCodec.Status.NEWER_VERSION -> readOnly = true
            else -> Unit
        }
        val loaded = decoded.entries
        entries = loaded
        removeOrphanThumbnails(loaded)
        return loaded
    }

    private fun save(updated: List<RecentFile>) {
        entries = updated
        if (readOnly) return
        dir.mkdirs()
        val out = try {
            file.startWrite()
        } catch (e: IOException) {
            Log.w(TAG, "Could not write the recent files history", e)
            return
        }
        try {
            out.write(RecentFilesCodec.encode(updated).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            Log.w(TAG, "Could not write the recent files history", e)
        }
    }

    private fun withStatus(list: List<RecentFile>): List<RecentEntry> {
        val granted = try {
            resolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        } catch (e: SecurityException) {
            emptySet()
        }
        // Files that aren't models (recorded before the app refused them, e.g. an .xml) are not listed.
        return list.filter { app.strata3d.model.ModelParser.unsupportedExtension(it.displayName) == null }.map { f ->
            val available = f.persisted && f.allUris.all { it in granted }
            RecentEntry(f, if (available) RecentStatus.AVAILABLE else RecentStatus.NEEDS_REOPEN)
        }
    }

    private fun takePersistable(uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        return try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (e: SecurityException) {
            false // Android did not grant a persistable permission for this URI.
        }
    }

    private fun release(dropped: List<RecentFile>, remaining: List<RecentFile>) {
        releaseUris(RecentFiles.releasableUris(dropped, remaining))
        val keepThumbs = remaining.mapNotNull { it.thumbnail }.toSet()
        dropped.mapNotNull { it.thumbnail }.filterNot { it in keepThumbs }.forEach { File(thumbDir, it).delete() }
    }

    private fun releaseUris(uris: Set<String>) {
        val granted = try {
            resolver.persistedUriPermissions.map { it.uri.toString() }.toSet()
        } catch (e: SecurityException) {
            emptySet()
        }
        for (u in uris) {
            if (u !in granted) continue
            try {
                resolver.releasePersistableUriPermission(Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                // Already released or never held.
            }
        }
    }

    private fun removeOrphanThumbnails(list: List<RecentFile>) {
        // Also removes temporary files left behind if the app was killed mid-write.
        val referenced = list.mapNotNull { it.thumbnail }.toSet()
        thumbDir.listFiles()?.forEach { if (it.name !in referenced) it.delete() }
    }

    private companion object {
        const val TAG = "RecentFilesStore"
    }
}
