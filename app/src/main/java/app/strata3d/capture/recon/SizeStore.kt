package app.strata3d.capture.recon

import app.strata3d.capture.recon.ModelSize.Record
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A capture's real-world size on disk: `<capture>/size.json` (plain java.io, unit-testable). The request stays with
 * the capture, so every new build of it is sized the same way.
 */
object SizeStore {
    const val FILE = "size.json"

    fun read(captureDir: File): Record? = try {
        File(captureDir, FILE).takeIf { it.isFile }?.readText()?.let { Record.decode(it) }
    } catch (e: Exception) { null }

    fun write(captureDir: File, record: Record) = atomicWrite(File(captureDir, FILE), record.encode() + "\n")

    /**
     * Scales [obj] so the requested dimension matches, marks it as millimetres, and stores the resulting dimensions.
     * Returns the updated record, or null when the capture has no size request. The earlier dimensions are cleared
     * first, so a failure never leaves numbers that don't match the model.
     */
    fun apply(captureDir: File, obj: File): Record? {
        val record = read(captureDir) ?: return null
        write(captureDir, record.copy(dimensionsMm = null))
        val (xyz, _) = obj.bufferedReader().use { ModelSize.readVertices(it) }
        val dims = ModelSize.measure(xyz)
        val f = ModelSize.factor(dims, record.request)
        val tmp = File(obj.parentFile, obj.name + ".tmp")
        tmp.bufferedWriter().use { w -> obj.bufferedReader().use { r -> ModelSize.scaleObj(r, w, f) } }
        Files.move(tmp.toPath(), obj.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return record.copy(dimensionsMm = dims.times(f)).also { write(captureDir, it) }
    }

    private fun atomicWrite(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
