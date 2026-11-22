// The /turn endpoint — what happens when Ahmed says something.
//
// The LLM is injected, so these tests never call Gemini: no quota spent, no
// key exercised, and the model's behaviour is whatever the test needs it to
// be. The handler takes a Request and returns a Response, so status codes and
// bodies are exercised for real rather than approximated.

import { assert, assertEquals } from "@std/assert";
import { serviceClient } from "../supabase/functions/_shared/db.ts";
import { turnHandler } from "../supabase/functions/_shared/turn.ts";
import type { TurnRunner } from "../supabase/functions/_shared/turn.ts";

const db = serviceClient();
const MARK = `zz${crypto.randomUUID().slice(0, 8)}`;
const today = new Date().toISOString().slice(0, 10);

async function wipe() {
  await db.from("tasks").delete().like("title", "zz%");
  await db.from("conversation").delete().like("content", "zz%");
  await db.from("llm_spend").delete().eq("day", today);
}

const post = (body: unknown) =>
  new Request("http://localhost/turn", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });

/**
 * Reads the stream to the end and returns the `done` payload.
 *
 * Draining matters beyond reading the reply: /turn now returns as soon as
 * the stream exists, and the appending, the spend accounting and the tool
 * calls all happen while it is being consumed. A test that checked the
 * database without draining would be racing the handler.
 */
async function drain(res: Response): Promise<Record<string, unknown>> {
  const text = await new Response(res.body).text();
  // Built from a code point because editing passes keep turning the escape
  // into a real line break in the source -- the same trap MaintenanceActivity
  // already works around.
  const br = String.fromCharCode(10);
  const blocks = text.split(br + br).filter((b) => b.trim().length > 0);
  const last = blocks.at(-1) ?? "";
  assertEquals(/^event: (.+)$/m.exec(last)?.[1], "done", `stream did not end in done: ${text}`);
  return JSON.parse(/^data: (.+)$/m.exec(last)?.[1] ?? "{}");
}

/** Every sentence that went out, in order. */
async function sentences(res: Response): Promise<string[]> {
  const text = await new Response(res.body).text();
  const re = new RegExp("^event: text" + String.fromCharCode(10) + "data: (.+)$", "gm");
  return [...text.matchAll(re)].map((m) => JSON.parse(m[1]).text as string);
}

/** A runner that just replies, calling no tools. */
const plainRunner = (reply: string): TurnRunner => () =>
  Promise.resolve({ reply, calls: [], spent: { in: 11, out: 13 } });

/** A runner that invokes one tool, then replies. */
const toolRunner = (
  name: string,
  args: unknown,
  reply: string,
): TurnRunner =>
  async (opts) => {
    const result = await opts.dispatch(name, args);
    return { reply, calls: [{ name, result }], spent: { in: 5, out: 7 } };
  };

Deno.test("a turn appends both sides to conversation", async () => {
  await wipe();
  const res = await turnHandler(
    post({ text: `${MARK} are you there` }),
    { runner: plainRunner(`${MARK} unfortunately`) },
  );
  assertEquals(res.status, 200);
  await drain(res);

  const { data } = await db.from("conversation").select("role, content")
    .like("content", `${MARK}%`).order("at", { ascending: true });
  assertEquals(data?.length, 2, "both sides of the exchange must be stored");
  assertEquals(data![0].role, "user");
  assertEquals(data![1].role, "model");
  await wipe();
});

Deno.test("'add buy milk to my list' results in a task existing", async () => {
  await wipe();
  const res = await turnHandler(
    post({ text: `${MARK} add buy milk to my list` }),
    { runner: toolRunner("add_task", { title: `${MARK} buy milk` }, "Noted. Thrilling.") },
  );
  assertEquals(res.status, 200);
  await drain(res);

  const { data } = await db.from("tasks").select("title").like("title", `${MARK}%`);
  assertEquals(data?.length, 1, "the tool must actually have run");
  await wipe();
});

Deno.test("'what am I meant to be doing' calls listTasks", async () => {
  await wipe();
  await db.from("tasks").insert({ title: `${MARK} write the thing`, status: "open" });

  let sawResult: unknown = null;
  const runner: TurnRunner = async (opts) => {
    sawResult = await opts.dispatch("list_tasks", { filter: "open" });
    return { reply: "One thing. One.", calls: [], spent: { in: 1, out: 1 } };
  };

  await drain(await turnHandler(post({ text: `${MARK} what am I meant to be doing` }), { runner }));
  const tasks = sawResult as { title: string }[];
  assert(Array.isArray(tasks), "list_tasks must return an array");
  assert(
    tasks.some((t) => t.title === `${MARK} write the thing`),
    "the open task should have come back",
  );
  await wipe();
});

Deno.test("the reply carries a mood the face can render", async () => {
  await wipe();
  const res = await turnHandler(
    post({ text: `${MARK} how are you` }),
    { runner: toolRunner("set_mood", { mood: "smug" }, "Never better.") },
  );
  const body = await drain(res);
  assertEquals(body.mood, "smug");
  await wipe();
});

