import { assert, assertEquals, assertStringIncludes } from "@std/assert";
import { sseResponse } from "../supabase/functions/_shared/stream.ts";

type Event = { event: string; data: Record<string, unknown> };

/** Parses the whole stream into events. Deliberately strict about the
 *  framing, because the device's parser has to agree with it exactly. */
async function readAll(body: ReadableStream<Uint8Array>): Promise<Event[]> {
  const text = await new Response(body).text();
  return text
    .split("\n\n")
    .filter((b) => b.trim().length > 0)
    .map((block) => {
      const event = /^event: (.+)$/m.exec(block)?.[1] ?? "";
      const data = /^data: (.+)$/m.exec(block)?.[1] ?? "{}";
      return { event, data: JSON.parse(data) };
    });
}

/**
 * The point of the whole task, and the one thing a buffered implementation
 * would still pass every other test with.
 *
 * The run does not finish until the gate is released, so if this read
 * resolves at all, the sentence left the server before the turn was over.
 */
Deno.test("a sentence reaches the client before the turn has finished", async () => {
  let release!: () => void;
  const gate = new Promise<void>((r) => (release = r));

  const res = sseResponse(async (emit) => {
    emit.text("One.");
    await gate;
    return { reply: "One. Two.", mood: "neutral" };
  });

  const reader = res.body!.getReader();
  const first = await reader.read();
  assertStringIncludes(new TextDecoder().decode(first.value!), "One.");

  release();
  await reader.cancel();
});

Deno.test("sentences arrive in the order they were produced", async () => {
  const res = sseResponse((emit) => {
    emit.text("One.");
    emit.text("Two.");
    emit.text("Three.");
    return Promise.resolve({ reply: "One. Two. Three.", mood: "neutral" as const });
  });
  const events = await readAll(res.body!);
  assertEquals(
    events.filter((e) => e.event === "text").map((e) => e.data.text),
    ["One.", "Two.", "Three."],
  );
});

Deno.test("the done event carries the whole reply and the final mood", async () => {
  const res = sseResponse((emit) => {
    emit.mood("smug");
    emit.text("Obviously.");
    return Promise.resolve({ reply: "Obviously.", mood: "smug" as const });
  });
  const events = await readAll(res.body!);
  const done = events.at(-1)!;
  assertEquals(done.event, "done");
  assertEquals(done.data.reply, "Obviously.");
  assertEquals(done.data.mood, "smug");
});

// The face reads mood from the stream as it goes, so a mood set before the
// first sentence must arrive before it -- otherwise the first thing he says
// is spoken in the previous mood.
Deno.test("a mood set before the first sentence arrives before it", async () => {
  const res = sseResponse((emit) => {
    emit.mood("frustrated");
    emit.text("Again?");
    return Promise.resolve({ reply: "Again?", mood: "frustrated" as const });
  });
  const events = await readAll(res.body!);
  assert(
    events.findIndex((e) => e.event === "mood") <
      events.findIndex((e) => e.event === "text"),
    "mood must precede the sentence it colours",
  );
});

/**
 * Review Focus 3. A tablet left waiting on a stream that simply stopped is a
 * tablet with its mouth open and no way to know the turn is over.
 */
Deno.test("a run that throws still closes with a done event", async () => {
  const res = sseResponse(() => Promise.reject(new Error("the model died")));
  const events = await readAll(res.body!);
  assertEquals(events.at(-1)?.event, "done");
});

Deno.test("a run that throws reports what was already said", async () => {
  const res = sseResponse(async (emit) => {
    emit.mood("sleepy");
    emit.text("Half a thought.");
    await Promise.reject(new Error("died mid-sentence"));
    return { reply: "never reached", mood: "neutral" as const };
  });
  const events = await readAll(res.body!);
  const done = events.at(-1)!;
  assertEquals(done.event, "done");
  // What he actually got out is the reply, not an empty string: the caption
  // and said_log should record the half he heard.
  assertEquals(done.data.reply, "Half a thought.");
  assertEquals(done.data.mood, "sleepy");
});

Deno.test("it is served as an event stream", () => {
  const res = sseResponse(() =>
    Promise.resolve({ reply: "", mood: "neutral" as const })
  );
  assertStringIncludes(res.headers.get("content-type") ?? "", "text/event-stream");
  assertEquals(res.headers.get("cache-control"), "no-cache");
});

// A client that walks away mid-turn must not take the server down with it:
// the tablet does exactly this every time Ahmed taps to interrupt.
Deno.test("a client that disconnects does not fail the run", async () => {
  let finished = false;
  const res = sseResponse(async (emit) => {
    emit.text("One.");
    await new Promise((r) => setTimeout(r, 10));
    emit.text("Two.");
    finished = true;
    return { reply: "One. Two.", mood: "neutral" as const };
  });

  const reader = res.body!.getReader();
  await reader.read();
  await reader.cancel();
  await new Promise((r) => setTimeout(r, 50));
  assert(finished, "the run should have completed despite the client leaving");
});
