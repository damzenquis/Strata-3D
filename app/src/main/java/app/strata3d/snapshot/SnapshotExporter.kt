package app.strata3d.snapshot

import android.app.ActivityManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import app.strata3d.render.SnapshotException
import app.strata3d.render.SnapshotPixels
import java.io.File
import java.io.IOException

/**
 * Turns rendered pixels into PNG files and hands them to Android. Snapshots are written to a private cache
 * folder only: sharing grants the receiving app temporary read access through a content URI, and saving goes
 * through the system document picker, so no storage permission is ever needed.
 */
object SnapshotExporter {

    private const val DIR = "snapshots"

    /** Temporary snapshots older than this are deleted at the start of the next export or app launch. */
    private const val MAX_AGE_MS = 15 * 60 * 1000L

    /** Throws [SnapshotException] when the device does not have enough free memory for [plan]. */
    fun checkMemory(context: Context, plan: SnapshotPlan) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val needed = SnapshotPlanning.estimatedBytes(plan)
        if (info.lowMemory || needed > (info.availMem - info.threshold) * 0.6) {
            throw SnapshotException("Not enough free memory for a ${plan.outputWidth} × ${plan.outputHeight} image. Try a smaller size.")
        }
    }

    /** Converts read-back pixels to a bitmap (flipping them upright). The pixel buffer is consumed. */
    fun toBitmap(pixels: SnapshotPixels): Bitmap {
        SnapshotPlanning.flipRows(pixels.rgba, pixels.width, pixels.height)
        val bitmap = try {
            Bitmap.createBitmap(pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            throw SnapshotException("Not enough memory for a ${pixels.width} × ${pixels.height} image")
        }
        bitmap.copyPixelsFromBuffer(pixels.rgba) // GL RGBA bytes are premultiplied, as ARGB_8888 expects
        return bitmap
    }

    /**
     * Adds the caption band under [render]: file name, then size. Returns a new bitmap; [render] is recycled.
     */
    fun addCaption(
        render: Bitmap,
        captionHeight: Int,
        title: String,
        subtitle: String,
        backgroundArgb: Int,
        titleArgb: Int,
        subtitleArgb: Int,
        dividerArgb: Int,
    ): Bitmap {
        val out = try {
            Bitmap.createBitmap(render.width, render.height + captionHeight, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            render.recycle()
            throw SnapshotException("Not enough memory for a ${render.width} × ${render.height + captionHeight} image")
        }
        val canvas = Canvas(out)
        canvas.drawColor(backgroundArgb)
        canvas.drawBitmap(render, 0f, 0f, null)
        render.recycle()

        val top = render.height.toFloat()
        val pad = captionHeight * 0.32f
        canvas.drawRect(pad, top, out.width - pad, top + maxOf(1f, captionHeight * 0.012f), Paint().apply { color = dividerArgb })

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = titleArgb
            textSize = captionHeight * 0.27f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = subtitleArgb
            textSize = captionHeight * 0.21f
            typeface = Typeface.DEFAULT
        }
        val maxWidth = out.width - pad * 2
        val titleText = TextUtils.ellipsize(title, titlePaint, maxWidth, TextUtils.TruncateAt.MIDDLE).toString()
        val subtitleText = TextUtils.ellipsize(subtitle, subtitlePaint, maxWidth, TextUtils.TruncateAt.END).toString()
        canvas.drawText(titleText, pad, top + captionHeight * 0.47f, titlePaint)
        canvas.drawText(subtitleText, pad, top + captionHeight * 0.78f, subtitlePaint)
        return out
    }

    /** Encodes [bitmap] as PNG into the private snapshot cache and recycles it. */
    fun writePng(context: Context, bitmap: Bitmap, fileName: String): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val file = File(dir, fileName)
        try {
            file.outputStream().buffered().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("PNG encoding failed")
            }
        } catch (e: IOException) {
            file.delete()
            throw SnapshotException("The image could not be written: ${e.message}")
        } finally {
            bitmap.recycle()
        }
        return file
    }

    /** Deletes temporary snapshots that are old enough that no share target can still be reading them. */
    fun cleanUp(context: Context, maxAgeMs: Long = MAX_AGE_MS) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        File(context.cacheDir, DIR).listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    /** A chooser that shares [file] through a content URI with temporary read permission only. */
    fun shareIntent(context: Context, file: File, chooserTitle: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, chooserTitle).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** Copies a cached snapshot to a document the user created, then deletes the cached copy. */
    fun saveTo(context: Context, file: File, destination: Uri) {
        try {
            val out = context.contentResolver.openOutputStream(destination) ?: throw IOException("The destination could not be opened")
            out.use { stream -> file.inputStream().use { it.copyTo(stream) } }
        } catch (e: IOException) {
            throw SnapshotException("The image could not be saved: ${e.message}")
        } catch (e: SecurityException) {
            throw SnapshotException("Permission denied while saving the image")
        } finally {
            file.delete()
        }
    }
}
