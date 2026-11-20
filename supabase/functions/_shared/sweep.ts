// Finding reminders that have come due.
//
// Deliberately no lower bound on the query: a reminder that came due while
// the project was paused, the tablet was asleep, or the Wi-Fi was down must
// fire late rather than vanish. A timer that silently skips is worse than no
// timer at all.

import { serviceClient, toReminder } from "./db.ts";
import type { Reminder } from "./types.ts";

const RECURRENCE_MS: Record<string, number> = {
  hourly: 3_600_000,
  daily: 86_400_000,
  weekly: 7 * 86_400_000,
};

/** The next occurrence strictly in the future — if several intervals were
 * missed while nothing was running, skip to the next one rather than firing
 * a backlog one per sweep. */
function nextOccurrence(dueAt: string, recurring: string, now: number): string | null {
  const step = RECURRENCE_MS[recurring];
  if (!step) return null;

  let next = new Date(dueAt).getTime() + step;
  if (next <= now) {
    const missed = Math.ceil((now - next) / step);
    next += missed * step;
  }
  return new Date(next).toISOString();
}

/** Marks every due reminder fired and returns them. Recurring ones leave a
 * pending successor behind. */
export async function sweepDue(now: Date = new Date()): Promise<Reminder[]> {
  const db = serviceClient();
  const stamp = now.toISOString();

  const { data, error } = await db
    .from("reminders")
    .select("*")
    .is("fired_at", null)
    .lte("due_at", stamp);
  if (error) throw new Error(`sweep failed: ${error.message}`);

  const due = (data ?? []) as Record<string, unknown>[];
  if (!due.length) return [];

  const ids = due.map((r) => String(r.id));
  const { error: markError } = await db
    .from("reminders")
    .update({ fired_at: stamp })
    .in("id", ids);
  if (markError) throw new Error(`marking reminders fired failed: ${markError.message}`);

  const successors = due
    .filter((r) => typeof r.recurring === "string" && r.recurring)
    .map((r) => {
      const next = nextOccurrence(String(r.due_at), String(r.recurring), now.getTime());
      return next
        ? {
          text: String(r.text),
          due_at: next,
          recurring: String(r.recurring),
          source: String(r.source ?? "recurring"),
        }
        : null;
    })
    .filter((r): r is NonNullable<typeof r> => r !== null);

  if (successors.length) {
    const { error: insertError } = await db.from("reminders").insert(successors);
    if (insertError) {
      throw new Error(`rescheduling recurring reminders failed: ${insertError.message}`);
    }
  }

  return due.map(toReminder);
}
