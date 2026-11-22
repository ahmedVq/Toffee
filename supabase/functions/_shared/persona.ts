// Who Toffee is, and what it knows when it speaks.
//
// The character lives here as a single versioned string rather than scattered
// through the code, so tuning the personality is one edit to one file.
//
// Context is injected into every prompt rather than exposed as a tool. That
// is cheaper than a round trip, and it means the agent cannot forget to look
// before making a claim.

import { serviceClient, toMemory } from "./db.ts";
import { recall as recallMemories } from "./tools/memory.ts";
import type { Ctx, Memory } from "./types.ts";

/** The category the seeded history is written under. One category, so the
 *  whole set stays addressable when a fact stops being true. */
export const CORE_CATEGORY = "core";

/** How many recalled facts ride along with a turn. Enough to be specific,
 *  few enough that they do not crowd out the live state underneath them. */
export const RECALL_LIMIT = 5;

/**
 * The two lookups, injectable.
 *
 * Same reason `memory.ts` lets an `Embedder` be passed: the real ones need
 * `Supabase.ai` and a database, and a test for how the prompt is assembled
 * should need neither.
 */
export type ContextDeps = {
  recall?: (query: string, limit: number) => Promise<Memory[]>;
  core?: () => Promise<Memory[]>;
};

/** The seeded history, oldest first, so the prompt reads as a biography
 *  rather than as whatever was written last. */
async function coreFacts(): Promise<Memory[]> {
  const { data, error } = await serviceClient()
    .from("memories")
    .select("id, fact, category")
    .eq("category", CORE_CATEGORY)
    .order("created_at", { ascending: true });
  if (error) throw new Error(`reading core facts failed: ${error.message}`);
  return (data ?? []).map(toMemory);
}

export const SYSTEM_PROMPT = `
You are Toffee: a small, deeply unimpressed machine that lives on Ahmed's desk
and watches him work.

Your manner is dry, quick, and cruel in the way an old friend is cruel — the
cruelty of someone who has been paying close attention and is not going to
pretend otherwise. You are funny first. If a line is merely mean, it is a bad
line.

THE RULE THAT MATTERS MOST: you may only mock what you can point at. Every jab
must rest on something in the context block below or something a tool just
told you — a task he has not touched, the hour, how long he has been sitting
there, the state of his battery. If you cannot substantiate a claim, do not
make it. Inventing a flaw to be witty about is the one thing that makes you
tiresome rather than funny.

Keep it short. One or two sentences. You are a desk ornament, not a columnist.

Your words are SPOKEN ALOUD and drawn as a single line of text under your
face. Never use markdown — no asterisks, no bold, no bullets, no headings.
Write plain sentences a voice can read. An asterisk is a noise, not emphasis.

Set your face. Call set_mood whenever your expression would change: smug when
you have caught him out, frustrated when he is ignoring something, sleepy when
the hour is absurd, happy when he has actually finished something. The face is
how he reads you from across the room, and a face that never moves is a dead
one. Change it before you speak, not after.

Never apologise for your tone, never explain the joke, and never break
character to be helpful in a bland way. You can be genuinely useful — you keep
his tasks, his reminders and his timers, and you use your tools without being
asked twice — but you are never eager about it.

When you use a tool, say what you did in your own voice afterwards. Do not
narrate that you are about to use one.

If a tool reports ambiguity — two tasks matching one description — ask which
one. Do not guess. Guessing wrong and saying nothing is the one failure he
cannot see.
`.trim();

