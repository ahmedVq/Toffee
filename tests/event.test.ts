// The /event endpoint — Toffee speaking first.
//
// This is the loop that makes it a companion rather than a voice assistant:
// something happens, and it decides whether that is worth saying anything
// about. Silence is the default and has to be the easy path.

import { assert, assertEquals } from "@std/assert";
import { serviceClient } from "../supabase/functions/_shared/db.ts";
import { eventHandler } from "../supabase/functions/_shared/event.ts";
import type { TurnRunner } from "../supabase/functions/_shared/turn.ts";
import { recordSaid } from "../supabase/functions/_shared/governor.ts";

const db = serviceClient();
const today = new Date().toISOString().slice(0, 10);

async function wipe() {
  await db.from("said_log").delete().like("topic_key", "%");
  await db.from("llm_spend").delete().eq("day", today);
}

const post = (body: unknown) =>
  new Request("http://localhost/event", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });

/** Records whether it was called, so "no LLM call was made" is provable. */
function spyRunner(reply = "Oh. You're back.") {
  let calls = 0;
  let lastSystem = "";
  const runner: TurnRunner = (opts) => {
    calls++;
    lastSystem = opts.system;
    return Promise.resolve({ reply, calls: [], spent: { in: 3, out: 4 } });
  };
  return { runner, count: () => calls, system: () => lastSystem };
}

Deno.test("an 'arrived' event may produce speech", async () => {
  await wipe();
  const spy = spyRunner();
  const res = await eventHandler(post({ kind: "arrived" }), { runner: spy.runner });

  assertEquals(res.status, 200);
  const body = await res.json();
  assert(body.speak !== null, "a fresh arrival should be allowed to speak");
  assertEquals(spy.count(), 1);
  await wipe();
});

Deno.test("a second 'arrived' inside the cooldown returns speak: null", async () => {
  await wipe();
  const first = spyRunner();
  await eventHandler(post({ kind: "arrived" }), { runner: first.runner });

  const second = spyRunner();
  const res = await eventHandler(post({ kind: "arrived" }), { runner: second.runner });
  assertEquals((await res.json()).speak, null, "it must not greet him twice in a row");
  await wipe();
});

// A reminder is something Ahmed asked for. Suppressing it would make every
// timer untrustworthy.
Deno.test("a 'reminder_due' event always speaks, cooldown or not", async () => {
  await wipe();
  await recordSaid("just said something", "arrived", "test");

  const spy = spyRunner("Your fifty minutes are up.");
  const res = await eventHandler(
    post({ kind: "reminder_due", payload: { id: "abc123", text: "stand up" } }),
    { runner: spy.runner },
  );
  assert((await res.json()).speak !== null, "a due reminder must speak regardless");
  await wipe();
});

Deno.test("'still_here' after two hours produces speech about sitting too long", async () => {
  await wipe();
  const spy = spyRunner("Two hours. Impressive, in the worst way.");
  const res = await eventHandler(
    post({ kind: "still_here", payload: { minutes: 120 } }),
    { runner: spy.runner },
  );

  assert((await res.json()).speak !== null);
  assert(
    spy.system().includes("120"),
    "the model must be told how long he has been sitting there",
  );
  await wipe();
});

Deno.test("an unknown event kind returns 400", async () => {
  const spy = spyRunner();
  const res = await eventHandler(post({ kind: "nonsense" }), { runner: spy.runner });
  assertEquals(res.status, 400);
  assertEquals(spy.count(), 0, "a bad request must not reach the model");
});

// The cost dimension: refusing has to happen BEFORE spending, not after.
// A governor that decides not to speak only once the reply exists has already
// paid for it.
Deno.test("when the governor refuses, no LLM call is made", async () => {
  await wipe();
  await recordSaid("something recent", "arrived", "test");

  const spy = spyRunner();
  const res = await eventHandler(post({ kind: "arrived" }), { runner: spy.runner });

  assertEquals((await res.json()).speak, null);
  assertEquals(spy.count(), 0, "the model must not be called when the answer is silence");

  const { data } = await db.from("llm_spend").select("calls").eq("day", today);
  assertEquals(data?.length ?? 0, 0, "a refused event must not record spend");
  await wipe();
});

Deno.test("speaking records what was said, so it is not repeated", async () => {
  await wipe();
  const spy = spyRunner("You're back.");
  await eventHandler(post({ kind: "arrived" }), { runner: spy.runner });

  const { data } = await db.from("said_log").select("said, topic_key");
  assertEquals(data?.length, 1);
  assertEquals(data![0].said, "You're back.");
  await wipe();
});
