import { assert, assertEquals } from "@std/assert";
import { runTurn, type ToolSpec } from "../supabase/functions/_shared/llm.ts";

Deno.env.set("OPENAI_API_KEY", "test-key-not-real");

type Chunk = Record<string, unknown>;
type Message = Record<string, unknown>;
type ReqBody = {
  model?: string;
  messages: Message[];
  tools?: { type: string; function: ToolSpec }[];
  stream?: boolean;
};
type Req = { url: string; headers: Headers; body: ReqBody };

const enc = new TextEncoder();
const sse = (chunk: Chunk) =>
  enc.encode(`data: ${JSON.stringify(chunk)}\r\n\r\n`);

const textChunk = (text: string): Chunk => ({
  choices: [{ index: 0, delta: { role: "assistant", content: text } }],
});

/** `args` is the raw arguments string, exactly as the wire carries it. */
const callChunk = (name: string, args: string, id = `call_${name}`): Chunk => ({
  choices: [{
    index: 0,
    delta: {
      role: "assistant",
      tool_calls: [{ index: 0, id, type: "function", function: { name, arguments: args } }],
    },
  }],
});

/** Sent last, with empty choices, when stream_options.include_usage is set. */
const usageChunk = (): Chunk => ({
  choices: [],
  usage: { prompt_tokens: 10, completion_tokens: 5 },
});

const sseResponse = (chunks: Chunk[]) =>
  new Response(
    new ReadableStream({
      start(c) {
        for (const ch of chunks) c.enqueue(sse(ch));
        c.enqueue(sse(usageChunk()));
        c.enqueue(enc.encode("data: [DONE]\r\n\r\n"));
        c.close();
      },
    }),
    { status: 200, headers: { "content-type": "text/event-stream" } },
  );

// Stubs globalThis.fetch with scripted responses; records parsed request bodies.
async function withFetch(
  responder: (n: number) => Response,
  body: (reqs: Req[]) => Promise<void>,
) {
  const reqs: Req[] = [];
  const orig = globalThis.fetch;
  globalThis.fetch = ((input: Request | URL | string, init?: RequestInit) => {
    reqs.push({
      url: String(input),
      headers: new Headers(init?.headers),
      body: JSON.parse(String(init?.body)),
    });
    return Promise.resolve(responder(reqs.length - 1));
  }) as typeof fetch;
  try {
    await body(reqs);
  } finally {
    globalThis.fetch = orig;
  }
}

const weather: ToolSpec = {
  name: "get_weather",
  description: "Get the weather",
  parameters: { type: "object", properties: { city: { type: "string" } } },
};
const base = {
  system: "You are Toffee.",
  history: [{ role: "user" as const, content: "hi" }],
  tools: [weather],
};

Deno.test("sends tool declarations in the request body", async () => {
  await withFetch(() => sseResponse([textChunk("Hello.")]), async (reqs) => {
    await runTurn({ ...base, dispatch: () => Promise.resolve(null) });
    const b = reqs[0].body;
    assertEquals(b.tools, [{ type: "function", function: weather }]);
    assertEquals(b.messages[0], { role: "system", content: "You are Toffee." });
    assertEquals(b.messages[1], { role: "user", content: "hi" });
    assertEquals(b.stream, true);
    assert(typeof b.model === "string" && b.model.length > 0);
    assert(reqs[0].url.includes("/chat/completions"));
    // key travels in a header, never in the URL
    assert(!reqs[0].url.includes("test-key-not-real"));
    assertEquals(reqs[0].headers.get("authorization"), "Bearer test-key-not-real");
  });
});

// The conversation table stores Gemini's vocabulary ('user' | 'model') and a
// CHECK constraint enforces it. OpenAI says 'assistant'. Sending 'model'
// would be a 400 on every turn that has any history at all — which is every
// turn after the first.
Deno.test("stored history is translated from model to assistant", async () => {
  await withFetch(() => sseResponse([textChunk("Fine.")]), async (reqs) => {
    await runTurn({
      ...base,
      history: [
        { role: "user", content: "hi" },
        { role: "model", content: "what" },
        { role: "user", content: "again" },
      ],
      dispatch: () => Promise.resolve(null),
    });
    assertEquals(reqs[0].body.messages.map((m) => m.role), [
      "system",
      "user",
      "assistant",
      "user",
    ]);
  });
});

