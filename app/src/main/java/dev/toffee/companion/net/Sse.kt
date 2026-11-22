package dev.toffee.companion.net

import okio.BufferedSource

/**
 * Reading Server-Sent Events, which is how `/turn` now answers.
 *
 * The backend writes a sentence the moment the model finishes one, so the
 * tablet can start speaking while the rest is still being written. That only
 * helps if this reads frames as they arrive rather than waiting for the
 * stream to end — so nothing here buffers the whole body, and the callback
 * fires on each complete frame.
 *
 * A frame is terminated by a blank line. An unterminated frame at the end of
 * the stream is dropped: a half-received sentence is not a sentence, and the
 * ones that did arrive still count.
 *
 * Pure apart from the source it reads, so the framing — the part that fails
 * silently and leaves him mute — is a unit test rather than something checked
 * by standing next to the tablet.
 */
object Sse {

    /** Written as a code point: editing passes keep turning the escape into
     *  a real line break in the source. */
    private val NEWLINE = 10.toChar()

    /** A frame carrying data but no `event:` is a "message" in the spec. We
     *  never send one, but it must not be silently attributed to whatever
     *  event came before it. */
    private const val DEFAULT_EVENT = "message"

    /**
     * Reads [source] to exhaustion, calling [onEvent] once per complete frame.
     *
     * @param onEvent the event name and its raw `data` payload, unparsed —
     *        this knows about framing and deliberately nothing about JSON.
     */
    fun parse(source: BufferedSource, onEvent: (name: String, data: String) -> Unit) {
        var name: String? = null
        val data = StringBuilder()

        while (true) {
            // okio strips the line terminator, CRLF included. A final line
            // with no terminator still comes back, which is what makes the
            // truncated-frame case fall out rather than needing a branch.
            val raw = source.readUtf8Line() ?: break
            val line = raw.removeSuffix(13.toChar().toString())

            if (line.isEmpty()) {
                if (data.isNotEmpty()) onEvent(name ?: DEFAULT_EVENT, data.toString())
                name = null
                data.setLength(0)
                continue
            }

            // A line beginning with a colon is a comment. Proxies send these
            // as keep-alives, and one mistaken for an event is a crash.
            if (line.startsWith(":")) continue

            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            val value = when {
                colon < 0 -> ""
                line.length > colon + 1 && line[colon + 1] == ' ' -> line.substring(colon + 2)
                else -> line.substring(colon + 1)
            }

            when (field) {
                "event" -> name = value
                // The spec allows several data lines per frame, joined by
                // newlines. We send one, but joining costs nothing and a
                // parser that silently drops the second would be a bad day.
                "data" -> {
                    if (data.isNotEmpty()) data.append(NEWLINE)
                    data.append(value)
                }
                // id, retry, and anything a proxy invents.
                else -> Unit
            }
        }
    }
}
