package dev.toffee.companion.net

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The other half of the wire format `_shared/stream.ts` writes.
 *
 * Framing bugs here are the worst kind available: the stream looks fine, the
 * tablet just never speaks. So this parses real bytes in real frames rather
 * than trusting a split on a separator.
 */
class SseTest {

    private val br = 10.toChar()

    private fun frames(vararg lines: String): Buffer =
        Buffer().writeUtf8(lines.joinToString(""))

    private fun collect(source: Buffer): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        Sse.parse(source) { name, data -> out += name to data }
        return out
    }

    @Test
    fun oneEventIsRead() {
        val seen = collect(frames("event: text${br}data: {\"text\":\"Hi.\"}${br}${br}"))
        assertEquals(listOf("text" to "{\"text\":\"Hi.\"}"), seen)
    }

    @Test
    fun severalEventsComeOutInOrder() {
        val seen = collect(
            frames(
                "event: mood${br}data: {\"mood\":\"smug\"}${br}${br}",
                "event: text${br}data: {\"text\":\"One.\"}${br}${br}",
                "event: text${br}data: {\"text\":\"Two.\"}${br}${br}",
                "event: done${br}data: {\"reply\":\"One. Two.\"}${br}${br}",
            ),
        )
        assertEquals(listOf("mood", "text", "text", "done"), seen.map { it.first })
        assertEquals("{\"text\":\"Two.\"}", seen[2].second)
    }

    /** A stream that stops mid-frame is the network dropping, and the
     *  sentences that did arrive still count. */
    @Test
    fun aTruncatedFrameIsDroppedAndTheRestKept() {
        val seen = collect(
            frames(
                "event: text${br}data: {\"text\":\"One.\"}${br}${br}",
                "event: text${br}data: {\"text\":\"Tw",
            ),
        )
        assertEquals(listOf("text" to "{\"text\":\"One.\"}"), seen)
    }

    /** SSE allows comment lines and other fields; neither is an event. */
    @Test
    fun commentsAndUnknownFieldsAreIgnored() {
        val seen = collect(
            frames(
                ": keep-alive${br}${br}",
                "id: 7${br}retry: 1000${br}event: text${br}data: {\"text\":\"Hi.\"}${br}${br}",
            ),
        )
        assertEquals(listOf("text" to "{\"text\":\"Hi.\"}"), seen)
    }

    /** Proxies and some servers use CRLF. Ours does not, but a parser that
     *  breaks on one is a parser that breaks in production and nowhere else. */
    @Test
    fun carriageReturnsDoNotBreakIt() {
        val cr = 13.toChar()
        val seen = collect(
            frames("event: text${cr}${br}data: {\"text\":\"Hi.\"}${cr}${br}${cr}${br}"),
        )
        assertEquals(listOf("text" to "{\"text\":\"Hi.\"}"), seen)
    }

    @Test
    fun anEmptyStreamYieldsNothing() {
        assertEquals(emptyList<Pair<String, String>>(), collect(Buffer()))
    }

    /** A frame with data and no event name is a "message" in the spec. We
     *  never send one, so it must not be mistaken for the last event seen. */
    @Test
    fun dataWithoutAnEventNameIsNotAttributedToThePreviousEvent() {
        val seen = collect(
            frames(
                "event: text${br}data: {\"text\":\"One.\"}${br}${br}",
                "data: {\"text\":\"loose\"}${br}${br}",
            ),
        )
        assertEquals(listOf("text" to "{\"text\":\"One.\"}", "message" to "{\"text\":\"loose\"}"), seen)
    }
}
