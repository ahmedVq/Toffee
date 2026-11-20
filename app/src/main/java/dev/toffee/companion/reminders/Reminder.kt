package dev.toffee.companion.reminders

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * One reminder, as the tablet sees it.
 *
 * [firedAtMs] is null until the server's sweep marks it due. The tablet never
 * decides that a reminder has fired; it only notices.
 */
data class Reminder(
    val id: String,
    val text: String,
    val dueAtMs: Long,
    val firedAtMs: Long?,
)

/**
 * Postgres hands timestamps back as `2026-11-20T10:00:00.123456+00:00` — with
 * microseconds and a numeric offset, not the `Z` form `Instant.parse` accepts.
 * Getting this wrong makes every reminder look un-fired, which is the exact
 * silent failure the two-route design exists to prevent, so it is parsed
 * through OffsetDateTime and tested against every shape the server sends.
 *
 * API 27 is Android 8.1, and java.time arrived in API 26, so no desugaring.
 */
fun parseTimestamp(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(raw).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}

/** ISO-8601 with an offset, which is what PostgREST filters expect back. */
fun formatTimestamp(epochMs: Long): String =
    OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), java.time.ZoneOffset.UTC)
        .toString()