Deno.test("mood defaults to neutral when the model does not set one", async () => {
  await wipe();
  const res = await turnHandler(
    post({ text: `${MARK} hello` }),
    { runner: plainRunner("Hello.") },
  );
  assertEquals((await drain(res)).mood, "neutral");
  await wipe();
});

Deno.test("an empty text body is rejected with 400, not a crash", async () => {
  assertEquals((await turnHandler(post({ text: "" }), { runner: plainRunner("x") })).status, 400);
  assertEquals((await turnHandler(post({}), { runner: plainRunner("x") })).status, 400);

  const malformed = new Request("http://localhost/turn", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: "{not json",
  });
  assertEquals((await turnHandler(malformed, { runner: plainRunner("x") })).status, 400);
});

// Without this the governor's cost gate never trips and the free tier drains
// silently — the one failure nobody notices until the bill or the outage.
Deno.test("a completed turn increases today's row in llm_spend", async () => {
  await wipe();
  await drain(await turnHandler(post({ text: `${MARK} hello` }), { runner: plainRunner("Hi.") }));

  const { data } = await db.from("llm_spend").select("calls, tokens_in, tokens_out").eq("day", today);
  assertEquals(data?.length, 1);
  assertEquals(data![0].calls, 1);
  assertEquals(data![0].tokens_in, 11);
  assertEquals(data![0].tokens_out, 13);
  await wipe();
});

// The whole point of the change, asserted at the level Ahmed experiences it:
// the sentences leave the server one at a time, in order, before the turn is
// over.
Deno.test("each sentence goes out as its own event", async () => {
  await wipe();
  const runner: TurnRunner = (opts) => {
    opts.onSentence?.("One.");
    opts.onSentence?.("Two.");
    return Promise.resolve({ reply: "One. Two.", calls: [], spent: { in: 1, out: 1 } });
  };
  const res = await turnHandler(post({ text: `${MARK} say two things` }), { runner });
  assertEquals(await sentences(res), ["One.", "Two."]);
  await wipe();
});

// A bad request is not a stream. The tablet has to be able to tell a refusal
// from a turn, and a 400 carrying an event stream reads as neither.
Deno.test("a rejected request is still plain JSON", async () => {
  const res = await turnHandler(post({ text: "" }), { runner: plainRunner("x") });
  assertEquals(res.status, 400);
  assertEquals(res.headers.get("content-type")?.includes("json"), true);
});

/**
 * The words are already out of the speaker. Recording them is not optional.
 *
 * Streaming changed what a failure here means. Before it, a throw while
 * writing the exchange produced a 500 and NOTHING was said -- the tablet
 * stayed quiet and the turn simply did not happen. Now the sentences have
 * been spoken aloud by the time any of this runs, so a throw leaves Toffee
 * having said something that the conversation has no record of: the next
 * turn is missing an exchange Ahmed remembers having, and `lastSaid` is
 * stale so the rule against repeating himself is bypassed.
 *
 * The spend write is the one most likely to fail on its own -- it is a
 * different table with a unique key per day -- and it used to run first,
 * so it could take the transcript down with it.
 */
Deno.test("a spend write that fails does not lose the exchange", async () => {
  await wipe();
  const runner: TurnRunner = (o) => {
    o.onSentence?.(`${MARK} I did.`);
    return Promise.resolve({
      reply: `${MARK} I did.`,
      calls: [],
      spent: { in: 10, out: 20 },
    });
  };

  const res = await turnHandler(post({ text: `${MARK} did you` }), {
    runner,
    spend: () => Promise.reject(new Error("llm_spend is having a day")),
  });
  const done = await drain(res);

  assertEquals(done.reply, `${MARK} I did.`);
  const { data } = await db
    .from("conversation")
    .select("role, content")
    .like("content", `${MARK}%`)
    .order("at", { ascending: true });
  assertEquals(
    (data ?? []).map((r: { role: string }) => r.role),
    ["user", "model"],
    "both halves of the exchange must survive a failed spend write",
  );
  await wipe();
});

/** And the other way round: a transcript write that fails must not take the
 *  spend accounting with it, or a turn that cost money goes uncounted and
 *  the governor never sees it. */
Deno.test("a transcript write that fails does not lose the spend", async () => {
  await wipe();
  let counted: { in: number; out: number } | null = null;
  const runner: TurnRunner = (o) => {
    o.onSentence?.(`${MARK} fine.`);
    return Promise.resolve({ reply: `${MARK} fine.`, calls: [], spent: { in: 3, out: 4 } });
  };

  const res = await turnHandler(post({ text: `${MARK} well` }), {
    runner,
    record: () => Promise.reject(new Error("conversation table is having a day")),
    spend: (inTok: number, outTok: number) => {
      counted = { in: inTok, out: outTok };
      return Promise.resolve();
    },
  });
  await drain(res);

  assertEquals(counted, { in: 3, out: 4 });
  await wipe();
});
