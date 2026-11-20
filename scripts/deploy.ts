// Deploy Edge Functions without the Supabase CLI.
//
// The CLI is blocked by Windows Smart App Control on this machine, so
// deployment goes through the Management API's deploy endpoint, which takes a
// multipart body: a `metadata` JSON part naming the entrypoint, plus one
// `file` part per source file. Relative imports resolve against the paths the
// file parts are named with, so `_shared/*` must be included alongside each
// function's index.ts.
//
//   deno run -A scripts/deploy.ts [slug ...]
//
// With no arguments it deploys every function under supabase/functions.

const ROOT = new URL("../", import.meta.url);

function env(name: string): string {
  const fromProcess = Deno.env.get(name);
  if (fromProcess) return fromProcess;
  const text = Deno.readTextFileSync(new URL(".env", ROOT));
  for (const line of text.split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/);
    if (m && m[1] === name) return m[2].replace(/^["']|["']$/g, "");
  }
  throw new Error(`${name} is not set`);
}

const REF = env("SUPABASE_PROJECT_REF");
const TOKEN = env("SUPABASE_ACCESS_TOKEN");
const API = `https://api.supabase.com/v1/projects/${REF}`;

async function listFiles(dir: URL, prefix: string): Promise<[string, string][]> {
  const out: [string, string][] = [];
  for await (const entry of Deno.readDir(dir)) {
    const child = new URL(`${entry.name}${entry.isDirectory ? "/" : ""}`, dir);
    const path = `${prefix}${entry.name}`;
    if (entry.isDirectory) {
      out.push(...await listFiles(child, `${path}/`));
    } else if (entry.name.endsWith(".ts")) {
      out.push([path, await Deno.readTextFile(child)]);
    }
  }
  return out;
}

async function deploy(slug: string): Promise<void> {
  const fnDir = new URL(`supabase/functions/${slug}/`, ROOT);
  const sharedDir = new URL("supabase/functions/_shared/", ROOT);

  // The entrypoint sits at the root of the bundle; _shared keeps its relative
  // position so `../_shared/x.ts` still resolves.
  const files: [string, string][] = [
    ...await listFiles(fnDir, `${slug}/`),
    ...await listFiles(sharedDir, "_shared/"),
  ];

  const form = new FormData();
  form.append(
    "metadata",
    new Blob(
      [JSON.stringify({
        name: slug,
        entrypoint_path: `${slug}/index.ts`,
        import_map_path: "deno.json",
        // /turn and /event reach Ahmed's tasks, reminders and memories, so
        // they require a JWT — the tablet's device identity. The sweep is
        // invoked by pg_cron with no user context and is idempotent (it only
        // fires reminders that are already due), so it stays open.
        verify_jwt: slug !== "reminders-sweep",
      })],
      { type: "application/json" },
    ),
  );

  // The import map travels with the bundle so `@supabase/supabase-js` resolves.
  files.push(["deno.json", await Deno.readTextFile(new URL("deno.json", ROOT))]);

  for (const [path, body] of files) {
    form.append("file", new Blob([body], { type: "application/typescript" }), path);
  }

  const res = await fetch(`${API}/functions/deploy?slug=${slug}`, {
    method: "POST",
    headers: { Authorization: `Bearer ${TOKEN}` },
    body: form,
  });

  const text = await res.text();
  if (!res.ok) {
    throw new Error(`deploying ${slug} failed: HTTP ${res.status} ${text}`);
  }
  console.log(`deployed ${slug} (${files.length} files)`);
}

const slugs = Deno.args.length > 0 ? Deno.args : ["turn", "event", "reminders-sweep"];
for (const slug of slugs) await deploy(slug);
console.log("done");
