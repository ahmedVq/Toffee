// Verifies every table and column the agent relies on exists in the hosted
// project. PostgREST does not expose information_schema, so each table is
// probed with an explicit column select: a missing table or column makes
// PostgREST answer 4xx instead of 200.

async function loadEnv(): Promise<Record<string, string>> {
  const env: Record<string, string> = {};
  try {
    const text = await Deno.readTextFile(new URL("../.env", import.meta.url));
    for (const line of text.split(/\r?\n/)) {
      const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/);
      if (m) env[m[1]] = m[2].replace(/^["']|["']$/g, "");
    }
  } catch { /* fall back to process env */ }
  return {
    SUPABASE_URL: env.SUPABASE_URL ?? Deno.env.get("SUPABASE_URL") ?? "",
    SUPABASE_SECRET_KEY: env.SUPABASE_SECRET_KEY ??
      Deno.env.get("SUPABASE_SECRET_KEY") ?? "",
  };
}

Deno.test("schema has every table the agent needs", async () => {
  const { SUPABASE_URL, SUPABASE_SECRET_KEY } = await loadEnv();
  if (!SUPABASE_URL || !SUPABASE_SECRET_KEY) {
    throw new Error("SUPABASE_URL and SUPABASE_SECRET_KEY must be set in .env");
  }

  const want: Record<string, string[]> = {
    profile: ["id", "display_name", "timezone", "working_hours", "facts"],
    tasks: ["id", "title", "status", "due_at", "project", "created_at", "done_at"],
    reminders: ["id", "text", "due_at", "fired_at", "recurring", "source"],
    presence_events: ["id", "at", "present", "duration_s"],
    memories: ["id", "fact", "category", "embedding", "created_at"],
    conversation: ["id", "role", "content", "at"],
    said_log: ["id", "said", "reason", "topic_key", "at"],
    llm_spend: ["id", "day", "calls", "tokens_in", "tokens_out"],
  };

  const problems: string[] = [];
  for (const [table, columns] of Object.entries(want)) {
    const res = await fetch(
      `${SUPABASE_URL}/rest/v1/${table}?select=${columns.join(",")}&limit=0`,
      {
        headers: {
          apikey: SUPABASE_SECRET_KEY,
          Authorization: `Bearer ${SUPABASE_SECRET_KEY}`,
        },
      },
    );
    const body = await res.text();
    if (!res.ok) problems.push(`${table}: HTTP ${res.status} ${body}`);
  }
  if (problems.length) {
    throw new Error(`schema incomplete:\n${problems.join("\n")}`);
  }
});
