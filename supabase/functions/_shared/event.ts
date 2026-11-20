// Toffee speaking first.
//
// Something happens — he sits down, he has been there two hours, a reminder
// came due — and this decides whether it is worth saying anything. Silence is
// the default, and the governor is consulted BEFORE the model is called, not
// after: deciding not to speak once the reply already exists means paying for
// it anyway.

import { runTurn } from "./llm.ts";
import { buildContext, renderSystem } from "./persona.ts";
import { maySpeak, noteSpend, recordSaid } from "./governor.ts";
import { ALL_TOOLS, makeDispatcher } from "./tools/index.ts";
import type { TurnDeps, TurnRunner } from "./turn.ts";
import type { Mood } from "./types.ts";

export type EventKind =
  | "arrived"
  | "left"
  | "still_here"
  | "reminder_due"
  | "idle_check";

const KINDS = new Set<EventKind>([
  "arrived",
  "left",
  "still_here",
  "reminder_due",
  "idle_check",
]);

/** What the agent is being asked to react to, and the key the governor uses
 * to tell whether it already made this point today. */
function framing(
  kind: EventKind,
  payload: Record<string, unknown>,
): { topicKey: string; prompt: string } {
  switch (kind) {
    case "arrived":
      return {
        topicKey: "arrived",
        prompt: "Ahmed has just sat down at the desk. Greet him, in your fashion.",
      };
    case "left":
      return {
        topicKey: "left",
        prompt: "Ahmed has just left the desk. Say something about it, briefly.",
      };
    case "still_here": {
      const minutes = Number(payload.minutes ?? 0);
      // Bucketed by the hour so each hour earns its own remark rather than
      // one per poll.
      return {
        topicKey: `still_here:${Math.floor(minutes / 60)}h`,
        prompt:
          `Ahmed has been at the desk for ${minutes} minutes without a break. Remark on it.`,
      };
    }
    case "reminder_due": {
      const id = String(payload.id ?? "unknown");
      const text = String(payload.text ?? "something");
      return {
        topicKey: `reminder:${id}`,
        prompt: `A reminder has come due: "${text}". Tell him, in your fashion.`,
      };
    }
    case "idle_check":
      return {
        topicKey: `idle:${new Date().toISOString().slice(0, 13)}`,
        prompt:
          "Nothing in particular has happened. Look at his tasks and the hour, and decide whether anything is worth saying. If nothing is, say nothing of substance.",
      };
  }
}

export async function eventHandler(
  req: Request,
  deps: TurnDeps = {},
): Promise<Response> {
  let body: { kind?: unknown; payload?: unknown };
  try {
    body = await req.json();
  } catch {
    return Response.json({ error: "body must be JSON" }, { status: 400 });
  }

  const kind = String(body.kind ?? "") as EventKind;
  if (!KINDS.has(kind)) {
    return Response.json({ error: `unknown event kind: ${kind}` }, { status: 400 });
  }

  const payload = (body.payload ?? {}) as Record<string, unknown>;
  const { topicKey, prompt } = framing(kind, payload);

  // Before anything expensive.
  const verdict = await maySpeak(topicKey, new Date());
  if (!verdict.ok) {
    return Response.json({ speak: null, mood: "neutral" as Mood, why: verdict.why });
  }

  const run = deps.runner ?? (runTurn as unknown as TurnRunner);
  const { dispatch, mood } = makeDispatcher();

  const ctx = await buildContext();
  // The tablet knows its own battery; the server never does.
  if (typeof payload.battery === "number") ctx.tabletBattery = payload.battery;

  const result = await run({
    system: `${renderSystem(ctx)}\n\nWHAT JUST HAPPENED\n${prompt}`,
    history: [],
    tools: ALL_TOOLS,
    dispatch,
  });

  await noteSpend(result.spent.in, result.spent.out);

  const speak = result.reply.trim();
  if (!speak) {
    return Response.json({ speak: null, mood: mood(), why: "nothing worth saying" });
  }

  await recordSaid(speak, topicKey, kind);
  return Response.json({ speak, mood: mood() });
}
