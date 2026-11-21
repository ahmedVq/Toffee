import { assert, assertStringIncludes } from "@std/assert";
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
