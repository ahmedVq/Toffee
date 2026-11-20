// Applies supabase/migrations/*.sql to the hosted project through the
// Supabase Management API (the Supabase CLI is unavailable on this machine).
// Usage: deno run -A scripts/migrate.ts

async function loadEnv(): Promise<Record<string, string>> {
  const env: Record<string, string> = {};
  try {
    const text = await Deno.readTextFile(new URL("../.env", import.meta.url));
    for (const line of text.split(/\r?\n/)) {
      const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/);
      if (m) env[m[1]] = m[2].replace(/^["']|["']$/g, "");
    }
  } catch { /* fall back to process env */ }
  return env;
}

const env = await loadEnv();
const ref = env.SUPABASE_PROJECT_REF ?? Deno.env.get("SUPABASE_PROJECT_REF");
const token = env.SUPABASE_ACCESS_TOKEN ?? Deno.env.get("SUPABASE_ACCESS_TOKEN");
if (!ref || !token) {
  console.error("SUPABASE_PROJECT_REF and SUPABASE_ACCESS_TOKEN must be set in .env");
  Deno.exit(1);
}

async function query(sql: string): Promise<unknown[]> {
  const res = await fetch(
    `https://api.supabase.com/v1/projects/${ref}/database/query`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ query: sql }),
    },
  );
  const body = await res.text();
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${body}`);
  return body ? JSON.parse(body) : [];
}

const quote = (s: string) => `'${s.replaceAll("'", "''")}'`;

await query(
  `create table if not exists _migrations (
     filename text primary key,
     applied_at timestamptz not null default now()
   )`,
);

const applied = new Set(
  (await query("select filename from _migrations") as { filename: string }[])
    .map((r) => r.filename),
);

const dir = new URL("../supabase/migrations/", import.meta.url);
const files: string[] = [];
for await (const e of Deno.readDir(dir)) {
  if (e.isFile && e.name.endsWith(".sql")) files.push(e.name);
}
files.sort();

let count = 0;
for (const file of files) {
  if (applied.has(file)) continue;
  const sql = await Deno.readTextFile(new URL(file, dir));
  try {
    // One request so the migration and its bookkeeping row commit together.
    await query(
      `${sql}\n;\ninsert into _migrations (filename) values (${quote(file)});`,
    );
  } catch (err) {
    console.error(`FAILED ${file}\n${(err as Error).message}`);
    Deno.exit(1);
  }
  console.log(`applied ${file}`);
  count++;
}
console.log(count ? `done, ${count} applied` : "nothing to apply");
