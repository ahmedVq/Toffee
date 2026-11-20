// Reminders and timers.
//
// Wall-clock phrases ("at 3pm") are resolved through Intl in the target zone,
// never by adding a fixed offset. Cairo observes DST, so a fixed +02:00 would
// put every such reminder an hour out for half the year — and the error stays
// invisible until someone misses something.
//
// Everything is stored as an instant (timestamptz). Local time exists only
// while parsing.

import { serviceClient, toReminder } from "../db.ts";
import type { Reminder } from "../types.ts";
import { findTask } from "./tasks.ts";

const TZ = "Africa/Cairo";
const MORNING_HOUR = 9;

/** Milliseconds the zone is ahead of UTC at a given instant. */
function offsetMs(at: Date, tz: string): number {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat("en-US", {
      timeZone: tz,
      hour12: false,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    }).formatToParts(at).map((p) => [p.type, p.value]),
  );
  const asIfUtc = Date.UTC(
    Number(parts.year),
    Number(parts.month) - 1,
    Number(parts.day),
    Number(parts.hour) % 24,
    Number(parts.minute),
    Number(parts.second),
  );
  return asIfUtc - at.getTime();
}

/** The calendar date in `tz` at a given instant. */
function zonedDate(at: Date, tz: string) {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat("en-CA", {
      timeZone: tz,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    }).formatToParts(at).map((p) => [p.type, p.value]),
  );
  return {
    y: Number(parts.year),
    m: Number(parts.month),
    d: Number(parts.day),
  };
}

/** The instant at which the wall clock in `tz` reads the given local time.
 * Resolved twice because shifting by the offset can cross a DST boundary and
 * change which offset applies. */
function instantFor(
  y: number,
  m: number,
  d: number,
  hour: number,
  minute: number,
  tz: string,
): Date {
  const naive = Date.UTC(y, m - 1, d, hour, minute);
  let utc = naive - offsetMs(new Date(naive), tz);
  const settled = naive - offsetMs(new Date(utc), tz);
  if (settled !== utc) utc = settled;
  return new Date(utc);
}

const WORD_MINUTES: Record<string, number> = {
  "half an hour": 30,
  "an hour and a half": 90,
  "an hour": 60,
  "a minute": 1,
};

/** A phrase like "in 50 minutes" or "at 3pm" as an instant, or null if it is
 * not a time at all. Returning null matters: a wrong time is worse than an
 * admission that the phrase was not understood. */
export function parseWhen(
  phrase: string,
  nowUtc: Date,
  tz = TZ,
): Date | null {
  const s = phrase.trim().toLowerCase();
  if (!s) return null;

  // "in <words>" — "in an hour and a half", "in half an hour"
  for (const [words, minutes] of Object.entries(WORD_MINUTES)) {
    if (s === `in ${words}`) return new Date(nowUtc.getTime() + minutes * 60_000);
  }

  // "in 50 minutes", "in 2 hours"
  const relative = s.match(/^in\s+(\d+)\s*(minute|min|hour|hr)s?$/);
  if (relative) {
    const n = Number(relative[1]);
    const unit = relative[2].startsWith("h") ? 3_600_000 : 60_000;
    return new Date(nowUtc.getTime() + n * unit);
  }

  // "at 3pm", "at 15:30", "at 9:05am"
  const clock = s.match(/^at\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?$/);
  if (clock) {
    let hour = Number(clock[1]);
    const minute = Number(clock[2] ?? 0);
    const meridiem = clock[3];
    if (hour > 23 || minute > 59) return null;
    if (meridiem === "pm" && hour < 12) hour += 12;
    if (meridiem === "am" && hour === 12) hour = 0;

    const today = zonedDate(nowUtc, tz);
    let when = instantFor(today.y, today.m, today.d, hour, minute, tz);
    if (when.getTime() <= nowUtc.getTime()) {
      // The time has already gone today, so they mean tomorrow.
      const next = new Date(when.getTime() + 86_400_000);
      const d2 = zonedDate(next, tz);
      when = instantFor(d2.y, d2.m, d2.d, hour, minute, tz);
    }
    return when;
  }

  // "tomorrow morning", "tomorrow"
  if (s === "tomorrow morning" || s === "tomorrow") {
    const next = new Date(nowUtc.getTime() + 86_400_000);
    const d = zonedDate(next, tz);
    return instantFor(d.y, d.m, d.d, MORNING_HOUR, 0, tz);
  }

  return null;
}

// --------------------------------------------------------------- persistence

async function insertReminder(
  text: string,
  dueAt: Date,
  source: string,
): Promise<Reminder> {
  const { data, error } = await serviceClient()
    .from("reminders")
    .insert({ text, due_at: dueAt.toISOString(), source })
    .select()
    .single();
  if (error) throw new Error(`saving reminder failed: ${error.message}`);
  return toReminder(data);
}

export async function setReminder(
  text: string,
  phrase: string,
): Promise<Reminder | null> {
  const due = parseWhen(phrase, new Date());
  if (!due) return null;
  return await insertReminder(text, due, "voice");
}

export async function startTimer(
  minutes: number,
  label: string,
): Promise<Reminder> {
  const due = new Date(Date.now() + minutes * 60_000);
  return await insertReminder(label, due, "timer");
}

/** Pending only, soonest first — a fired reminder is history, not a list. */
export async function listReminders(): Promise<Reminder[]> {
  const { data, error } = await serviceClient()
    .from("reminders")
    .select("*")
    .is("fired_at", null)
    .order("due_at", { ascending: true });
  if (error) throw new Error(`listing reminders failed: ${error.message}`);
  return (data ?? []).map(toReminder);
}

export async function cancelReminder(query: string): Promise<boolean> {
  const needle = query.trim().toLowerCase();
  if (!needle) return false;

  const match = (await listReminders()).find((r) =>
    r.text.toLowerCase().includes(needle) || needle.includes(r.text.toLowerCase())
  );
  if (!match) return false;

  const { error } = await serviceClient().from("reminders").delete().eq("id", match.id);
  if (error) throw new Error(`cancelling reminder failed: ${error.message}`);
  return true;
}

/** A reminder bound to a task, so the agent can promise to come back to
 * something and then actually do it. Refuses when it cannot identify the
 * task — a nag about nothing in particular is just noise. */
export async function nag(
  taskTitleQuery: string,
  phrase: string,
): Promise<Reminder | null> {
  const found = await findTask(taskTitleQuery);
  if (found === null || "ambiguous" in found) return null;

  const due = parseWhen(phrase, new Date());
  if (!due) return null;

  return await insertReminder(found.title, due, "nag");
}
