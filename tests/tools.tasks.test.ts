// Task tools, against the live database.
//
// Every title is prefixed with a per-run marker so these tests cannot see or
// disturb real tasks, and each test cleans up what it made.

import { assert, assertEquals } from "@std/assert";
import { serviceClient } from "../supabase/functions/_shared/db.ts";
import {
  addTask,
  type AmbiguousMatch,
  completeTask,
  deleteTask,
  listTasks,
  uncompleteTask,
} from "../supabase/functions/_shared/tools/tasks.ts";

const MARK = `zz${crypto.randomUUID().slice(0, 8)}`;
const db = serviceClient();

const title = (s: string) => `${MARK} ${s}`;

async function wipe() {
  // "zz" prefixes every test marker, so this also clears a previous run's
  // leftovers — which would otherwise compete in the matching tests.
  await db.from("tasks").delete().like("title", "zz%");
}

/** Insert directly so created_at can be backdated — addTask cannot do that. */
async function seedAged(name: string, daysOld: number, status = "open") {
  const at = new Date(Date.now() - daysOld * 86_400_000).toISOString();
  const { data, error } = await db
    .from("tasks")
    .insert({ title: title(name), status, created_at: at })
    .select()
    .single();
  if (error) throw new Error(`seed failed: ${error.message}`);
  return data;
}

const isAmbiguous = (r: unknown): r is AmbiguousMatch =>
  !!r && typeof r === "object" && "ambiguous" in r;

Deno.test("addTask stores and returns the task", async () => {
  await wipe();
  const t = await addTask(title("buy milk"));
  assertEquals(t.title, title("buy milk"));
  assertEquals(t.status, "open");

  const { data } = await db.from("tasks").select("title").eq("id", t.id);
  assertEquals(data?.length, 1, "task was not actually stored");
  await wipe();
});

Deno.test("listTasks('open') excludes done tasks", async () => {
  await wipe();
  await seedAged("still open", 0);
  await seedAged("already finished", 0, "done");

  const open = (await listTasks("open")).filter((t) => t.title.startsWith(MARK));
  assertEquals(open.length, 1);
  assertEquals(open[0].title, title("still open"));
  await wipe();
});

Deno.test("listTasks('stale') returns only open tasks older than three days", async () => {
  await wipe();
  await seedAged("ancient", 5);
  await seedAged("fresh", 1);
  await seedAged("ancient but done", 5, "done");

  const stale = (await listTasks("stale")).filter((t) => t.title.startsWith(MARK));
  assertEquals(stale.length, 1, "stale should exclude fresh and done");
  assertEquals(stale[0].title, title("ancient"));
  await wipe();
});

Deno.test("completeTask matches loosely: 'the deck thing' finds 'Fix the deck'", async () => {
  await wipe();
  await seedAged("Fix the deck", 0);

  const r = await completeTask(`${MARK} the deck thing`);
  assert(!isAmbiguous(r) && r !== null, "expected a single match");
  assertEquals(r.status, "done");
  await wipe();
});

Deno.test("completeTask is idempotent — completing a done task is not an error", async () => {
  await wipe();
  await seedAged("water the plants", 0);

  const first = await completeTask(title("water the plants"));
  assert(!isAmbiguous(first) && first !== null);
  const second = await completeTask(title("water the plants"));
  assert(!isAmbiguous(second) && second !== null, "second call should not fail");
  assertEquals(second.status, "done");
  await wipe();
});

Deno.test("completeTask on an unknown title returns null, not a throw", async () => {
  await wipe();
  assertEquals(await completeTask(`${MARK} nothing like this exists`), null);
});

Deno.test("uncompleteTask reopens a done task", async () => {
  await wipe();
  await seedAged("submit the form", 0, "done");

  const r = await uncompleteTask(title("submit the form"));
  assert(!isAmbiguous(r) && r !== null);
  assertEquals(r.status, "open");
  await wipe();
});

Deno.test("deleteTask removes a matched task and returns false for no match", async () => {
  await wipe();
  await seedAged("throw this away", 0);

  assertEquals(await deleteTask(title("throw this away")), true);
  const { data } = await db.from("tasks").select("id").like("title", `${MARK}%`);
  assertEquals(data?.length, 0, "task should be gone");

  assertEquals(await deleteTask(`${MARK} never existed`), false);
});

// Review Focus: an ambiguous match must refuse rather than guess, because a
// wrong guess silently ticks off the wrong task and the user never finds out.
Deno.test("completeTask refuses when two tasks match equally well", async () => {
  await wipe();
  await seedAged("Fix the deck lighting", 0);
  await seedAged("Fix the deck railing", 0);

  const r = await completeTask(`${MARK} fix the deck`);
  assert(isAmbiguous(r), `expected ambiguity, got ${JSON.stringify(r)}`);
  assertEquals(r.candidates.length, 2);

  const { data } = await db
    .from("tasks")
    .select("status")
    .like("title", `${MARK}%`);
  assert(
    data?.every((row) => row.status === "open"),
    "an ambiguous match must change nothing",
  );
  await wipe();
});

// The test above uses two titles that score *identically*, so it passes even
// with the tie band set to zero — it proves exact ties, not near ones. The
// band exists for near ties, which is where a silent wrong guess actually
// happens. This pins that: 8 query tokens, one title matching all 8 and one
// matching 7, a 12.5% gap that must still refuse.
Deno.test("completeTask refuses a near-tie, not just an exact tie", async () => {
  await wipe();
  await seedAged("fix the deck railing north side now", 0);
  await seedAged("fix the deck railing north side", 0);

  const r = await completeTask(`${MARK} fix the deck railing north side now`);
  assert(isAmbiguous(r), `expected ambiguity on a 12.5% gap, got ${JSON.stringify(r)}`);
  assertEquals(r.candidates.length, 2);
  await wipe();
});
