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

  await turnHandler(post({ text: `${MARK} what am I meant to be doing` }), { runner });
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
  const body = await res.json();
  assertEquals(body.mood, "smug");
  await wipe();
});

Deno.test("mood defaults to neutral when the model does not set one", async () => {
  await wipe();
  const res = await turnHandler(
    post({ text: `${MARK} hello` }),
    { runner: plainRunner("Hello.") },
  );
  assertEquals((await res.json()).mood, "neutral");
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
  await turnHandler(post({ text: `${MARK} hello` }), { runner: plainRunner("Hi.") });

  const { data } = await db.from("llm_spend").select("calls, tokens_in, tokens_out").eq("day", today);
  assertEquals(data?.length, 1);
  assertEquals(data![0].calls, 1);
  assertEquals(data![0].tokens_in, 11);
  assertEquals(data![0].tokens_out, 13);
  await wipe();
});
