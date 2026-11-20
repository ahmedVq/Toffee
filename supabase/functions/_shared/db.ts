// The privileged database client, and the one place snake_case becomes
// camelCase.
//
// Every Edge Function talks to Postgres through `serviceClient()`, which
// authenticates with the secret key and therefore bypasses RLS. That is
// deliberate: the policies in migration 0003 exist to constrain the *tablet*,
// not the agent. The secret key never leaves the server.

import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import type { Memory, Reminder, Task } from "./types.ts";

/** Reads .env when present, falling back to the process environment.
 * Edge Functions get their values from the environment; local tests get them
 * from the gitignored .env at the repo root. */
function env(name: string): string {
  const fromProcess = Deno.env.get(name);
  if (fromProcess) return fromProcess;

  try {
    const text = Deno.readTextFileSync(new URL("../../../.env", import.meta.url));
    for (const line of text.split(/\r?\n/)) {
      const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/);
      if (m && m[1] === name) return m[2].replace(/^["']|["']$/g, "");
    }
  } catch { /* no .env; the process environment was the only source */ }

  return "";
}

/** Supabase reserves every SUPABASE_* secret name and injects its own set
 * into Edge Functions, so the key is called SUPABASE_SECRET_KEY locally (from
 * .env) but SUPABASE_SERVICE_ROLE_KEY once deployed. Both name the same
 * service-role credential. */
export function resolveSecretKey(
  lookup: (name: string) => string | undefined,
): string {
  return lookup("SUPABASE_SECRET_KEY") || lookup("SUPABASE_SERVICE_ROLE_KEY") || "";
}

let cached: SupabaseClient | null = null;

/** The service-role client. Cached, because creating one per call would open
 * a new connection pool on every request. */
export function serviceClient(): SupabaseClient {
  if (cached) return cached;

  const url = env("SUPABASE_URL");
  const key = resolveSecretKey((n) => env(n) || undefined);
  if (!url || !key) {
    throw new Error(
      "SUPABASE_URL and one of SUPABASE_SECRET_KEY / SUPABASE_SERVICE_ROLE_KEY must be set",
    );
  }

  cached = createClient(url, key, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  return cached;
}

// ---------------------------------------------------------------- row mappers
//
// One per table. Nothing outside this file should know a column name.

type Row = Record<string, unknown>;

export function toTask(row: Row): Task {
  return {
    id: String(row.id),
    title: String(row.title),
    status: row.status === "done" ? "done" : "open",
    dueAt: (row.due_at as string | null) ?? null,
    project: (row.project as string | null) ?? null,
  };
}

export function toReminder(row: Row): Reminder {
  return {
    id: String(row.id),
    text: String(row.text),
    dueAt: String(row.due_at),
    firedAt: (row.fired_at as string | null) ?? null,
    recurring: (row.recurring as string | null) ?? null,
  };
}

export function toMemory(row: Row): Memory {
  return {
    id: String(row.id),
    fact: String(row.fact),
    category: String(row.category),
  };
}
