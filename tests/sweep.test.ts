// The reminder sweep.
//
// The sweep logic lives in _shared/sweep.ts so it can be tested without
// deploying; the Edge Function is a thin wrapper that calls it and broadcasts
// the result.

import { assert, assertEquals } from "@std/assert";
import { serviceClient } from "../supabase/functions/_shared/db.ts";
import { sweepDue } from "../supabase/functions/_shared/sweep.ts";

const db = serviceClient();
const MARK = `zz${crypto.randomUUID().slice(0, 8)}`;

const wipe = () => db.from("reminders").delete().like("text", "zz%");
const at = (ms: number) => new Date(Date.now() + ms).toISOString();

async function seed(text: string, dueAt: string, extra: Record<string, unknown> = {}) {
  const { data, error } = await db
    .from("reminders")
    .insert({ text: `${MARK} ${text}`, due_at: dueAt, source: "test", ...extra })
    .select()
    .single();
  if (error) throw new Error(error.message);
  return data as { id: string };
}

const mine = (rs: { text: string }[]) => rs.filter((r) => r.text.startsWith(MARK));

Deno.test("a reminder due in the past is returned and marked fired", async () => {
  await wipe();
  const r = await seed("overdue", at(-60_000));

  const fired = mine(await sweepDue());
  assertEquals(fired.length, 1);

  const { data } = await db.from("reminders").select("fired_at").eq("id", r.id).single();
  assert(data!.fired_at !== null, "fired_at must be stamped");
  await wipe();
});

Deno.test("a reminder due in the future is untouched", async () => {
  await wipe();
  const r = await seed("later", at(60 * 60_000));

  assertEquals(mine(await sweepDue()).length, 0);
  const { data } = await db.from("reminders").select("fired_at").eq("id", r.id).single();
  assertEquals(data!.fired_at, null);
  await wipe();
});

Deno.test("an already-fired reminder is not fired twice", async () => {
  await wipe();
  await seed("done already", at(-60_000), { fired_at: new Date().toISOString() });

  assertEquals(mine(await sweepDue()).length, 0, "a fired reminder must not fire again");
  await wipe();
});

Deno.test("a recurring reminder reschedules itself after firing", async () => {
  await wipe();
  const r = await seed("standup", at(-60_000), { recurring: "daily" });

  assertEquals(mine(await sweepDue()).length, 1);

  const { data } = await db.from("reminders").select("id, due_at, fired_at, recurring")
    .like("text", `${MARK}%`);
  const rows = data as { id: string; due_at: string; fired_at: string | null; recurring: string | null }[];

  assertEquals(rows.length, 2, "firing a recurring reminder should leave a successor");
  const next = rows.find((row) => row.id !== r.id)!;
  assertEquals(next.fired_at, null, "the successor must be pending");
  assertEquals(next.recurring, "daily", "the successor must keep recurring");
  assert(
    new Date(next.due_at).getTime() > Date.now(),
    "the successor must be due in the future",
  );
  await wipe();
});

// Review Focus: free projects pause and Wi-Fi drops. A reminder that came due
// while nothing was running must fire late, not vanish — a timer you cannot
// trust is worse than no timer.
Deno.test("a reminder four hours overdue still fires", async () => {
  await wipe();
  await seed("very overdue", at(-4 * 60 * 60_000));

  assertEquals(
    mine(await sweepDue()).length,
    1,
    "an overdue reminder must still fire; the sweep has no lower bound",
  );
  await wipe();
});
