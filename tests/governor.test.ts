// The governor decides whether Toffee is allowed to speak unprompted.
//
// Two dimensions: annoyance (has it spoken recently, has it already made this
// point today) and cost (how much of today's LLM budget is left). The
// distinction that matters is that it suppresses *opinions* and never
// *obligations* — a due reminder speaks regardless.

import { assert, assertEquals } from "@std/assert";
import { serviceClient } from "../supabase/functions/_shared/db.ts";
import {
  COOLDOWN_MINUTES,
  DAILY_CALL_BUDGET,
  maySpeak,
  noteSpend,
  recordSaid,
} from "../supabase/functions/_shared/governor.ts";

const db = serviceClient();
const MARK = `zz${crypto.randomUUID().slice(0, 8)}`;
const today = new Date().toISOString().slice(0, 10);

async function wipe() {
  await db.from("said_log").delete().like("topic_key", "zz%");
  await db.from("llm_spend").delete().eq("day", today);
}

const minutesAgo = (n: number) => new Date(Date.now() - n * 60_000);

Deno.test("maySpeak is true when nothing has been said recently", async () => {
  await wipe();
  const r = await maySpeak(`${MARK}-fresh`, new Date());
  assert(r.ok, `expected ok, got: ${r.why}`);
  await wipe();
});

Deno.test("maySpeak is false within the cooldown window", async () => {
  await wipe();
  await recordSaid("something", `${MARK}-a`, "test");
  const r = await maySpeak(`${MARK}-b`, new Date());
  assertEquals(r.ok, false);
  await wipe();
});

Deno.test("maySpeak is false for a topicKey already used today", async () => {
  await wipe();
  await recordSaid("the battery jibe", `${MARK}-battery`, "test");
  // Past the cooldown, so only the repeated topic can block it.
  const r = await maySpeak(`${MARK}-battery`, new Date(Date.now() + (COOLDOWN_MINUTES + 5) * 60_000));
  assertEquals(r.ok, false, "a joke already made today must not be remade");
  await wipe();
});

Deno.test("maySpeak is true for a different topicKey inside the same day", async () => {
  await wipe();
  await recordSaid("the battery jibe", `${MARK}-battery`, "test");
  const r = await maySpeak(`${MARK}-posture`, new Date(Date.now() + (COOLDOWN_MINUTES + 5) * 60_000));
  assert(r.ok, `a different point should still be allowed: ${r.why}`);
  await wipe();
});

Deno.test("maySpeak is false once the daily LLM call budget is spent", async () => {
  await wipe();
  // Seed the row directly: 200 sequential round trips took over a minute and
  // proved nothing extra — noteSpend's accumulation has its own test.
  await db.from("llm_spend").insert({
    day: today,
    calls: DAILY_CALL_BUDGET,
    tokens_in: 1,
    tokens_out: 1,
  });
  const r = await maySpeak(`${MARK}-anything`, new Date());
  assertEquals(r.ok, false, "spent budget must silence opinions");
  await wipe();
});

// A reminder is something Ahmed asked for. Suppressing it would make the
// timer untrustworthy, which is worse than being talkative.
Deno.test("a reminder topicKey bypasses the cooldown", async () => {
  await wipe();
  await recordSaid("just said something", `${MARK}-chatter`, "test");
  const r = await maySpeak("reminder:abc123", new Date());
  assert(r.ok, `a due reminder must always speak: ${r.why}`);
  await wipe();
});

Deno.test("recordSaid makes the next maySpeak for that topic false", async () => {
  await wipe();
  const key = `${MARK}-roundtrip`;
  assert((await maySpeak(key, new Date())).ok);
  await recordSaid("said it", key, "test");
  assertEquals((await maySpeak(key, new Date())).ok, false);
  await wipe();
});

Deno.test("noteSpend accumulates into one row per day", async () => {
  await wipe();
  await noteSpend(10, 20);
  await noteSpend(5, 7);
  const { data } = await db.from("llm_spend").select("calls, tokens_in, tokens_out").eq("day", today);
  assertEquals(data?.length, 1, "one row per day, upserted");
  assertEquals(data![0].calls, 2);
  assertEquals(data![0].tokens_in, 15);
  assertEquals(data![0].tokens_out, 27);
  await wipe();
});

Deno.test("cooldown is measured from the last thing said", async () => {
  await wipe();
  await db.from("said_log").insert({
    said: "ages ago",
    topic_key: `${MARK}-old`,
    reason: "test",
    at: minutesAgo(COOLDOWN_MINUTES + 10).toISOString(),
  });
  const r = await maySpeak(`${MARK}-new`, new Date());
  assert(r.ok, `older than the cooldown should not block: ${r.why}`);
  await wipe();
});
