import { assert, assertEquals } from "@std/assert";
import {
  listenHandler,
  MAX_AUDIO_BYTES,
  transcribe,
} from "../supabase/functions/_shared/listen.ts";

Deno.env.set("OPENAI_API_KEY", "test-key-not-real");

type Req = { url: string; headers: Headers; body: FormData };

/** Stubs globalThis.fetch and records what was sent. */
async function withFetch(
  responder: () => Response,
  body: (reqs: Req[]) => Promise<void>,
) {
  const reqs: Req[] = [];
  const orig = globalThis.fetch;
  globalThis.fetch = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input.toString();
    const headers = new Headers(init?.headers);
    const form = init?.body instanceof FormData ? init.body : new FormData();
    reqs.push({ url, headers, body: form });
    return responder();
  }) as typeof fetch;
  try {
    await body(reqs);
  } finally {
    globalThis.fetch = orig;
  }
}

const ok = (text: string) => () => Response.json({ text });
const audio = () => new Uint8Array(1_000).fill(7);

const post = (bytes: Uint8Array, type = "audio/wav") =>
  new Request("https://toffee.test/listen", {
    method: "POST",
    headers: { "content-type": type },
    // slice() always allocates a non-shared buffer, which is what
    // Request wants and what the inferred ArrayBufferLike is not.
    body: bytes.slice().buffer as ArrayBuffer,
  });

Deno.test("transcribe returns the text the provider gave", async () => {
  await withFetch(ok("stand up, your spine is a rumour"), async () => {
    assertEquals(
      await transcribe(audio(), "audio/wav"),
      "stand up, your spine is a rumour",
    );
  });
});

Deno.test("transcribe returns null rather than throwing on a provider error", async () => {
  await withFetch(() => new Response("nope", { status: 500 }), async () => {
    assertEquals(await transcribe(audio(), "audio/wav"), null);
  });
});

Deno.test("transcribe returns null rather than throwing when the network dies", async () => {
  const orig = globalThis.fetch;
  globalThis.fetch = (() => Promise.reject(new TypeError("dns"))) as typeof fetch;
  try {
    assertEquals(await transcribe(audio(), "audio/wav"), null);
  } finally {
    globalThis.fetch = orig;
  }
});

// The whole security story of this device is that the key never leaves the
// server, and never reaches a log or a proxy on the way.
Deno.test("transcribe keeps the key in a header, never in the URL", async () => {
  await withFetch(ok("x"), async (reqs) => {
    await transcribe(audio(), "audio/wav");
    const [req] = reqs;
    assert(!req.url.includes("test-key-not-real"), `key leaked into ${req.url}`);
    assertEquals(req.headers.get("authorization"), "Bearer test-key-not-real");
  });
});

Deno.test("transcribe sends the audio as a file with a model", async () => {
  await withFetch(ok("x"), async (reqs) => {
    await transcribe(audio(), "audio/wav");
    const form = reqs[0].body;
    assert(form.get("file") instanceof File, "the audio must be a file part");
    assert(String(form.get("model")).length > 0, "a model must be named");
  });
});

Deno.test("the handler refuses an empty body", async () => {
  const res = await listenHandler(post(new Uint8Array(0)));
  assertEquals(res.status, 400);
});

Deno.test("the handler refuses a body past the cap", async () => {
  const res = await listenHandler(post(new Uint8Array(MAX_AUDIO_BYTES + 1)));
  assertEquals(res.status, 413);
});

// The tablet must never be shown an error. A provider that is down is the
// same to it as a tap nobody spoke into.
Deno.test("a provider failure still answers 200 with text null", async () => {
  await withFetch(() => new Response("down", { status: 503 }), async () => {
    const res = await listenHandler(post(audio()));
    assertEquals(res.status, 200);
    assertEquals(await res.json(), { text: null });
  });
});

Deno.test("a good request answers 200 with the transcript", async () => {
  await withFetch(ok("what have I got left today"), async () => {
    const res = await listenHandler(post(audio()));
    assertEquals(res.status, 200);
    assertEquals(await res.json(), { text: "what have I got left today" });
  });
});

Deno.test("a blank transcript is reported as nothing heard", async () => {
  await withFetch(ok("   "), async () => {
    const res = await listenHandler(post(audio()));
    assertEquals(await res.json(), { text: null });
  });
});
