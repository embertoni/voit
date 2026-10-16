package com.voit.domain

/**
 * A tiny, dependency-free JSON reader/writer.
 *
 * Voit is local-first and avoids pulling heavy serialization frameworks into the pure
 * domain core. This codec supports exactly the JSON subset the app serializes: objects,
 * arrays, strings, numbers, booleans and null. It performs correct string escaping so
 * arbitrary note/task text survives a serialize -> parse round-trip unchanged.
 *
 * It is intentionally small and total: parsing a value this codec produced always yields
 * an equal structure. It is NOT a hardened parser for untrusted third-party JSON.
 */
object Json {

    // ---- Model ------------------------------------------------------------------

    /** A parsed JSON value: String, Double, Boolean, null, List<JsonValue?>, or Map<String, JsonValue?>. */

    // ---- Writing ----------------------------------------------------------------

    fun write(value: Any?): String {
        val sb = StringBuilder()
        writeValue(sb, value)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int -> sb.append(value.toString())
            is Long -> sb.append(value.toString())
            is Double -> sb.append(formatNumber(value))
            is Map<*, *> -> writeObject(sb, value)
            is List<*> -> writeArray(sb, value)
            else -> error("Unsupported JSON value type: ${value::class}")
        }
    }

    private fun formatNumber(value: Double): String {
        // Preserve integral values without a trailing ".0" so timestamps stay compact
        // and round-trip into Long cleanly.
        return if (value % 1.0 == 0.0 && !value.isInfinite()) {
            value.toLong().toString()
        } else {
            value.toString()
        }
    }

    private fun writeObject(sb: StringBuilder, map: Map<*, *>) {
        sb.append('{')
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(',')
            first = false
            writeString(sb, k.toString())
            sb.append(':')
            writeValue(sb, v)
        }
        sb.append('}')
    }

    private fun writeArray(sb: StringBuilder, list: List<*>) {
        sb.append('[')
        for ((i, v) in list.withIndex()) {
            if (i > 0) sb.append(',')
            writeValue(sb, v)
        }
        sb.append(']')
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else ->
                    if (c < ' ') {
                        sb.append("\\u")
                        sb.append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(c)
                    }
            }
        }
        sb.append('"')
    }

    // ---- Reading ----------------------------------------------------------------

    fun parse(text: String): Any? = Parser(text).parseDocument()

    /** Parse a JSON document whose top level is an object. */
    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?>
            ?: error("Expected a JSON object")

    /** Parse a JSON document whose top level is an array. */
    @Suppress("UNCHECKED_CAST")
    fun parseArray(text: String): List<Any?> =
        parse(text) as? List<Any?>
            ?: error("Expected a JSON array")

    private class Parser(private val s: String) {
        private var pos = 0

        fun parseDocument(): Any? {
            skipWs()
            val v = parseValue()
            skipWs()
            require(pos >= s.length) { "Trailing content at $pos" }
            return v
        }

        private fun parseValue(): Any? {
            skipWs()
            if (pos >= s.length) error("Unexpected end of input")
            return when (val c = s[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else ->
                    if (c == '-' || c in '0'..'9') parseNumber()
                    else error("Unexpected character '$c' at $pos")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                val value = parseValue()
                map[key] = value
                skipWs()
                when (val c = next()) {
                    ',' -> continue
                    '}' -> break
                    else -> error("Expected ',' or '}' but got '$c' at ${pos - 1}")
                }
            }
            return map
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWs()
                when (val c = next()) {
                    ',' -> continue
                    ']' -> break
                    else -> error("Expected ',' or ']' but got '$c' at ${pos - 1}")
                }
            }
            return list
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) error("Unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[pos++]
                        when (e) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                val hex = s.substring(pos, pos + 4)
                                pos += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> error("Invalid escape '\\$e' at ${pos - 1}")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Double {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length && (s[pos] in '0'..'9' || s[pos] == '.' ||
                    s[pos] == 'e' || s[pos] == 'E' || s[pos] == '+' || s[pos] == '-')
            ) {
                pos++
            }
            return s.substring(start, pos).toDouble()
        }

        private fun parseBoolean(): Boolean =
            when {
                s.startsWith("true", pos) -> { pos += 4; true }
                s.startsWith("false", pos) -> { pos += 5; false }
                else -> error("Invalid literal at $pos")
            }

        private fun parseNull(): Any? {
            require(s.startsWith("null", pos)) { "Invalid literal at $pos" }
            pos += 4
            return null
        }

        private fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun peek(): Char = if (pos < s.length) s[pos] else '\u0000'
        private fun next(): Char = s[pos++]
        private fun expect(c: Char) {
            val actual = next()
            require(actual == c) { "Expected '$c' but got '$actual' at ${pos - 1}" }
        }
    }
}
