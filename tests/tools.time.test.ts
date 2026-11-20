// Reminder and timer tools.
//
// parseWhen is pure and takes `nowUtc`, so every time case is deterministic
// rather than dependent on when the suite runs. The DST case is the reason
// the implementation must resolve wall-clock times through Intl rather than a
// fixed +02:00 — Cairo observes DST, so a fixed offset is an hour wrong for
// half the year.

import { assert, assertEquals } from "@std/assert";
import { serviceClient } from "../supabase/functions/_shared/db.ts";
import {
  cancelReminder,
  listReminders,
  nag,
  parseWhen,
  setReminder,
  startTimer,
} from "../supabase/functions/_shared/tools/time.ts";

const MARK = `zz${crypto.randomUUID().slice(0, 8)}`;
const db = serviceClient();

async function wipe() {
  await db.from("reminders").delete().like("text", "zz%");
  await db.from("tasks").delete().like("title", "zz%");
}

/** Wall-clock hour in Cairo for an instant. */
function cairoHour(d: Date): number {
  return Number(
    new Intl.DateTimeFormat("en-GB", {
      timeZone: "Africa/Cairo",
      hour: "2-digit",
      hour12: false,
    }).format(d),
  );
}

function cairoDay(d: Date): string {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: "Africa/Cairo",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(d);
}

// ---------------------------------------------------------------- parseWhen

Deno.test("'in 50 minutes' resolves to now + 50m", () => {
  const now = new Date("2026-03-10T08:00:00Z");
  assertEquals(
    parseWhen("in 50 minutes", now)?.toISOString(),
    new Date("2026-03-10T08:50:00Z").toISOString(),
  );
});

Deno.test("'in an hour and a half' resolves to now + 90m", () => {
  const now = new Date("2026-03-10T08:00:00Z");
  assertEquals(
    parseWhen("in an hour and a half", now)?.toISOString(),
    new Date("2026-03-10T09:30:00Z").toISOString(),
  );
});

Deno.test("'at 3pm' resolves to today 15:00 Cairo when now is morning", () => {
  // 06:00Z is 08:00 Cairo in winter — the same day, before 3pm.
  const now = new Date("2026-01-15T06:00:00Z");
  const got = parseWhen("at 3pm", now)!;
  assertEquals(cairoHour(got), 15);
  assertEquals(cairoDay(got), cairoDay(now));
});

Deno.test("'at 3pm' resolves to tomorrow 15:00 Cairo when now is evening", () => {
  // 19:00Z is 21:00 Cairo in winter — 3pm has gone, so it must mean tomorrow.
  const now = new Date("2026-01-15T19:00:00Z");
  const got = parseWhen("at 3pm", now)!;
  assertEquals(cairoHour(got), 15);
  assert(got.getTime() > now.getTime(), "must be in the future");
  assert(cairoDay(got) !== cairoDay(now), "must be the next day");
});

Deno.test("'tomorrow morning' resolves to tomorrow 09:00 Cairo", () => {
  const now = new Date("2026-01-15T19:00:00Z");
  const got = parseWhen("tomorrow morning", now)!;
  assertEquals(cairoHour(got), 9);
  assert(cairoDay(got) !== cairoDay(now));
});

Deno.test("gibberish returns null rather than a wrong time", () => {
  const now = new Date("2026-03-10T08:00:00Z");
  assertEquals(parseWhen("asdfqwer", now), null);
  assertEquals(parseWhen("", now), null);
});

// Review Focus: Cairo observes DST. A fixed +02:00 offset puts every
// wall-clock reminder an hour out for roughly half the year, and the error is
// invisible until someone misses something.
Deno.test("'at 3pm' lands at 15:00 Cairo on both sides of the DST change", () => {
  const winter = new Date("2026-01-15T06:00:00Z"); // UTC+2
  const summer = new Date("2026-07-15T05:00:00Z"); // UTC+3

  assertEquals(cairoHour(parseWhen("at 3pm", winter)!), 15, "winter");
  assertEquals(cairoHour(parseWhen("at 3pm", summer)!), 15, "summer");
});

// ------------------------------------------------------------------- storage

Deno.test("stored due_at is UTC, not local", async () => {
  await wipe();
  const r = await setReminder(`${MARK} drink water`, "in 50 minutes");
  assert(r !== null);

  const { data } = await db.from("reminders").select("due_at").eq("id", r.id).single();
  const raw = String(data!.due_at);
  const parsed = new Date(raw);
  // Round-tripping through Date must not shift it: the column is timestamptz
  // and the value we read back must be the same instant we stored.
  assertEquals(parsed.toISOString(), new Date(r.dueAt).toISOString());
  await wipe();
});

Deno.test("startTimer(50, 'focus') creates a reminder due 50 minutes out", async () => {
  await wipe();
  const before = Date.now();
  const r = await startTimer(50, `${MARK} focus`);
  const delta = new Date(r.dueAt).getTime() - before;
  assert(
    delta > 49 * 60_000 && delta < 51 * 60_000,
    `expected ~50 minutes out, got ${Math.round(delta / 60_000)}`,
  );
  await wipe();
});

Deno.test("listReminders returns only unfired reminders, soonest first", async () => {
  await wipe();
  await setReminder(`${MARK} later`, "in 50 minutes");
  await setReminder(`${MARK} sooner`, "in 10 minutes");
  const fired = await setReminder(`${MARK} already done`, "in 20 minutes");
  await db.from("reminders").update({ fired_at: new Date().toISOString() }).eq("id", fired!.id);

  const mine = (await listReminders()).filter((r) => r.text.startsWith(MARK));
  assertEquals(mine.map((r) => r.text), [`${MARK} sooner`, `${MARK} later`]);
  await wipe();
});

Deno.test("cancelReminder removes a pending reminder and returns false for no match", async () => {
  await wipe();
  await setReminder(`${MARK} cancel me`, "in 30 minutes");

  assertEquals(await cancelReminder(`${MARK} cancel me`), true);
  const { data } = await db.from("reminders").select("id").like("text", `${MARK}%`);
  assertEquals(data?.length, 0);

  assertEquals(await cancelReminder(`${MARK} never existed`), false);
});

// ----------------------------------------------------------------------- nag

Deno.test("nag('the deck thing', 'in 2 hours') stores a reminder naming that task", async () => {
  await wipe();
  await db.from("tasks").insert({ title: `${MARK} Fix the deck`, status: "open" });

  const r = await nag(`${MARK} the deck thing`, "in 2 hours");
  assert(r !== null, "expected a reminder");
  assert(
    r.text.includes("Fix the deck"),
    `reminder should name the task, got: ${r.text}`,
  );
  await wipe();
});

Deno.test("nag on an unknown task returns null rather than a free-floating reminder", async () => {
  await wipe();
  assertEquals(await nag(`${MARK} no such task anywhere`, "in 2 hours"), null);

  const { data } = await db.from("reminders").select("id").like("text", "zz%");
  assertEquals(data?.length, 0, "must not create a reminder for a task it could not find");
});
