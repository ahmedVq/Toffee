// What Toffee remembers about Ahmed.
//
// Raw conversation gets expensive to carry into every prompt, so facts worth
// keeping are distilled here and recalled semantically. Embeddings come from
// Supabase's built-in gte-small, which runs inside the Edge runtime at no
// cost and needs no external API.
//
// `Supabase.ai` exists only in that runtime. remember/recall therefore accept
// an Embedder so local tests can inject a deterministic one; production uses
// the default and never passes the argument.

import { serviceClient, toMemory } from "../db.ts";
import type { Memory } from "../types.ts";

export type Embedder = (text: string) => Promise<number[]>;

export const EMBEDDING_DIMS = 384;

/** The real embedder: Supabase's gte-small, available inside Edge Functions. */
export const gteSmall: Embedder = async (text: string) => {
  const ai = (globalThis as { Supabase?: { ai?: { Session: new (m: string) => { run: (t: string, o: unknown) => Promise<number[]> } } } }).Supabase?.ai;
  if (!ai) {
    throw new Error(
      "Supabase.ai is unavailable — gteSmall only runs inside an Edge Function. " +
        "Pass an Embedder explicitly when calling from elsewhere.",
    );
  }
  const session = new ai.Session("gte-small");
  return await session.run(text, { mean_pool: true, normalize: true });
};

export async function remember(
  fact: string,
  category: string,
  embed: Embedder = gteSmall,
): Promise<Memory> {
  const embedding = await embed(fact);
  const { data, error } = await serviceClient()
    .from("memories")
    .insert({ fact, category, embedding: JSON.stringify(embedding) })
    .select()
    .single();
  if (error) throw new Error(`remembering failed: ${error.message}`);
  return toMemory(data);
}

/** Nearest facts first, by cosine distance. */
export async function recall(
  query: string,
  limit = 5,
  embed: Embedder = gteSmall,
): Promise<Memory[]> {
  const embedding = await embed(query);
  const { data, error } = await serviceClient().rpc("match_memories", {
    query_embedding: JSON.stringify(embedding),
    match_count: limit,
  });
  if (error) throw new Error(`recall failed: ${error.message}`);
  return (data ?? []).map(toMemory);
}

/** The profile is a single row describing Ahmed. Created on first write. */
export async function updateProfile(field: string, value: string): Promise<void> {
  const db = serviceClient();
  const { data: existing } = await db.from("profile").select("id").limit(1).maybeSingle();

  const patch: Record<string, string> = { [field]: value };
  const { error } = existing
    ? await db.from("profile").update(patch).eq("id", existing.id)
    : await db.from("profile").insert(patch);

  if (error) throw new Error(`updating profile failed: ${error.message}`);
}
