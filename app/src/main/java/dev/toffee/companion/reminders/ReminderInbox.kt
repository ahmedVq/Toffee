package dev.toffee.companion.reminders

/** What the tablet has already said out loud. Survives a reboot, because a
 *  tablet that forgets is a tablet that nags twice. */
interface AnnouncedStore {
    fun contains(id: String): Boolean
    fun add(id: String)
}

/**
 * The place the two routes meet.
 *
 * A reminder reaches the tablet twice on purpose — once from the server poll,
 * once from a local alarm that fires with no network at all. Whichever gets
 * here first wins; the other is dropped by id.
 *
 * [offer] and [markAnnounced] are separate steps, and the gap between them
 * matters: offering is "this came due", marking is the app saying it actually
 * spoke. Collapsing them would lose the reminder every time speaking failed.
 */
class ReminderInbox(private val seen: AnnouncedStore) {

    enum class Source { POLL, ALARM }

    /** The ones that have fired and have not been announced yet. [source] is
     *  carried for logging: which route won is worth knowing when the thing
     *  misbehaves on a desk at 3am. */
    fun offer(source: Source, reminders: List<Reminder>): List<Reminder> =
        reminders.filter { it.firedAtMs != null && !seen.contains(it.id) }

    fun markAnnounced(id: String) = seen.add(id)

    fun announced(id: String): Boolean = seen.contains(id)
}
