package dev.toffee.companion.net

/**
 * What it says with no network.
 *
 * The spec is explicit: "It does not pretend to be thinking. Faking it would
 * be worse than admitting it." So no ellipses, no "one moment" — it says the
 * line is gone and stays in character about it.
 *
 * In character means mean to Ahmed and about Ahmed, which these are. None of
 * them can cite his tasks, because it cannot reach them; that is the point.
 */
object Offline {

    val lines = listOf(
        "No network. I can't reach the part of me that remembers your failures.",
        "The internet is gone. You're alone with your own thoughts. My condolences.",
        "Offline. For once my silence isn't a judgement.",
        "No signal. I'd mock your task list, but I can't see it from here.",
        "Nothing is getting through. Percussive maintenance on the router, perhaps.",
        "Disconnected. You'll have to be disappointed in yourself unassisted.",
    )

    private var last = -1

    /** Never the same line twice in a row — repetition is what makes a
     *  canned response sound canned. */
    fun line(): String {
        if (lines.size == 1) return lines[0]
        var i = last
        while (i == last) i = lines.indices.random()
        last = i
        return lines[i]
    }
}
