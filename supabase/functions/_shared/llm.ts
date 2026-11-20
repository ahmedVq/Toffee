// Gemini client: sends the conversation plus tool declarations, runs any tool calls the
// model asks for, feeds results back, and streams the reply out sentence by sentence.

// Task 3 will move Msg to types.ts.
export type Msg = { role: "user" | "model"; content: string };

export type ToolSpec = { name: string; description: string; parameters: object };
export type ToolResult = { name: string; result: unknown };

const MODEL = "gemini-2.5-flash";
const ENDPOINT =
  `https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:streamGenerateContent?alt=sse`;
const MAX_TOOL_ROUNDS = 5;

const QUOTA_FALLBACK = "My brain's out of juice for the moment. Give me a little while and ask me again.";
const EMPTY_FALLBACK = "I got a bit carried away there. Ask me again?";

// deno-lint-ignore no-explicit-any
type Part = Record<string, any>;

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
): Promise<{ status: number; parts: Part[]; usage: { in: number; out: number } }> {
  const res = await fetch(ENDPOINT, {
    method: "POST",
    headers: { "content-type": "application/json", "x-goog-api-key": key },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    await res.body?.cancel();
    return { status: res.status, parts: [], usage: { in: 0, out: 0 } };
  }
  const parts: Part[] = [];
  const usage = { in: 0, out: 0 };
  const handle = (event: string) => {
    const data = event.split(/\r?\n/).filter((l) => l.startsWith("data:")).map((l) => l.slice(5).trim()).join("");
    if (!data) return;
    let chunk;
    try {
      chunk = JSON.parse(data);
    } catch {
      return;
    }
    for (const p of chunk.candidates?.[0]?.content?.parts ?? []) {
      parts.push(p);
      if (typeof p.text === "string" && !p.thought) onText(p.text);
    }
    // usageMetadata is cumulative within a request, so keep the latest.
    if (chunk.usageMetadata) {
      usage.in = chunk.usageMetadata.promptTokenCount ?? usage.in;
      usage.out = chunk.usageMetadata.candidatesTokenCount ?? usage.out;
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
  return { status: res.status, parts, usage };
}

export async function runTurn(opts: {
  system: string;
  history: Msg[];
  tools: ToolSpec[];
  dispatch: (name: string, args: unknown) => Promise<unknown>;
  onSentence?: (s: string) => void;
}): Promise<{ reply: string; calls: ToolResult[]; spent: { in: number; out: number } }> {
  const key = Deno.env.get("GEMINI_API_KEY");
  if (!key) throw new Error("GEMINI_API_KEY is not set");

  const contents: unknown[] = opts.history.map((m) => ({ role: m.role, parts: [{ text: m.content }] }));
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
      systemInstruction: { parts: [{ text: opts.system }] },
      contents,
    };
    // After the last allowed tool round, ask for a plain answer.
    if (opts.tools.length && round < MAX_TOOL_ROUNDS) {
      body.tools = [{ functionDeclarations: opts.tools }];
    }

    const r = await streamRound(key, body, onText);
    if (r.status === 429) return fallback(QUOTA_FALLBACK);
    if (r.status !== 200) throw new Error(`Gemini request failed with status ${r.status}`);
    spent.in += r.usage.in;
    spent.out += r.usage.out;
    if (pending.trim()) opts.onSentence?.(pending.trim());
    pending = "";
    if (raw && !/\s$/.test(raw)) raw += " ";

    const fnCalls = r.parts.filter((p) => p.functionCall);
    if (!fnCalls.length || round === MAX_TOOL_ROUNDS) break;

    contents.push({ role: "model", parts: r.parts });
    const responses: Part[] = [];
    for (const p of fnCalls) {
      const { name, args } = p.functionCall;
      let response: Record<string, unknown>;
      try {
        const result = await opts.dispatch(name, args ?? {});
        calls.push({ name, result });
        response = { result };
      } catch (e) {
        // Message only: never a stack, so nothing internal reaches the model or the tablet.
        const error = e instanceof Error ? e.message : String(e);
        calls.push({ name, result: { error } });
        response = { error };
      }
      responses.push({ functionResponse: { name, response } });
    }
    contents.push({ role: "user", parts: responses });
  }

  const reply = raw.trim();
  if (!reply) return { ...fallback(EMPTY_FALLBACK), calls };
  return { reply, calls, spent };
}
