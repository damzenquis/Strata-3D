package app.strata3d.model

import java.io.InputStream

/**
 * Gives a parser access to the files that sit next to the model it is reading, such as a glTF's
 * separate `.bin` buffer. Implementations look the file up by the relative URI written in the model
 * (for example `"scene.bin"` or `"buffers/scene%20data.bin"`).
 */
fun interface ModelResources {
    /** Opens the resource, or returns null when it was not provided. */
    fun open(relativeUri: String): InputStream?

    companion object {
        /** Decodes a glTF/URI-style relative path: strips `./`, decodes `%XX` escapes, normalises slashes. */
        fun normalize(relativeUri: String): String {
            val raw = relativeUri.trim().replace('\\', '/').removePrefix("./")
            val bytes = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c == '%' && i + 2 < raw.length) {
                    val hex = raw.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        bytes.write(hex)
                        i += 3
                        continue
                    }
                }
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            return bytes.toString("UTF-8")
        }

        /** The last path segment of a (normalised) relative URI. */
        fun fileName(relativeUri: String): String = normalize(relativeUri).substringAfterLast('/')
    }
}
