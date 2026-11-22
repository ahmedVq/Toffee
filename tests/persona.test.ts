import { assert, assertEquals, assertStringIncludes } from "@std/assert";
import {
  buildContext,
  renderSystem,
  SYSTEM_PROMPT,
} from "../supabase/functions/_shared/persona.ts";
import type { Ctx } from "../supabase/functions/_shared/types.ts";

const ctx: Ctx = {
  now: "2026-11-20T02:14:00+02:00",
  present: true,
  atDeskMinutes: 137,
  tabletBattery: 6,
  openTaskCount: 4,
  lastSaid: "You have been staring at that for an hour.",
  coreFacts: [],
  relevantFacts: [],
};

Deno.test("renderSystem includes the current time, presence and open task count", () => {
  const s = renderSystem(ctx);
  assertStringIncludes(s, "2026-11-20T02:14:00+02:00");
  assertStringIncludes(s, "137");
  assertStringIncludes(s, "4");
  assert(/at (the|his) desk|present/i.test(s), "presence must be stated");
});

// The rule that keeps the cruelty funny rather than merely unpleasant: it may
// only mock what it can point at.
Deno.test("SYSTEM_PROMPT contains the evidence rule", () => {
  assertStringIncludes(SYSTEM_PROMPT, "cannot substantiate");
});

// Found live, on the first real model: every reply came back wrapped in
// markdown ("**charge the tablet properly**"). This text is spoken aloud by
// the tablet and drawn as a caption under the face — neither renders
// markdown, so the asterisks are read out or printed raw.
Deno.test("SYSTEM_PROMPT forbids markdown, because the reply is spoken", () => {
  const p = SYSTEM_PROMPT.toLowerCase();
  assert(p.includes("markdown"), "the prompt must forbid markdown by name");
  assert(
    p.includes("aloud") || p.includes("spoken"),
    "it must say why: the words are spoken, not rendered",
  );
});

// Also found live: set_mood was never called once across several turns, so
// the face sat at neutral for ever. Half the design is that the expression
// changes; the model has to be told to drive it.
Deno.test("SYSTEM_PROMPT tells it to drive its own face", () => {
  assert(
    SYSTEM_PROMPT.includes("set_mood"),
    "the prompt must name the tool that changes the face",
  );
});

Deno.test("renderSystem names the user as Ahmed", () => {
  assertStringIncludes(renderSystem(ctx), "Ahmed");
});

Deno.test("renderSystem states when nobody is at the desk", () => {
  const s = renderSystem({ ...ctx, present: false, atDeskMinutes: null });
  assert(/not (at|there)|away|empty/i.test(s), `absence must be stated: ${s}`);
});

Deno.test("buildContext reads real state without throwing", async () => {
  const live = await buildContext();
  assert(typeof live.now === "string" && live.now.length > 0);
  assert(typeof live.present === "boolean");
  assert(typeof live.openTaskCount === "number");
});

// The gap behind "give him a history of me": the memories table and its tool
// have existed since the backend was built, but nothing ever put a memory in
// front of the model. It could only remember by choosing to go and look,
// which meant it mostly did not, and every turn started from nothing.
Deno.test("core facts reach the prompt without being asked for", () => {
  const prompt = renderSystem({
    ...ctx,
    coreFacts: ["He built me to be unimpressed by him."],
  });
  assertStringIncludes(prompt, "He built me to be unimpressed by him.");
});

/**
 * Seeded history is history, and says so.
 *
 * These facts were written once, by hand, when the tablet was set up.
 * They are not observations he keeps making. "He finds me too slow, and
 * said so" was true the day it was written and is a lie the week after
 * the change that made him faster -- and until this, the model was handed
 * it as a present-tense fact in the system prompt of every single turn,
 * for ever, with no tool able to take it back.
 *
 * There is no forget tool and adding one is a bigger change than this
 * branch. What the prompt can do, at no cost, is stop asserting that the
 * list is current. A model told these were true when he was set up will
 * treat a contradiction as news rather than as an error.
 */
Deno.test("seeded facts are framed as what was true at setup, not as current truth", () => {
  const prompt = renderSystem({
    ...ctx,
    coreFacts: ["He finds me too slow, and said so."],
  });
  assertStringIncludes(prompt, "WHAT HE TOLD YOU WHEN HE SET YOU UP");
  assertStringIncludes(
    prompt,
    "may be out of date",
  );
});

// Live recall is different and must NOT carry that caveat: it is what he
// said and did, not what was typed into a seed script.
Deno.test("what he actually said is not labelled as possibly stale", () => {
  const prompt = renderSystem({
    ...ctx,
    coreFacts: [],
    relevantFacts: ["He asked me to stop nagging about the invoice."],
  });
  assertStringIncludes(prompt, "WHAT THIS REMINDS YOU OF");
  const reminds = prompt.slice(prompt.indexOf("WHAT THIS REMINDS YOU OF"));
  if (reminds.includes("may be out of date")) {
    throw new Error("live recall must not be hedged like seeded history");
  }
});
Deno.test("what he just said is what gets recalled against", async () => {
  const asked: string[] = [];
  await buildContext("did I ever finish the invoice", {
    recall: (q: string) => {
      asked.push(q);
      return Promise.resolve([]);
    },
    core: () => Promise.resolve([]),
  });
  assertEquals(asked, ["did I ever finish the invoice"]);
});

// An event -- he arrived, a reminder fired -- has no utterance to recall
// against. Core facts must still arrive, or he forgets who Ahmed is the
// moment Ahmed is not talking to him.
Deno.test("an event with nothing said still gets the core facts", async () => {
  let recalled = false;
  const built = await buildContext(undefined, {
    recall: () => {
      recalled = true;
      return Promise.resolve([]);
    },
    core: () => Promise.resolve([{ id: "1", fact: "He is Ahmed.", category: "core" }]),
  });
  assertEquals(built.coreFacts, ["He is Ahmed."]);
  assert(!recalled, "there was nothing said, so there was nothing to recall against");
});

// Review Focus 5. A seeded fact is a claim about Ahmed that outlives the
// week it was true in, so it has to be addressable -- which means it must be
// distinguishable from what the model learned on its own.
Deno.test("a core fact is kept apart from what he has since learned", () => {
  const prompt = renderSystem({
    ...ctx,
    coreFacts: ["He is Ahmed."],
    relevantFacts: ["He abandoned a pomodoro after four minutes."],
  });
  const core = prompt.indexOf("He is Ahmed.");
  const learned = prompt.indexOf("He abandoned a pomodoro");
  assert(core >= 0 && learned >= 0, "both must appear");
  assert(core !== learned, "they must not be the same line");
});

Deno.test("a prompt with no memories says so rather than inventing some", () => {
  const prompt = renderSystem({ ...ctx, coreFacts: [], relevantFacts: [] });
  assertStringIncludes(prompt, "You do not know anything about him yet");
});

// Nothing here degrades into a failed turn: the existing rule in this file is
// that a missing battery reading is not a reason to go silent, and a database
// that will not answer about memories is not either.
Deno.test("a memory lookup that throws leaves the turn standing", async () => {
  const built = await buildContext("anything", {
    recall: () => Promise.reject(new Error("pgvector is having a day")),
    core: () => Promise.reject(new Error("so is postgrest")),
  });
  assertEquals(built.coreFacts, []);
  assertEquals(built.relevantFacts, []);
});
