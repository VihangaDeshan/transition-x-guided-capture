// SPDX-License-Identifier: Apache-2.0
// replay: a tiny dependency-free JSON reader/writer, so log writing and offline replay share one
// implementation and run on a plain JVM (org.json is an Android stub in unit tests).
package lk.transitionx.capture.replay

object JsonLite {
    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** Serialises maps, lists, strings, numbers, booleans, enums and null. */
    fun write(value: Any?): String = StringBuilder().also { writeTo(it, value) }.toString()

    private fun writeTo(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> sb.append(quote(v))
            is Boolean -> sb.append(v)
            is Float -> sb.append(if (v.isFinite()) trimNumber(v.toDouble()) else "null")
            is Double -> sb.append(if (v.isFinite()) trimNumber(v) else "null")
            is Number -> sb.append(v.toString())
            is Enum<*> -> sb.append(quote(v.name))
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    sb.append(quote(k.toString())).append(':')
                    writeTo(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeTo(sb, item)
                }
                sb.append(']')
            }
            is FloatArray -> writeTo(sb, v.toList())
            is IntArray -> writeTo(sb, v.toList())
            else -> sb.append(quote(v.toString()))
        }
    }

    private fun trimNumber(d: Double): String {
        if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) return d.toLong().toString()
        val s = String.format(java.util.Locale.ROOT, "%.5f", d)
        return s.trimEnd('0').trimEnd('.')
    }

    /** Parses one JSON value. Objects become LinkedHashMap, arrays List, numbers Double. */
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        require(p.i == text.length) { "trailing characters at ${p.i}" }
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?> ?: throw IllegalArgumentException("not a JSON object")

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            require(i < s.length) { "unexpected end" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("bad char '$c' at $i")
            }
        }
        fun lit(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "bad literal at $i" }
            i += word.length
            return v
        }
        fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-eE.")) i++
            return s.substring(start, i).toDouble()
        }
        fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated string" }
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun obj(): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws()
                val k = str()
                ws(); require(s[i] == ':') { "expected ':' at $i" }; i++; ws()
                m[k] = value()
                ws()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> throw IllegalArgumentException("expected ',' or '}' at ${i - 1}")
                }
            }
        }
        fun arr(): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                ws(); l += value(); ws()
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return l
                    else -> throw IllegalArgumentException("expected ',' or ']' at ${i - 1}")
                }
            }
        }
    }
}