Deno.test("executes a functionCall from the model and sends the result back", async () => {
  const seen: unknown[] = [];
  await withFetch(
    (n) =>
      n === 0
        ? sseResponse([callChunk("get_weather", '{"city":"Cairo"}')])
        : sseResponse([textChunk("It is sunny.")]),
    async (reqs) => {
      const out = await runTurn({
        ...base,
        dispatch: (name, args) => {
          seen.push([name, args]);
          return Promise.resolve({ temp: 31 });
        },
      });
      assertEquals(seen, [["get_weather", { city: "Cairo" }]]);
      assertEquals(out.reply, "It is sunny.");
      assertEquals(out.calls, [{ name: "get_weather", result: { temp: 31 } }]);
      assertEquals(out.spent, { in: 20, out: 10 });

      const messages = reqs[1].body.messages;
      assertEquals(messages.at(-2), {
        role: "assistant",
        tool_calls: [{
          id: "call_get_weather",
          type: "function",
          function: { name: "get_weather", arguments: '{"city":"Cairo"}' },
        }],
      });
      assertEquals(messages.at(-1), {
        role: "tool",
        tool_call_id: "call_get_weather",
        content: JSON.stringify({ result: { temp: 31 } }),
      });
    },
  );
});

// Arguments arrive as fragments of a JSON string across several deltas. If
// they are not reassembled in order the tool is called with nonsense.
Deno.test("tool arguments split across deltas are reassembled", async () => {
  const seen: unknown[] = [];
  await withFetch(
    (n) =>
      n === 0
        ? sseResponse([
          { choices: [{ index: 0, delta: { tool_calls: [{ index: 0, id: "c1", function: { name: "get_weather", arguments: '{"ci' } }] } }] },
          { choices: [{ index: 0, delta: { tool_calls: [{ index: 0, function: { arguments: 'ty":"Ca' } }] } }] },
          { choices: [{ index: 0, delta: { tool_calls: [{ index: 0, function: { arguments: 'iro"}' } }] } }] },
        ])
        : sseResponse([textChunk("Sunny.")]),
    async () => {
      const out = await runTurn({
        ...base,
        dispatch: (_n, a) => {
          seen.push(a);
          return Promise.resolve("ok");
        },
      });
      assertEquals(seen, [{ city: "Cairo" }]);
      assertEquals(out.reply, "Sunny.");
    },
  );
});

Deno.test("loops when the model calls a second tool after the first result", async () => {
  const script = [
    () => sseResponse([callChunk("get_weather", '{"city":"A"}')]),
    () => sseResponse([callChunk("get_weather", '{"city":"B"}')]),
    () => sseResponse([textChunk("Done.")]),
  ];
  await withFetch((n) => script[n](), async (reqs) => {
    const out = await runTurn({
      ...base,
      dispatch: (_n, a) => Promise.resolve(a),
    });
    assertEquals(reqs.length, 3);
    assertEquals(out.calls.map((c) => c.result), [{ city: "A" }, { city: "B" }]);
    assertEquals(out.reply, "Done.");
  });
});

Deno.test("stops after 5 tool rounds rather than looping forever", async () => {
  await withFetch(
    () => sseResponse([callChunk("get_weather", "{}")]),
    async (reqs) => {
      const out = await runTurn({
        ...base,
        dispatch: () => Promise.resolve("x"),
      });
      assertEquals(out.calls.length, 5);
      assertEquals(reqs.length, 6); // 5 tool rounds + one final tool-less request
      assertEquals(reqs[5].body.tools, undefined);
      assert(out.reply.length > 0);
    },
  );
});

Deno.test("onSentence fires per sentence while streaming", async () => {
  const fired: string[] = [];
  const atSecondChunk: string[][] = [];
  const body = new ReadableStream({
    async start(c) {
      c.enqueue(
        sse(textChunk("Why did the robot cross the road? Because it was pro")),
      );
      await new Promise((r) => setTimeout(r, 20));
      atSecondChunk.push([...fired]); // snapshot before the rest of the reply exists
      c.enqueue(sse(textChunk("grammed to! Ha. And it is")));
      c.enqueue(sse(textChunk(" all")));
      c.close();
    },
  });
  await withFetch(() => new Response(body, { status: 200 }), async () => {
    const out = await runTurn({
      ...base,
      dispatch: () => Promise.resolve(null),
      onSentence: (s) => fired.push(s),
    });
    assertEquals(atSecondChunk[0], ["Why did the robot cross the road?"]);
    assertEquals(fired, [
      "Why did the robot cross the road?",
      "Because it was programmed to!",
      "Ha.",
      "And it is all",
    ]);
    assertEquals(
      out.reply,
      "Why did the robot cross the road? Because it was programmed to! Ha. And it is all",
    );
  });
});

