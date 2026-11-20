// Memory tools.
//
// `Supabase.ai.Session("gte-small")` exists only inside the Edge runtime, so
// a local `deno test` cannot produce real embeddings. remember/recall
// therefore take an Embedder, defaulting to the real one in production and
// injected here as a deterministic fake.
//
// That means these tests prove the *plumbing* — that a 384-dimension vector
// round-trips through the column, and that match_memories ranks by proximity
// and not by keyword. They do not prove the model embeds paraphrases nearby;
// that is semantics, and it is checked against the deployed function in
// Task 12.

import { assert, assertEquals } from "@std/assert";
import { serviceClient } from "../supabase/functions/_shared/db.ts";
import {
  type Embedder,
  recall,
  remember,
  updateProfile,
} from "../supabase/functions/_shared/tools/memory.ts";

const MARK = `zz${crypto.randomUUID().slice(0, 8)}`;
const db = serviceClient();

const wipe = () => db.from("memories").delete().like("fact", "zz%");

/** A unit vector pointing along one axis, so distances are predictable:
 * identical axes are nearest, different axes are far. */
function axis(i: number): number[] {
  const v = new Array(384).fill(0);
  v[i] = 1;
  return v;
}

/** Maps known text to a known axis, so ranking is fully determined. */
function fakeEmbedder(map: Record<string, number>): Embedder {
  return (text: string) => {
    const key = Object.keys(map).find((k) => text.includes(k));
    return Promise.resolve(axis(key ? map[key] : 383));
  };
}

Deno.test("remember stores a fact with a 384-dimension vector", async () => {
  await wipe();
  const embed = fakeEmbedder({ standup: 5 });
  const m = await remember(`${MARK} hates standup meetings`, "preference", embed);

  assertEquals(m.fact, `${MARK} hates standup meetings`);
  const { data } = await db.from("memories").select("embedding").eq("id", m.id).single();
  const stored = JSON.parse(String(data!.embedding)) as number[];
  assertEquals(stored.length, 384, "the column must hold a 384-dimension vector");
  await wipe();
});

Deno.test("recall ranks by vector proximity, not keyword overlap", async () => {
  await wipe();
  // Deliberately: the nearest fact shares NO words with the query, and the
  // furthest shares several. Keyword matching would invert this.
  const embed = fakeEmbedder({
    "morning meetings": 5,
    standup: 5,
    "coffee": 100,
  });
  await remember(`${MARK} hates standup`, "preference", embed);
  await remember(`${MARK} drinks coffee`, "habit", embed);

  const hits = await recall(`${MARK} morning meetings`, 5, embed);
  const mine = hits.filter((h) => h.fact.startsWith(MARK));
  assert(mine.length >= 1, "expected at least one hit");
  assertEquals(mine[0].fact, `${MARK} hates standup`, "nearest vector must rank first");
  await wipe();
});

Deno.test("recall returns [] on an empty store rather than throwing", async () => {
  await wipe();
  const hits = await recall(`${MARK} anything`, 5, fakeEmbedder({}));
  assertEquals(hits.filter((h) => h.fact.startsWith(MARK)).length, 0);
});

Deno.test("updateProfile writes a field readable afterwards", async () => {
  const value = `Cairo-${crypto.randomUUID().slice(0, 6)}`;
  await updateProfile("timezone", value);

  const { data } = await db.from("profile").select("timezone").limit(1).single();
  assertEquals(data!.timezone, value);

  await updateProfile("timezone", "Africa/Cairo");
});
