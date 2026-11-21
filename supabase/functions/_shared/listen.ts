// POST /listen — the tablet's second pair of ears.
//
// The design doc names "whether SpeechRecognizer works at all on API 27 with
// no Google account" as the main open technical risk of the project. This is
// the answer that does not depend on the ROM: the tablet records the audio
// itself and posts it here, and the provider key stays on the server, exactly
// as it does for the LLM.
//
// Like llm.ts, this never throws at the tablet. A provider that is down must
// look like a tap nobody spoke into, not like an error — the face has no good
// way to render a stack trace and nobody is standing there to read one.

const MODEL = Deno.env.get("OPENAI_TRANSCRIBE_MODEL") || "gpt-4o-mini-transcribe";
const ENDPOINT = Deno.env.get("OPENAI_TRANSCRIBE_URL") ||
  "https://api.openai.com/v1/audio/transcriptions";

/**
 * Ten seconds of 16kHz 16-bit mono is 320KB, and Vad stops the recording at
 * ten seconds. This is a generous multiple of that: large enough never to
 * clip him, small enough that a bug cannot post a megabyte a second.
 */
export const MAX_AUDIO_BYTES = 2_000_000;

/** Comfortably inside the platform's idle limit and outside a sane upload. */
const TIMEOUT_MS = 20_000;

/**
 * Audio in, words out. Returns null for every failure there is — a provider
 * error, a timeout, a dead network, a missing key, a transcript of nothing.
 */
export async function transcribe(
  audio: Uint8Array,
  contentType: string,
): Promise<string | null> {
  const key = Deno.env.get("OPENAI_API_KEY");
  if (!key) {
    console.error("listen: no OPENAI_API_KEY");
    return null;
  }

  const form = new FormData();
  // slice() always allocates a non-shared buffer, which is what File accepts
  // and what a plain Uint8Array's ArrayBufferLike is not.
  const bytes = audio.slice().buffer as ArrayBuffer;
  form.set("file", new File([bytes], "speech.wav", { type: contentType }));
  form.set("model", MODEL);

  const abort = new AbortController();
  const timer = setTimeout(() => abort.abort(), TIMEOUT_MS);
  try {
    const res = await fetch(ENDPOINT, {
      method: "POST",
      // The key travels in a header. Never in the URL, where it would reach
      // logs and proxies.
      headers: { authorization: `Bearer ${key}` },
      body: form,
      signal: abort.signal,
    });
    if (!res.ok) {
      // Status only: a provider's error body can echo request details back.
      console.error("listen: provider answered", res.status);
      return null;
    }
    const json = await res.json() as { text?: unknown };
    const text = typeof json.text === "string" ? json.text.trim() : "";
    return text.length > 0 ? text : null;
  } catch (e) {
    // Name only, for the same reason as llm.ts: a message could carry the
    // request, and the key must never be logged.
    console.error("listen failed:", e instanceof Error ? e.name : "unknown");
    return null;
  } finally {
    clearTimeout(timer);
  }
}

export async function listenHandler(req: Request): Promise<Response> {
  const declared = Number(req.headers.get("content-length") ?? "0");
  if (declared > MAX_AUDIO_BYTES) {
    return Response.json({ error: "audio too long" }, { status: 413 });
  }

  let audio: Uint8Array;
  try {
    audio = new Uint8Array(await req.arrayBuffer());
  } catch {
    return Response.json({ error: "body must be audio" }, { status: 400 });
  }

  if (audio.byteLength === 0) {
    return Response.json({ error: "body must be audio" }, { status: 400 });
  }
  if (audio.byteLength > MAX_AUDIO_BYTES) {
    return Response.json({ error: "audio too long" }, { status: 413 });
  }

  const contentType = req.headers.get("content-type") || "audio/wav";
  const text = await transcribe(audio, contentType);

  // 200 either way. To the tablet, "the transcriber is down" and "he said
  // nothing" are the same thing, and both are silence rather than an error.
  return Response.json({ text });
}
