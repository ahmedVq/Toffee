// Sending the reply out as it is written, instead of after it is finished.
//
// The agent has always produced its answer sentence by sentence -- `llm.ts`
// calls `onSentence` the moment a sentence terminator arrives -- and the
// handler then collected all of them, waited for the last one, and sent a
// single JSON object. So the tablet sat silent for the whole turn with the
// first sentence having been ready for about a second.
//
// Ahmed's first complaint after using it was "it was too slow". This is the
// larger half of the answer, and it is mostly deleting the waiting.
//
// The wire format is Server-Sent Events, chosen over a WebSocket because the
// tablet speaks HTTP to Edge Functions already, the traffic is one-directional,
// and OkHttp needs no new dependency to read it:
//
//   event: mood\ndata: {"mood":"smug"}\n\n
//   event: text\ndata: {"text":"One sentence."}\n\n
//   event: done\ndata: {"reply":"...","mood":"smug"}\n\n
//
// `done` carries the same field names the old JSON reply used, so a client
// that reads only the final event sees exactly what it saw before.

import type { Mood } from "./types.ts";

/** What a run may say while it is running. */
export type Emit = {
  /** Set the face. Sent immediately, so the sentence it colours follows it. */
  mood(m: Mood): void;
  /** One finished sentence, spoken as soon as it lands. */
  text(s: string): void;
};

/** What the turn settled on, once it is over. */
export type Done = { reply: string; mood: Mood };

/**
 * Runs `run`, streaming whatever it emits, and always closing with `done`.
 *
 * Never rejects and never leaves the stream hanging. A tablet waiting on a
 * stream that merely stopped has its mouth open with no way to know the turn
 * is over — so a run that throws still gets a `done`, carrying whatever was
 * actually said before it died rather than an empty string. The half Ahmed
 * heard is the half that belongs in the caption and in `said_log`.
 */
export function sseResponse(run: (emit: Emit) => Promise<Done>): Response {
  const encoder = new TextEncoder();

  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      // The tablet hangs up mid-turn every time Ahmed taps to interrupt,
      // and enqueueing into a cancelled stream throws. That must not fail
      // the run: the turn still has spend to record and a line to append.
      let gone = false;
      const send = (event: string, data: unknown) => {
        if (gone) return;
        try {
          controller.enqueue(
            encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`),
          );
        } catch {
          gone = true;
        }
      };

      // Kept so a failed run can still report what it managed to say.
      let said = "";
      let mood: Mood = "neutral";

      const emit: Emit = {
        mood: (m) => {
          mood = m;
          send("mood", { mood: m });
        },
        text: (s) => {
          said += said ? ` ${s}` : s;
          send("text", { text: s });
        },
      };

      // Not awaited: `start` must return so the first chunk can reach the
      // client while the run is still going. That is the whole point.
      (async () => {
        try {
          const done = await run(emit);
          send("done", done);
        } catch (e) {
          // Name only. A message could echo request details, and the key
          // must never reach a log.
          console.error("stream failed:", e instanceof Error ? e.name : "unknown");
          send("done", { reply: said, mood });
        } finally {
          if (!gone) {
            try {
              controller.close();
            } catch {
              // Already cancelled by the client; nothing left to close.
            }
          }
        }
      })();
    },
  });

  return new Response(body, {
    headers: {
      "content-type": "text/event-stream; charset=utf-8",
      "cache-control": "no-cache",
      // Supabase sits behind a proxy that will otherwise buffer the whole
      // response and hand it over at the end, which would reproduce exactly
      // the behaviour this file exists to remove.
      "x-accel-buffering": "no",
      "connection": "keep-alive",
    },
  });
}
