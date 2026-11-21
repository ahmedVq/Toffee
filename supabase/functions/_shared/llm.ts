// OpenAI client: sends the conversation plus tool declarations, runs any tool calls the
// model asks for, feeds results back, and streams the reply out sentence by sentence.
//
// Was Gemini until the key ran out of credit. The shape of this file did not
// change with the provider — the sentence streaming, the deadline and the
// never-reject contract are about comic timing and about a tablet on a desk,
// not about whose API is behind it.

export type { Msg } from "./types.ts";
import type { Msg } from "./types.ts";

export type ToolSpec = {
  name: string;
  description: string;
  parameters: object;
};
export type ToolResult = { name: string; result: unknown };

/** Comfortably inside Supabase's 150s idle limit, and far outside a
 * conversational pause. */
export const DEFAULT_TIMEOUT_MS = 25_000;

/** The cheapest tier that still calls tools. Overridable by env because model
 * availability has already shifted under us once — that is evidence, not
 * speculation. */
const MODEL = Deno.env.get("OPENAI_MODEL") || "gpt-5.4-nano";
const ENDPOINT = Deno.env.get("OPENAI_BASE_URL") ||
  "https://api.openai.com/v1/chat/completions";
const MAX_TOOL_ROUNDS = 5;

const FALLBACK =
  "My brain's out of juice for the moment. Give me a little while and ask me again.";
const EMPTY_FALLBACK = "I got a bit carried away there. Ask me again?";

/** One tool call, assembled from however many deltas it arrived in. */
type PendingCall = { id: string; name: string; args: string };

// Splits off complete sentences. A terminator followed by whitespace ends a sentence; a
// terminator at the very end of the buffer does too (so the last sentence of a chunk is
// spoken now, not when the next chunk arrives) unless it is a "." after a digit ("3.").
function takeSentences(buf: string): { sentences: string[]; rest: string } {
  const sentences: string[] = [];
  const re = /[.!?]+["')\]]*(\s+|$)/g;
  let start = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(buf)) !== null) {
    const atEnd = m[1] === "";
    const end = m.index + m[0].length;
    if (atEnd && /\d\.$/.test(buf.slice(start, end))) break;
    const s = buf.slice(start, end).trim();
    if (s) sentences.push(s);
    start = end;
  }
  return { sentences, rest: buf.slice(start) };
}

async function streamRound(
  key: string,
  body: unknown,
  onText: (t: string) => void,
  timeoutMs: number,
): Promise<
  { status: number; calls: PendingCall[]; usage: { in: number; out: number } }
> {
  // Without a deadline a slow model rides all the way to the platform's own
  // idle timeout, and the tablet gets nothing at all. Giving up early and
  // saying something is always better than going quiet.
  const abort = new AbortController();
  const timer = setTimeout(() => abort.abort(), timeoutMs);
  let res: Response;
  try {
    res = await fetch(ENDPOINT, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        // The key travels in a header. Never in the URL, where it would
        // reach logs and proxies.
        authorization: `Bearer ${key}`,
      },
      body: JSON.stringify(body),
      signal: abort.signal,
    });
  } finally {
    clearTimeout(timer);
  }
  if (!res.ok) {
    await res.body?.cancel();
    return { status: res.status, calls: [], usage: { in: 0, out: 0 } };
  }

  // Tool calls arrive spread across deltas, keyed by index: the first carries
  // the id and name, later ones append fragments of the JSON arguments.
  const byIndex = new Map<number, PendingCall>();
  const usage = { in: 0, out: 0 };

  const handle = (event: string) => {
    const data = event.split(/\r?\n/).filter((l) => l.startsWith("data:")).map((
      l,
    ) => l.slice(5).trim()).join("");
    if (!data || data === "[DONE]") return;
    let chunk;
    try {
      chunk = JSON.parse(data);
    } catch {
      return;
    }
    const delta = chunk.choices?.[0]?.delta;
    if (typeof delta?.content === "string" && delta.content) onText(delta.content);
    for (const tc of delta?.tool_calls ?? []) {
      const i = tc.index ?? 0;
      const acc = byIndex.get(i) ?? { id: "", name: "", args: "" };
      if (tc.id) acc.id = tc.id;
      if (tc.function?.name) acc.name = tc.function.name;
      if (typeof tc.function?.arguments === "string") acc.args += tc.function.arguments;
      byIndex.set(i, acc);
    }
    // The usage chunk arrives last and is absolute, not cumulative.
    if (chunk.usage) {
      usage.in = chunk.usage.prompt_tokens ?? usage.in;
      usage.out = chunk.usage.completion_tokens ?? usage.out;
    }
  };

  const reader = res.body!.pipeThrough(new TextDecoderStream()).getReader();
  let buf = "";
  for (;;) {
    const { done, value } = await reader.read();
    if (value) buf += value;
    const events = buf.split(/\r?\n\r?\n/);
    buf = done ? "" : events.pop()!;
    for (const e of events) handle(e);
    if (done) break;
  }
  return {
    status: res.status,
    calls: [...byIndex.entries()].sort((a, b) => a[0] - b[0]).map(([, c]) => c),
    usage,
  };
}

