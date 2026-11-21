package dev.toffee.companion.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Turns what the microphone gives us into something a transcription API will
 * accept.
 *
 * `AudioRecord` hands back raw 16-bit little-endian PCM samples with no
 * container at all. Every speech API wants a file. This is the 44 bytes in
 * between.
 */
object Wav {

    /** What every speech API is tuned for, and a quarter of the bytes of
     *  44.1kHz. Desk speech has nothing above 8kHz worth keeping. */
    const val SAMPLE_RATE = 16_000

    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val HEADER_BYTES = 44

    /**
     * A canonical RIFF/WAVE header for [dataBytes] of 16-bit mono PCM.
     *
     * The byte count has to be known up front, which is why the recording is
     * buffered and the header prepended at the end rather than streamed.
     */
    fun header(sampleRate: Int, dataBytes: Int): ByteArray {
        val bytesPerFrame = CHANNELS * BITS_PER_SAMPLE / 8
        return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            // Everything after this field: the 36 bytes of header that
            // follow, plus the samples.
            putInt(dataBytes + 36)
            put("WAVE".toByteArray(Charsets.US_ASCII))

            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // the fmt chunk's own length, 16 for uncompressed PCM
            putShort(1) // format 1: PCM, no compression
            putShort(CHANNELS.toShort())
            putInt(sampleRate)
            putInt(sampleRate * bytesPerFrame) // byte rate
            putShort(bytesPerFrame.toShort()) // block align
            putShort(BITS_PER_SAMPLE.toShort())

            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
        }.array()
    }
}