Deno.test("an unknown tool name returns an error to the model and the turn continues", async () => {
  await withFetch(
    (n) =>
      n === 0
        ? sseResponse([callChunk("nope", "{}")])
        : sseResponse([textChunk("Sorry, cannot do that.")]),
    async (reqs) => {
      const out = await runTurn({
        ...base,
        dispatch: (name) => Promise.reject(new Error(`unknown tool ${name}`)),
      });
      assertEquals(out.reply, "Sorry, cannot do that.");
      const last = reqs[1].body.messages.at(-1)!;
      assertEquals(last.role, "tool");
      assertEquals(last.tool_call_id, "call_nope");
      assertEquals(JSON.parse(String(last.content)), { error: "unknown tool nope" });
    },
  );
});

Deno.test("malformed tool arguments do not throw out of runTurn", async () => {
  // All four are things the wire can actually carry: a truncated object, an
  // empty string, and valid JSON that is not an object.
  for (const bad of ["{not json", "", "null", "42"]) {
    await withFetch(
      (n) =>
        n === 0
          ? sseResponse([callChunk("get_weather", bad)])
          : sseResponse([textChunk("Hmm.")]),
      async () => {
        const out = await runTurn({
          ...base,
          dispatch: (_n, a) => {
            if (typeof a !== "object" || a === null) {
              throw new Error("bad args");
            }
            return Promise.resolve("ok");
          },
        });
        assertEquals(out.reply, "Hmm.");
      },
    );
  }
});

Deno.test("a 429 from the API resolves with a spoken fallback, not a rejection", async () => {
  const quiet = console.error;
  console.error = () => {};
  try {
    await withFetch(
      () =>
        new Response(
          JSON.stringify({
            error: { code: 429, type: "rate_limit_exceeded", message: "quota" },
          }),
          { status: 429 },
        ),
      async () => {
        const spoken: string[] = [];
        const out = await runTurn({
          ...base,
          dispatch: () => Promise.resolve(null),
          onSentence: (s) => spoken.push(s),
        });
        assert(out.reply.length > 0);
        assertEquals(out.calls, []);
        assert(!out.reply.includes("test-key-not-real"));
        assertEquals(spoken.join(" "), out.reply);
      },
    );
  } finally {
    console.error = quiet;
  }
});

Deno.test("a 500 from the API resolves with a spoken fallback, not a rejection", async () => {
  const quiet = console.error;
  console.error = () => {};
  try {
    const run = async () => {
      const spoken: string[] = [];
      const out = await runTurn({
        ...base,
        dispatch: () => Promise.resolve(null),
        onSentence: (s) => spoken.push(s),
      });
      assert(out.reply.length > 0);
      assertEquals(out.calls, []);
      assert(!out.reply.includes("test-key-not-real"));
      assert(!out.reply.includes("SECRET-BODY"));
      assertEquals(spoken.join(" "), out.reply);
    };
    await withFetch(
      () => new Response("SECRET-BODY stack trace", { status: 500 }),
      run,
    );
    // network failure
    const orig = globalThis.fetch;
    globalThis.fetch = (() =>
      Promise.reject(new TypeError("boom test-key-not-real"))) as typeof fetch;
    try {
      await run();
    } finally {
      globalThis.fetch = orig;
    }
    // missing key
    Deno.env.delete("OPENAI_API_KEY");
    try {
      await run();
    } finally {
      Deno.env.set("OPENAI_API_KEY", "test-key-not-real");
    }
  } finally {
    console.error = quiet;
  }
});

// Found in production under the previous provider: the model intermittently
// hung, and with no deadline the request rode all the way to Supabase's 150s
// idle timeout. The tablet got nothing at all — worse than a joke that lands
// badly, because a companion that goes silent for two and a half minutes
// looks broken. The provider changed; the hazard did not.
Deno.test("a hanging model resolves with the fallback instead of waiting forever", async () => {
  const original = globalThis.fetch;
  // A faithful stall: never resolves, but honours abort the way a real
  // fetch does. A stub that ignores the signal cannot test a deadline.
  globalThis.fetch = ((_url: unknown, init?: { signal?: AbortSignal }) =>
    new Promise((_resolve, reject) => {
      init?.signal?.addEventListener(
        "abort",
        () => reject(new DOMException("Aborted", "AbortError")),
      );
    })) as unknown as typeof fetch;
  const quiet = console.error;
  console.error = () => {};
  try {
    const started = Date.now();
    const out = await runTurn({
      system: "s",
      history: [{ role: "user", content: "hi" }],
      tools: [],
      dispatch: () => Promise.resolve({}),
      timeoutMs: 300,
    });
    const elapsed = Date.now() - started;
    assert(out.reply.length > 0, "it must still say something");
    assert(elapsed < 5_000, `waited ${elapsed}ms; the deadline did not fire`);
  } finally {
    globalThis.fetch = original;
    console.error = quiet;
  }
});
