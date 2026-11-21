package dev.toffee.companion.voice

/**
 * Which speech engine to try, and in what order.
 *
 * Pure, and separate from [AndroidVoice], because of what the tablet turned
 * out to be. After the factory reset `settings get secure tts_default_synth`
 * is null — there is no platform default — and the only engine installed is
 * Google's. An implementation that asks a `TextToSpeech` instance which
 * engines exist cannot recover from that, because the instance it would have
 * to ask is the one that failed to start.
 *
 * So the candidate list is built from PackageManager, the ordering is decided
 * here where it can be tested, and [AndroidVoice] just walks it.
 */
object TtsEngines {

    /**
     * Known-good engines, best first.
     *
     * Google's is what this ROM shipped with. Samsung's is listed because
     * other `gtaxllte` builds carry it and it needs no Google account at all.
     * eSpeak NG is the one the design doc wanted bundled: as a sideload it
     * lands here, with no NDK build in the way.
     */
    val PREFERRED = listOf("com.google.android.tts", "com.samsung.SMT", "com.reecedunn.espeak")

    /**
     * The engines to try, in order. A `null` entry means "whatever the
     * platform default is" — which is only worth trying when there is one.
     */
    fun order(installed: List<String>, default: String?): List<String?> {
        if (installed.isEmpty()) return emptyList()

        val out = mutableListOf<String?>()
        // A default naming an engine that is not installed is stale config,
        // not a candidate.
        val usableDefault = default?.takeIf { it in installed }
        if (usableDefault != null) out += null

        for (name in PREFERRED) {
            if (name in installed && name != usableDefault) out += name
        }
        for (name in installed) {
            // Retrying the default under its own name proves nothing.
            if (name != usableDefault && name !in out) out += name
        }
        return out
    }
}
