package io.github.asterx5.kotlinspect.internal.har

/** Where an export's text goes: a file on the platforms that can write one. */
internal interface HarSink {
    fun write(text: String)
    fun close()
}

/**
 * A small streaming JSON writer: values go straight to the sink in compact form, so an export's
 * memory use does not grow with its size. Escaping follows RFC 8259: quote, backslash and control
 * characters below U+0020 (as \u00XX) are escaped, everything else is written as is.
 */
internal class HarJson(private val sink: HarSink) {
    private val buffer = StringBuilder(BUFFER)

    // One entry per open object or array: whether something was already written in it.
    private val hasItems = ArrayList<Boolean>()
    private var afterName = false

    fun beginObject(): HarJson = open('{')
    fun endObject(): HarJson = close('}')
    fun beginArray(): HarJson = open('[')
    fun endArray(): HarJson = close(']')

    fun name(name: String): HarJson {
        separate()
        string(name)
        buffer.append(':')
        afterName = true
        return this
    }

    fun value(text: String): HarJson {
        separate()
        string(text)
        return flushIfFull()
    }

    fun value(number: Long): HarJson {
        separate()
        buffer.append(number)
        return flushIfFull()
    }

    fun value(number: Int): HarJson = value(number.toLong())

    fun value(flag: Boolean): HarJson {
        separate()
        buffer.append(if (flag) "true" else "false")
        return flushIfFull()
    }

    fun field(name: String, text: String): HarJson = name(name).value(text)
    fun field(name: String, number: Long): HarJson = name(name).value(number)
    fun field(name: String, number: Int): HarJson = name(name).value(number)
    fun field(name: String, flag: Boolean): HarJson = name(name).value(flag)

    /** Writes whatever is buffered. Call before closing the sink. */
    fun flush() {
        if (buffer.isNotEmpty()) {
            sink.write(buffer.toString())
            buffer.setLength(0)
        }
    }

    private fun open(bracket: Char): HarJson {
        separate()
        buffer.append(bracket)
        hasItems.add(false)
        return this
    }

    private fun close(bracket: Char): HarJson {
        hasItems.removeAt(hasItems.lastIndex)
        buffer.append(bracket)
        return flushIfFull()
    }

    /** Adds the comma between items; a value right after its name needs none. */
    private fun separate() {
        if (afterName) {
            afterName = false
            return
        }
        if (hasItems.isEmpty()) return
        if (hasItems[hasItems.lastIndex]) buffer.append(',')
        hasItems[hasItems.lastIndex] = true
    }

    private fun string(s: String) {
        buffer.append('"')
        for (c in s) {
            when {
                c == '"' -> buffer.append("\\\"")
                c == '\\' -> buffer.append("\\\\")
                c < ' ' -> {
                    buffer.append("\\u00")
                    buffer.append(HEX[c.code shr 4])
                    buffer.append(HEX[c.code and 0xF])
                }
                else -> buffer.append(c)
            }
        }
        buffer.append('"')
    }

    private fun flushIfFull(): HarJson {
        if (buffer.length >= BUFFER) flush()
        return this
    }

    private companion object {
        const val BUFFER = 16 * 1024
        const val HEX = "0123456789ABCDEF"
    }
}
