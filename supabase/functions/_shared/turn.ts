// One exchange: Ahmed says something, Toffee answers.
//
// The runner is injectable so tests can exercise the whole path — tools,
// persistence, spend accounting — without calling Gemini or spending quota.
// Production passes nothing and gets the real one.

import { serviceClient } from "./db.ts";
import { runTurn, type ToolResult, type ToolSpec } from "./llm.ts";
import { buildContext, renderSystem } from "./persona.ts";
import { noteSpend } from "./governor.ts";
import { ALL_TOOLS, makeDispatcher } from "./tools/index.ts";
import type { Mood, Msg } from "./types.ts";

export type TurnRunner = (opts: {
  system: string;
  history: Msg[];
  tools: ToolSpec[];
  dispatch: (name: string, args: unknown) => Promise<unknown>;
  onSentence?: (s: string) => void;
}) => Promise<{ reply: string; calls: ToolResult[]; spent: { in: number; out: number } }>;

export type TurnDeps = { runner?: TurnRunner };

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
  const { dispatch, mood } = makeDispatcher();

  const [ctx, history] = await Promise.all([buildContext(), recentHistory()]);

  const result = await run({
    system: renderSystem(ctx),
    history: [...history, { role: "user", content: text }],
    tools: ALL_TOOLS,
    dispatch,
  });

  // Spend is recorded even when the reply was a fallback: a failed call still
  // costs, and the governor's cost gate has to see it.
  await noteSpend(result.spent.in, result.spent.out);

  await append("user", text);
  await append("model", result.reply);

  const payload: { reply: string; mood: Mood; calls: ToolResult[] } = {
    reply: result.reply,
    mood: mood(),
    calls: result.calls,
  };
  return Response.json(payload);
}