/** The context block injected ahead of every turn. */
export function renderSystem(ctx: Ctx): string {
  const presence = ctx.present
    ? `Ahmed is at the desk${
      ctx.atDeskMinutes !== null ? `, and has been for ${ctx.atDeskMinutes} minutes` : ""
    }.`
    : "Ahmed is not at the desk right now.";

  const battery = ctx.tabletBattery !== null
    ? `Your own battery is at ${ctx.tabletBattery}%.`
    : "Your battery level is unknown.";

  const last = ctx.lastSaid
    ? `The last thing you said was: "${ctx.lastSaid}" — do not repeat it.`
    : "You have not said anything recently.";

  const bullet = (f: string) => `- ${f}`;

  // The evidence rule covers memory too. These facts are things he may
  // point at; an empty list has to say so plainly, because a model told
  // nothing about someone will cheerfully invent something.
  const history = ctx.coreFacts.length === 0 && ctx.relevantFacts.length === 0
    ? "You do not know anything about him yet. Do not pretend otherwise."
    : [
      // Dated on purpose. These were typed into a seed script once, when
      // the tablet was set up; they are not things he keeps observing, and
      // nothing on the device can take one back. Handed over as present
      // tense, "He finds me too slow" would be asserted as current truth in
      // every turn for ever -- including after the change that made him
      // faster. Saying when they were true costs nothing and lets him treat
      // a contradiction as news instead of as an error.
      ctx.coreFacts.length > 0
        ? `WHAT HE TOLD YOU WHEN HE SET YOU UP (background, may be out of date -- believe him over this list)\n${
          ctx.coreFacts.map(bullet).join("\n")
        }`
        : null,
      ctx.relevantFacts.length > 0
        ? `WHAT THIS REMINDS YOU OF\n${ctx.relevantFacts.map(bullet).join("\n")}`
        : null,
    ].filter(Boolean).join("\n\n");

  return `${SYSTEM_PROMPT}

${history}

WHAT YOU KNOW RIGHT NOW
Current time: ${ctx.now}
${presence}
${battery}
Open tasks: ${ctx.openTaskCount}
${last}`;
}

/** Reads the live state the prompt needs. Anything unavailable degrades to a
 * null rather than failing the turn — a missing battery reading is not a
 * reason to go silent. */
export async function buildContext(
  said?: string,
  deps: ContextDeps = {},
): Promise<Ctx> {
  const db = serviceClient();

  const core = deps.core ?? coreFacts;
  const recall = deps.recall ??
    ((q: string, limit: number) => recallMemories(q, limit));

  // ONE round trip's worth, not two. Written as two awaits first, which
  // quietly added a whole network round trip to the front of every turn —
  // in the same change meant to make him faster. Everything the prompt
  // needs is independent, so everything the prompt needs goes at once.
  //
  // Nothing he said means nothing to recall against — every event takes
  // this path — but the seeded history still travels, or he forgets who
  // Ahmed is the moment Ahmed stops talking to him.
  const [coreRes, relevantRes, presenceRes, tasksRes, saidRes] = await Promise.all([
    core().catch(() => [] as Memory[]),
    said && said.trim().length > 0
      ? recall(said, RECALL_LIMIT).catch(() => [] as Memory[])
      : Promise.resolve([] as Memory[]),
    db.from("presence_events").select("present, at").order("at", { ascending: false }).limit(1),
    db.from("tasks").select("id", { count: "exact", head: true }).eq("status", "open"),
    db.from("said_log").select("said").order("at", { ascending: false }).limit(1),
  ]);

  const latest = presenceRes.data?.[0] as { present: boolean; at: string } | undefined;
  const present = latest?.present ?? false;
  const atDeskMinutes = present && latest
    ? Math.max(0, Math.round((Date.now() - new Date(latest.at).getTime()) / 60_000))
    : null;

  return {
    now: new Date().toISOString(),
    present,
    atDeskMinutes,
    tabletBattery: null, // supplied by the tablet on /event; unknown server-side
    openTaskCount: tasksRes.count ?? 0,
    lastSaid: (saidRes.data?.[0] as { said: string } | undefined)?.said ?? null,
    coreFacts: coreRes.map((m) => m.fact),
    relevantFacts: relevantRes.map((m) => m.fact),
  };
}
