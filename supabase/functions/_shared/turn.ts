// One exchange: Ahmed says something, Toffee answers.
//
// The runner is injectable so tests can exercise the whole path — tools,
// persistence, spend accounting — without calling Gemini or spending quota.
// Production passes nothing and gets the real one.

import { serviceClient } from "./db.ts";
import { runTurn, type ToolResult, type ToolSpec } from "./llm.ts";
import { buildContext, renderSystem } from "./persona.ts";
import { noteSpend } from "./governor.ts";
import { sseResponse } from "./stream.ts";
import { ALL_TOOLS, makeDispatcher } from "./tools/index.ts";
import type { Mood, Msg } from "./types.ts";

export type TurnRunner = (opts: {
  system: string;
  history: Msg[];
  tools: ToolSpec[];
  dispatch: (name: string, args: unknown) => Promise<unknown>;
  onSentence?: (s: string) => void;
}) => Promise<{ reply: string; calls: ToolResult[]; spent: { in: number; out: number } }>;

export type TurnDeps = {
  runner?: TurnRunner;
  /** Injectable for the same reason [runner] is: so a failure here can be
   *  exercised without taking the spend table down for real. */
  spend?: (inTokens: number, outTokens: number) => Promise<void>;
  record?: (said: string, replied: string) => Promise<void>;
};

/** How much of the conversation to carry. Distilled memories do the long-term
 * work, so the transcript only needs enough for the thread of the moment. */
const HISTORY_TURNS = 12;

async function recentHistory(): Promise<Msg[]> {
  const { data } = await serviceClient()
    .from("conversation")
    .select("role, content")
    .order("at", { ascending: false })
    .limit(HISTORY_TURNS);

  return ((data ?? []) as { role: string; content: string }[])
    .reverse()
    .map((r) => ({ role: r.role === "model" ? "model" : "user", content: r.content }));
}

async function append(role: "user" | "model", content: string): Promise<void> {
  await serviceClient().from("conversation").insert({ role, content });
}

/** Both halves of one exchange, in order. Separate inserts rather than one
 *  call because the table orders by its own timestamp and a pair written
 *  together can come back the wrong way round. */
async function recordExchange(said: string, replied: string): Promise<void> {
  await append("user", said);
  await append("model", replied);
}

/**
 * Run it, and if it fails say so rather than taking the caller with it.
 *
 * Only for work whose failure must not undo work already done — here, after
 * the tablet has already spoken. The failure is not swallowed: it reaches
 * the function logs, which on this project is where a backend problem is
 * actually looked at.
 */
async function anyway(what: string, fn: () => Promise<void>): Promise<void> {
  try {
    await fn();
  } catch (e) {
    console.error(`turn: ${what} failed after the reply was spoken:`, e);
  }
}

const bad = (message: string) =>
  Response.json({ error: message }, { status: 400 });

export async function turnHandler(
  req: Request,
  deps: TurnDeps = {},
): Promise<Response> {
  let body: { text?: unknown };
  try {
    body = await req.json();
  } catch {
    return bad("body must be JSON");
  }

  const text = typeof body.text === "string" ? body.text.trim() : "";
  if (!text) return bad("text is required");

  const run = deps.runner ?? (runTurn as unknown as TurnRunner);
  const spend = deps.spend ?? noteSpend;
  const record = deps.record ?? recordExchange;
  const { dispatch, mood } = makeDispatcher();

  // Everything from here on goes out as it happens. The handler returns as
  // soon as the stream exists, so the work below finishes AFTER the response
  // has been handed back — which is the point, and is also why anything
  // checking its side effects has to read the stream to the end first.
  return sseResponse(async (emit) => {
    // What he just said is what the recall runs against, so the facts that
    // arrive are the ones this turn is about. An event has no utterance and
    // gets the seeded history alone.
    const [ctx, history] = await Promise.all([buildContext(text), recentHistory()]);

    // The face is set by a tool, and the persona tells him to set it before
    // he speaks. Emitting it the moment the tool runs is what lets the first
    // sentence be spoken in the mood it was written in, rather than in the
    // previous one.
    const watched = async (name: string, args: unknown) => {
      const result = await dispatch(name, args);
      if (name === "set_mood") emit.mood(mood());
      return result;
    };

    const result = await run({
      system: renderSystem(ctx),
      history: [...history, { role: "user", content: text }],
      tools: ALL_TOOLS,
      dispatch: watched,
      onSentence: (s) => emit.text(s),
    });

    // Bookkeeping, and by now the tablet has already said the words out
    // loud. That is what changed when this started streaming: before it, a
    // throw here produced a 500 and nothing had been spoken, so losing the
    // write lost nothing. Now a throw leaves Toffee having said something
    // the conversation has no record of — the next turn is missing an
    // exchange Ahmed remembers, and `lastSaid` is stale so the rule against
    // repeating himself is bypassed.
    //
    // So neither of these may take the other down, and neither may take the
    // `done` frame down. They are independent failures and are reported as
    // such; the function's own logs are where that goes, since nothing else
    // here can reach a person.
    //
    // Spend is recorded even when the reply was a fallback: a failed call
    // still costs, and the governor's cost gate has to see it.
    await Promise.all([
      anyway("spend accounting", () => spend(result.spent.in, result.spent.out)),
      anyway("the transcript", () => record(text, result.reply)),
    ]);

    const done: { reply: string; mood: Mood; calls: ToolResult[] } = {
      reply: result.reply,
      mood: mood(),
      calls: result.calls,
    };
    return done;
  });
}
