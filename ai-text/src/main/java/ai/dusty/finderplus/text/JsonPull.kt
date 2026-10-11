package ai.dusty.finderplus.text

import java.io.Reader

/**
 * Minimal pull-style JSON reader, enough to stream a 30+ MB `tokenizer.json` without building a DOM of
 * half a million merge pairs.
 *
 * Pure Kotlin because `android.util.JsonReader` is a stub in JVM unit tests, which is where the
 * tokenizer is checked against the reference implementation. Strict enough for machine-written JSON;
 * not a general validator.
 */
internal class JsonPull(private val reader: Reader) {

    enum class Token { BEGIN_OBJECT, END_OBJECT, BEGIN_ARRAY, END_ARRAY, NAME, STRING, NUMBER, BOOLEAN, NULL, END }

    private val buf = CharArray(1 shl 16)
    private var pos = 0
    private var limit = 0
    private val sb = StringBuilder(64)

    /** One entry per open container: true while the next string inside an object is a key. */
    private val nameNext = ArrayList<Boolean>()
    private val inObject = ArrayList<Boolean>()

    private fun fill(): Boolean {
        limit = reader.read(buf, 0, buf.size)
        pos = 0
        return limit > 0
    }

    private fun peekChar(): Int {
        if (pos >= limit && !fill()) return -1
        return buf[pos].code
    }

    private fun readChar(): Char {
        if (pos >= limit && !fill()) throw IllegalStateException("unexpected end of JSON")
        return buf[pos++]
    }

    /** Skips whitespace and the structural separators, which carry no information for a pull reader. */
    private fun skipSeparators(): Int {
        while (true) {
            val c = peekChar()
            if (c == -1) return -1
            when (c.toChar()) {
                ' ', '\n', '\r', '\t', ',', ':' -> pos++
                else -> return c
            }
        }
    }

    fun peek(): Token {
        val c = skipSeparators()
        if (c == -1) return Token.END
        return when (c.toChar()) {
            '{' -> Token.BEGIN_OBJECT
            '}' -> Token.END_OBJECT
            '[' -> Token.BEGIN_ARRAY
            ']' -> Token.END_ARRAY
            '"' -> if (inObject.lastOrNull() == true && nameNext.last()) Token.NAME else Token.STRING
            't', 'f' -> Token.BOOLEAN
            'n' -> Token.NULL
            else -> Token.NUMBER
        }
    }

    fun hasNext(): Boolean {
        val t = peek()
        return t != Token.END_OBJECT && t != Token.END_ARRAY && t != Token.END
    }

    fun beginObject() { expect('{'); inObject += true; nameNext += true }
    fun beginArray() { expect('['); inObject += false; nameNext += false }
    fun endObject() { expect('}'); pop() }
    fun endArray() { expect(']'); pop() }

    fun nextName(): String {
        check(peek() == Token.NAME) { "expected a name" }
        val s = readString()
        nameNext[nameNext.size - 1] = false
        return s
    }

    fun nextString(): String {
        check(peek() == Token.STRING) { "expected a string" }
        return readString().also { valueDone() }
    }

    fun nextInt(): Int = nextNumberText().toDouble().toInt()

    fun nextDouble(): Double = nextNumberText().toDouble()

    fun nextBoolean(): Boolean {
        check(peek() == Token.BOOLEAN)
        val v = readChar() == 't'
        repeat(if (v) 3 else 4) { readChar() }
        valueDone()
        return v
    }

    fun nextNull() {
        check(peek() == Token.NULL)
        repeat(4) { readChar() }
        valueDone()
    }

    /** True (and consumed) when the next value is `null`. */
    fun skipIfNull(): Boolean {
        if (peek() != Token.NULL) return false
        nextNull()
        return true
    }

    /** The next value as plain Kotlin: objects as maps, arrays as lists, numbers as doubles. For small files. */
    fun nextValue(): Any? = when (peek()) {
        Token.BEGIN_OBJECT -> LinkedHashMap<String, Any?>().also { m -> beginObject(); while (hasNext()) m[nextName()] = nextValue(); endObject() }
        Token.BEGIN_ARRAY -> ArrayList<Any?>().also { l -> beginArray(); while (hasNext()) l += nextValue(); endArray() }
        Token.STRING -> nextString()
        Token.NUMBER -> nextDouble()
        Token.BOOLEAN -> nextBoolean()
        Token.NULL -> { nextNull(); null }
        else -> throw IllegalStateException("unexpected ${peek()}")
    }

    fun skipValue() {
        when (peek()) {
            Token.BEGIN_OBJECT -> { beginObject(); while (hasNext()) { nextName(); skipValue() }; endObject() }
            Token.BEGIN_ARRAY -> { beginArray(); while (hasNext()) skipValue(); endArray() }
            Token.STRING -> nextString()
            Token.NUMBER -> nextNumberText()
            Token.BOOLEAN -> nextBoolean()
            Token.NULL -> nextNull()
            else -> throw IllegalStateException("cannot skip ${peek()}")
        }
    }

    private fun nextNumberText(): String {
        check(peek() == Token.NUMBER) { "expected a number" }
        sb.setLength(0)
        while (true) {
            val c = peekChar()
            if (c == -1) break
            val ch = c.toChar()
            if (ch.isDigit() || ch == '-' || ch == '+' || ch == '.' || ch == 'e' || ch == 'E') {
                sb.append(ch); pos++
            } else break
        }
        valueDone()
        return sb.toString()
    }

    private fun readString(): String {
        expect('"')
        sb.setLength(0)
        while (true) {
            val c = readChar()
            when (c) {
                '"' -> return sb.toString()
                '\\' -> when (val e = readChar()) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    // Surrogate pairs arrive as two escapes and recombine naturally as two UTF-16 units.
                    'u' -> {
                        var code = 0
                        repeat(4) { code = (code shl 4) or Character.digit(readChar(), 16) }
                        sb.append(code.toChar())
                    }
                    else -> throw IllegalStateException("bad escape \\$e")
                }
                else -> sb.append(c)
            }
        }
    }

    private fun expect(c: Char) {
        val got = skipSeparators()
        check(got == c.code) { "expected '$c' but found '${if (got == -1) "EOF" else got.toChar()}'" }
        pos++
    }

    private fun pop() {
        inObject.removeAt(inObject.size - 1)
        nameNext.removeAt(nameNext.size - 1)
        valueDone()
    }

    /** A value just completed: inside an object, the next string is a key again. */
    private fun valueDone() {
        if (inObject.lastOrNull() == true) nameNext[nameNext.size - 1] = true
    }
}
