package dev.toffee.companion.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The header that makes raw microphone bytes into a file a transcription API
 * will accept.
 *
 * Worth testing precisely because getting it wrong does not look like an
 * error: a provider handed a malformed RIFF returns an empty transcript, the
 * tablet shows "I didn't catch that", and nothing anywhere says the bytes
 * were the problem.
 */
class WavTest {

    private fun buf(h: ByteArray) = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
    private fun ascii(h: ByteArray, at: Int) = String(h, at, 4, Charsets.US_ASCII)

    @Test
    fun `the header is forty four bytes`() {
        assertEquals(44, Wav.header(16_000, 320_000).size)
    }

    @Test
    fun `it is a riff wave file`() {
        val h = Wav.header(16_000, 1_000)
        assertEquals("RIFF", ascii(h, 0))
        assertEquals("WAVE", ascii(h, 8))
        assertEquals("fmt ", ascii(h, 12))
        assertEquals("data", ascii(h, 36))
    }

    @Test
    fun `it declares sixteen bit mono pcm`() {
        val b = buf(Wav.header(16_000, 1_000))
        assertEquals("fmt chunk is 16 bytes for PCM", 16, b.getInt(16))
        assertEquals("format 1 is uncompressed PCM", 1, b.getShort(20).toInt())
        assertEquals("one channel", 1, b.getShort(22).toInt())
        assertEquals("sixteen bits per sample", 16, b.getShort(34).toInt())
    }

    @Test
    fun `the sizes agree with the data`() {
        val b = buf(Wav.header(16_000, 1_234))
        assertEquals("RIFF size counts everything after it", 1_234 + 36, b.getInt(4))
        assertEquals("data size is the payload", 1_234, b.getInt(40))
    }

    @Test
    fun `the byte rate follows the sample rate`() {
        val b = buf(Wav.header(16_000, 0))
        assertEquals("16000 samples a second at two bytes each", 32_000, b.getInt(28))
        assertEquals("one mono sample is two bytes", 2, b.getShort(32).toInt())
        assertEquals(16_000, b.getInt(24))
    }

    @Test
    fun `a different sample rate changes the byte rate with it`() {
        val b = buf(Wav.header(8_000, 0))
        assertEquals(8_000, b.getInt(24))
        assertEquals(16_000, b.getInt(28))
    }
}
