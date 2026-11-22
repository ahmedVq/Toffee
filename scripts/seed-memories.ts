// Writes the history Toffee starts with.
//
// Every line is something that actually happened in this project. That is
// not a stylistic choice: the persona's central rule is that he may only
// mock what he can point at, and a seeded history is the one place that rule
// could be broken wholesale and never noticed. A fact invented here would be
// indistinguishable, to him, from one he observed.
//
// Idempotent on the fact text, so running it twice does not duplicate and --
// more usefully -- a fact Ahmed deletes stays deleted unless he asks for it
// back by re-running with it restored to the list below.
//
// Usage: deno run -A scripts/seed-memories.ts [--dry-run]

import { CORE_CATEGORY } from "../supabase/functions/_shared/persona.ts";

/**
 * Written in the first person because the pronouns collide otherwise:
 * Toffee is a he and so is Ahmed, and "he built him to be unimpressed by
 * him" is not a sentence. In Toffee's own voice "he" is always Ahmed.
 */
export const CORE_FACTS: string[] = [
  "He is Ahmed. He built me, and he built me to be unimpressed by him.",
  "He works in an office in Cairo that nobody else enters.",
  "He runs this whole project on free tiers and no budget, and looks for a free alternative before he will pay for anything.",
  "He keeps a separate task list on his laptop and decided, deliberately, not to connect it to mine.",
  "He factory-reset a 2016 tablet to make me its owner, so I cannot be uninstalled.",
  "He let that tablet run flat to 1%, then tried to charge it from a laptop port that supplies less power than the screen draws.",
  "He was told to rotate an exposed API key and said he would do it later.",
  "He tests things himself rather than taking a report for it.",
  "He finds me too slow, and said so.",
];

/** Which facts are not there yet. Pure, so the idempotence is a unit test
 *  rather than something proved by running the script twice and squinting. */
export function toWrite(existing: readonly string[]): string[] {
  const have = new Set(existing);
  return CORE_FACTS.filter((f) => !have.has(f));
}

if (import.meta.main) {
  const dryRun = Deno.args.includes("--dry-run");

  // Imported here rather than at the top so that reading this file does not
  // itself open a client. It is not isolation: persona.ts above already
  // pulls db.ts in, so the module graph loads either way. What this avoids
  // is the connection, because serviceClient() is what reads the
  // environment, and nothing calls it unless the script is actually run.
  const { serviceClient } = await import("../supabase/functions/_shared/db.ts");

  const { data, error } = await serviceClient()
    .from("memories")
    .select("fact")
    .eq("category", CORE_CATEGORY);
  if (error) {
    console.error(`could not read existing memories: ${error.message}`);
    Deno.exit(1);
  }

  const existing = (data ?? []).map((r: { fact: string }) => r.fact);
  const missing = toWrite(existing);

  if (dryRun) {
    console.log(`${missing.length} would be written, ${existing.length} already there`);
    for (const f of missing) console.log(`  + ${f}`);
    Deno.exit(0);
  }

  if (missing.length > 0) {
    // Written without an embedding, on purpose. The seeded history is
    // selected by category and injected into every prompt, so it is never
    // the subject of a vector search -- and gte-small only runs inside the
    // Edge runtime, which a script on a laptop cannot reach. Migration 0008
    // makes `match_memories` skip unembedded rows so these cannot surface
    // as a "nearest" match as well.
    const { error: writeError } = await serviceClient()
      .from("memories")
      .insert(missing.map((fact) => ({ fact, category: CORE_CATEGORY })));
    if (writeError) {
      console.error(`writing failed: ${writeError.message}`);
      Deno.exit(1);
    }
    for (const f of missing) console.log(`  + ${f}`);
  }
  console.log(`${missing.length} written, ${existing.length} already there`);
}