type TurnOpts = {
  system: string;
  history: Msg[];
  tools: ToolSpec[];
  dispatch: (name: string, args: unknown) => Promise<unknown>;
  onSentence?: (s: string) => void;
  /** Per-request deadline. Defaults to DEFAULT_TIMEOUT_MS. */
  timeoutMs?: number;
};
type TurnResult = {
  reply: string;
  calls: ToolResult[];
  spent: { in: number; out: number };
};

// Never rejects: any failure to get a usable reply (quota, 4xx/5xx, network, missing key)
// resolves with a spoken in-character line, so no error text or stack reaches the tablet.
export async function runTurn(opts: TurnOpts): Promise<TurnResult> {
  try {
    return await converse(opts);
  } catch (e) {
    // Log the error name only: messages could echo request details, and the key must never be logged.
    console.error("llm turn failed:", e instanceof Error ? e.name : "unknown");
    opts.onSentence?.(FALLBACK);
    return { reply: FALLBACK, calls: [], spent: { in: 0, out: 0 } };
  }
}

async function converse(opts: TurnOpts): Promise<TurnResult> {
  const key = Deno.env.get("OPENAI_API_KEY");
  if (!key) throw new Error("missing key");

  // The conversation table stores Gemini's vocabulary ('user' | 'model');
  // OpenAI says 'assistant'. Translating here keeps the schema, and the
  // history already in it, untouched.
  const messages: unknown[] = [
    { role: "system", content: opts.system },
    ...opts.history.map((m) => ({
      role: m.role === "model" ? "assistant" : "user",
      content: m.content,
    })),
  ];

  const calls: ToolResult[] = [];
  const spent = { in: 0, out: 0 };
  let pending = "";
  let raw = "";

  const fallback = (line: string) => {
    opts.onSentence?.(line);
    return { reply: line, calls: [] as ToolResult[], spent };
  };
  const onText = (t: string) => {
    raw += t;
    pending += t;
    const { sentences, rest } = takeSentences(pending);
    pending = rest;
    for (const s of sentences) opts.onSentence?.(s);
  };

  for (let round = 0; round <= MAX_TOOL_ROUNDS; round++) {
    const body: Record<string, unknown> = {
      model: MODEL,
      stream: true,
      stream_options: { include_usage: true },
      messages,
    };
    // After the last allowed tool round, ask for a plain answer.
    if (opts.tools.length && round < MAX_TOOL_ROUNDS) {
      body.tools = opts.tools.map((t) => ({
        type: "function",
        function: { name: t.name, description: t.description, parameters: t.parameters },
      }));
    }

    const r = await streamRound(key, body, onText, opts.timeoutMs ?? DEFAULT_TIMEOUT_MS);
    if (r.status !== 200) {
      throw new Error(`OpenAI request failed with status ${r.status}`);
    }
    spent.in += r.usage.in;
    spent.out += r.usage.out;
    if (pending.trim()) opts.onSentence?.(pending.trim());
    pending = "";
    if (raw && !/\s$/.test(raw)) raw += " ";

    if (!r.calls.length || round === MAX_TOOL_ROUNDS) break;

    messages.push({
      role: "assistant",
      tool_calls: r.calls.map((c) => ({
        id: c.id,
        type: "function",
        function: { name: c.name, arguments: c.args || "{}" },
      })),
    });

    for (const c of r.calls) {
      let response: Record<string, unknown>;
      try {
        // Arguments arrive as a JSON string assembled from fragments; a
        // malformed one is the model's mistake to hear about, not a crash.
        let args: unknown = {};
        try {
          args = c.args ? JSON.parse(c.args) : {};
        } catch {
          throw new Error("arguments were not valid JSON");
        }
        const result = await opts.dispatch(c.name, args);
        calls.push({ name: c.name, result });
        response = { result };
      } catch (e) {
        // Message only: never a stack, so nothing internal reaches the model or the tablet.
        const error = e instanceof Error ? e.message : String(e);
        calls.push({ name: c.name, result: { error } });
        response = { error };
      }
      messages.push({
        role: "tool",
        tool_call_id: c.id,
        content: JSON.stringify(response),
      });
    }
  }

  const reply = raw.trim();
  if (!reply) return { ...fallback(EMPTY_FALLBACK), calls };
  return { reply, calls, spent };
}
