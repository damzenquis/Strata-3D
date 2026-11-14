package app.strata3d.model

/**
 * Minimal JSON document model + recursive-descent parser used by [GltfParser].
 *
 * This is intentionally tiny and dependency-free (the `model` package must stay pure-JVM). It covers the
 * JSON subset glTF uses: objects, arrays, strings (with full escape handling including `\uXXXX` surrogate
 * pairs), numbers, booleans and null.
 */
sealed class JsonValue {
    object Null : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    data class Num(val raw: String) : JsonValue() {
        fun toInt(): Int = raw.toInt()
        fun toLong(): Long = raw.toLong()
        fun toDouble(): Double = raw.toDouble()
        fun toFloat(): Float = raw.toFloat()
    }
    data class Str(val value: String) : JsonValue()
    data class Arr(val items: List<JsonValue>) : JsonValue()
    data class Obj(val members: Map<String, JsonValue>) : JsonValue()
}

// Navigation helpers (null-safe on the receiver).
fun JsonValue?.get(name: String): JsonValue? = (this as? JsonValue.Obj)?.members?.get(name)
fun JsonValue?.at(index: Int): JsonValue? = (this as? JsonValue.Arr)?.items?.getOrNull(index)
fun JsonValue?.asString(): String? = (this as? JsonValue.Str)?.value
fun JsonValue?.asInt(): Int? = (this as? JsonValue.Num)?.toInt()
fun JsonValue?.asLong(): Long? = (this as? JsonValue.Num)?.toLong()
fun JsonValue?.asDouble(): Double? = (this as? JsonValue.Num)?.toDouble()
fun JsonValue?.asBool(): Boolean? = (this as? JsonValue.Bool)?.value
fun JsonValue?.asList(): List<JsonValue>? = (this as? JsonValue.Arr)?.items
fun JsonValue?.asIntArray(): IntArray? = (this as? JsonValue.Arr)?.items?.mapNotNull { it.asInt() }?.toIntArray()
fun JsonValue?.asFloatArray(): FloatArray? = (this as? JsonValue.Arr)?.items?.mapNotNull { it.asDouble()?.toFloat() }?.toFloatArray()

object JsonParser {

    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) throw GltfParseException("Unexpected trailing characters in JSON")
        return value
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun atEnd(): Boolean = pos >= text.length

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun parseValue(): JsonValue {
            skipWhitespace()
            if (pos >= text.length) throw GltfParseException("Unexpected end of JSON")
            return when (val c = text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.Str(parseString())
                't' -> { expect("true"); JsonValue.Bool(true) }
                'f' -> { expect("false"); JsonValue.Bool(false) }
                'n' -> { expect("null"); JsonValue.Null }
                else -> {
                    if (c == '-' || c in '0'..'9') parseNumber()
                    else throw GltfParseException("Unexpected character '$c' in JSON")
                }
            }
        }

        private fun parseObject(): JsonValue.Obj {
            pos++ // consume '{'
            skipWhitespace()
            val members = LinkedHashMap<String, JsonValue>()
            if (peek() == '}') { pos++; return JsonValue.Obj(members) }
            while (true) {
                skipWhitespace()
                if (pos >= text.length || text[pos] != '"') {
                    throw GltfParseException("Expected string key in JSON object")
                }
                val key = parseString()
                skipWhitespace()
                if (pos >= text.length || text[pos] != ':') {
                    throw GltfParseException("Expected ':' after JSON object key")
                }
                pos++ // consume ':'
                val value = parseValue()
                members[key] = value
                skipWhitespace()
                if (pos >= text.length) throw GltfParseException("Unterminated JSON object")
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return JsonValue.Obj(members) }
                    else -> throw GltfParseException("Expected ',' or '}' in JSON object")
                }
            }
        }

        private fun parseArray(): JsonValue.Arr {
            pos++ // consume '['
            skipWhitespace()
            val items = ArrayList<JsonValue>()
            if (peek() == ']') { pos++; return JsonValue.Arr(items) }
            while (true) {
                items.add(parseValue())
                skipWhitespace()
                if (pos >= text.length) throw GltfParseException("Unterminated JSON array")
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return JsonValue.Arr(items) }
                    else -> throw GltfParseException("Expected ',' or ']' in JSON array")
                }
            }
        }

        private fun parseString(): String {
            pos++ // consume opening '"'
            val sb = StringBuilder()
            while (pos < text.length) {
                val c = text[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= text.length) throw GltfParseException("Unterminated escape in JSON string")
                        when (val e = text[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val code = parseHex4()
                                if (Character.isHighSurrogate(code.toChar()) && pos + 1 < text.length &&
                                    text[pos] == '\\' && text[pos + 1] == 'u'
                                ) {
                                    val lowPos = pos
                                    pos += 2
                                    val low = parseHex4()
                                    if (Character.isLowSurrogate(low.toChar())) {
                                        sb.append(code.toChar()).append(low.toChar())
                                    } else {
                                        pos = lowPos
                                        sb.append(code.toChar())
                                    }
                                } else {
                                    sb.append(code.toChar())
                                }
                            }
                            else -> throw GltfParseException("Invalid escape '\\$e' in JSON string")
                        }
                    }
                    c < ' ' -> throw GltfParseException("Unescaped control character in JSON string")
                    else -> sb.append(c)
                }
            }
            throw GltfParseException("Unterminated JSON string")
        }

        private fun parseHex4(): Int {
            if (pos + 4 > text.length) throw GltfParseException("Invalid \\u escape in JSON string")
            var value = 0
            repeat(4) {
                val c = text[pos++]
                val digit = when (c) {
                    in '0'..'9' -> c - '0'
                    in 'a'..'f' -> c - 'a' + 10
                    in 'A'..'F' -> c - 'A' + 10
                    else -> throw GltfParseException("Invalid hex digit '$c' in \\u escape")
                }
                value = value * 16 + digit
            }
            return value
        }

        private fun parseNumber(): JsonValue.Num {
            val start = pos
            if (pos < text.length && text[pos] == '-') pos++
            while (pos < text.length && text[pos] in '0'..'9') pos++
            if (pos < text.length && text[pos] == '.') {
                pos++
                while (pos < text.length && text[pos] in '0'..'9') pos++
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                while (pos < text.length && text[pos] in '0'..'9') pos++
            }
            return JsonValue.Num(text.substring(start, pos))
        }

        private fun peek(): Char? = if (pos < text.length) text[pos] else null

        private fun expect(word: String) {
            if (pos + word.length > text.length || text.substring(pos, pos + word.length) != word) {
                throw GltfParseException("Invalid JSON literal")
            }
            pos += word.length
        }
    }
}
