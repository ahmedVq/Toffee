// Proves what the device identity can and cannot do.
//
// Three callers, because the publishable key alone is the *anon* role, not the
// device:
//   admin    - secret key, bypasses RLS; seeds rows and verifies ground truth.
//   device   - a real Supabase Auth user flagged app_metadata.device = true,
//              signed in with a password, so requests run as role
//              `authenticated` with the device claim. This is what policies
//              target.
//   stranger - an Auth user WITHOUT the device claim (what a self-signup would
//              be). Also `authenticated`, so it proves the claim is enforced.
//   anon     - publishable key with no user session.
//
// Every negative test is non-vacuous: rows are seeded through the admin client,
// so "zero rows" can only mean the policy hid them. A denied write must either
// raise an RLS/permission error, or affect zero rows AND leave the row
// unchanged when re-read by admin.

import { assert, assertEquals } from "@std/assert";

async function loadEnv(): Promise<Record<string, string>> {
  const env: Record<string, string> = {};
  try {
    const text = await Deno.readTextFile(new URL("../.env", import.meta.url));
    for (const line of text.split(/\r?\n/)) {
      const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/);
      if (m) env[m[1]] = m[2].replace(/^["']|["']$/g, "");
    }
  } catch { /* fall back to process env */ }
  const get = (k: string) => env[k] ?? Deno.env.get(k) ?? "";
  const out = {
    url: get("SUPABASE_URL"),
    pub: get("SUPABASE_PUBLISHABLE_KEY"),
    secret: get("SUPABASE_SECRET_KEY"),
  };
  if (!out.url || !out.pub || !out.secret) {
    throw new Error(
      "SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY, SUPABASE_SECRET_KEY must be set in .env",
    );
  }
  return out;
}

const cfg = await loadEnv();

type Caller = { name: string; headers: Record<string, string> };
type Res = { status: number; body: unknown; code?: string };

async function rest(
  who: Caller,
  method: string,
  path: string,
  body?: unknown,
  prefer = "return=representation",
): Promise<Res> {
  const res = await fetch(`${cfg.url}/rest/v1/${path}`, {
    method,
    headers: {
      ...who.headers,
      "Content-Type": "application/json",
      Prefer: prefer,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  const parsed = text ? JSON.parse(text) : null;
  return {
    status: res.status,
    body: parsed,
    code: parsed && !Array.isArray(parsed) ? parsed.code : undefined,
  };
}

const admin: Caller = {
  name: "admin",
  headers: { apikey: cfg.secret, Authorization: `Bearer ${cfg.secret}` },
};
const anon: Caller = { name: "anon", headers: { apikey: cfg.pub } };

async function adminReadAll(table: string, filter: string): Promise<unknown[]> {
  const r = await rest(admin, "GET", `${table}?${filter}`);
  assertEquals(r.status, 200, `admin read ${table}: ${JSON.stringify(r.body)}`);
  return r.body as unknown[];
}

// ---- users -----------------------------------------------------------------

const runId = crypto.randomUUID().slice(0, 8);
const password = crypto.randomUUID() + crypto.randomUUID();
const createdUsers: string[] = [];

async function makeUser(label: string, appMetadata: object): Promise<Caller> {
  const email = `rls-${label}-${runId}@example.test`;
  const c = await fetch(`${cfg.url}/auth/v1/admin/users`, {
    method: "POST",
    headers: { ...admin.headers, "Content-Type": "application/json" },
    body: JSON.stringify({
      email,
      password,
      email_confirm: true,
      app_metadata: appMetadata,
    }),
  });
  const created = await c.json();
  assert(c.ok, `create ${label} user: ${JSON.stringify(created)}`);
  createdUsers.push(created.id);

  const s = await fetch(`${cfg.url}/auth/v1/token?grant_type=password`, {
    method: "POST",
    headers: { apikey: cfg.pub, "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  const session = await s.json();
  assert(s.ok, `sign in ${label}: ${JSON.stringify(session)}`);

  // Guard against testing the wrong role: decode the JWT we will send.
  const claims = JSON.parse(
    atob(session.access_token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")),
  );
  assertEquals(claims.role, "authenticated");
  return {
    name: label,
    headers: {
      apikey: cfg.pub,
      Authorization: `Bearer ${session.access_token}`,
    },
  };
}

const device = await makeUser("device", { device: true });
const stranger = await makeUser("stranger", {});

// ---- seed ------------------------------------------------------------------

const mark = `rls-${runId}`;
const seeded: Record<string, string> = {}; // table -> id
const seed: Record<string, Record<string, unknown>> = {
  profile: { display_name: mark },
  tasks: { title: mark },
  reminders: { text: mark, due_at: "2030-01-01T00:00:00Z" },
  presence_events: { present: true, duration_s: 4242 },
  memories: { fact: mark },
  conversation: { role: "user", content: mark },
  said_log: { said: mark, topic_key: mark },
  llm_spend: { day: "2999-12-31", calls: 7 },
};
/**
 * Remove what an earlier run left behind, before seeding anything.
 *
 * The seed below is top-level module code. If it throws, Deno never runs a
 * single test in this file — including the cleanup test at the bottom — so
 * every row inserted before the throw stays in the database for ever. That
 * is not hypothetical: `llm_spend.day` is unique, so the very first
 * interrupted run leaves `2999-12-31` behind, the next run's seed collides
 * with it and dies at the same line, and each of those deaths leaks a
 * further set of rows into profile, tasks, reminders, memories,
 * conversation, said_log and presence_events. The suite breaks itself
 * permanently and the failure it reports ("duplicate key") names the
 * symptom, not the cause.
 *
 * So the cleanup at the bottom is no longer the only one. This purges by
 * the `rls-` mark rather than by this run's id, which makes a run repair
 * its predecessor. It assumes no two runs of this file overlap in time;
 * they never do, and a parallel run would be unsound here anyway.
 */
async function purgeLeftovers(): Promise<void> {
  await rest(admin, "DELETE", "llm_spend?day=eq.2999-12-31");
  await rest(admin, "DELETE", "profile?display_name=like.rls-*");
  await rest(admin, "DELETE", "tasks?title=like.rls-*");
  await rest(admin, "DELETE", "reminders?text=like.rls-*");
  await rest(admin, "DELETE", "memories?fact=like.rls-*");
  await rest(admin, "DELETE", "conversation?content=like.rls-*");
  await rest(admin, "DELETE", "said_log?topic_key=like.rls-*");
  await rest(admin, "DELETE", "presence_events?duration_s=in.(1717,4242)");

  const res = await fetch(`${cfg.url}/auth/v1/admin/users?per_page=200`, {
    headers: admin.headers,
  });
  if (!res.ok) return;
  const { users } = await res.json() as { users?: { id: string; email?: string }[] };
  for (const u of users ?? []) {
    // This run's own users already exist by now — makeUser runs above this.
    // Skipping them is what keeps the purge from deleting the identities the
    // tests are about to sign in as.
    if (u.email?.includes(runId)) continue;
    if (u.email?.startsWith("rls-") && u.email.endsWith("@example.test")) {
      await fetch(`${cfg.url}/auth/v1/admin/users/${u.id}`, {
        method: "DELETE",
        headers: admin.headers,
      });
    }
  }
}

await purgeLeftovers();

for (const [table, row] of Object.entries(seed)) {
  const r = await rest(admin, "POST", table, row);
  assertEquals(r.status, 201, `seed ${table}: ${JSON.stringify(r.body)}`);
  seeded[table] = (r.body as { id: string }[])[0].id;
}
// _migrations has no id; its rows exist from the runner.


// WHAT EACH NEGATIVE ACTUALLY PROVES (read before trusting a green run)
//   - Reads (assertReadHidden): prove the SELECT gate.
//   - Inserts (assertInsertDenied, return=minimal): prove the INSERT gate
//     independently of SELECT.
//   - Update/delete on a table the caller cannot SELECT (device on memories,
//     said_log, conversation, llm_spend, presence_events; anon and stranger on
//     tasks/reminders): UPDATE/DELETE with a WHERE must read the row first, so
//     the missing SELECT policy already yields zero rows. These tests prove the
//     property "the row is untouched", but they do NOT separately guard the
//     UPDATE/DELETE policies. For a table the caller cannot read, no
//     client-side test can tell the two gates apart.
//   - Update/delete on tables the device CAN select (tasks/reminders delete,
//     profile update/delete) do exercise the UPDATE/DELETE gate directly.

// ---- assertions helpers ----------------------------------------------------

const isDenied = (r: Res) =>
  (r.status === 401 || r.status === 403) && r.code === "42501";

/** Read must be refused outright, or return no rows (never the seeded row). */
async function assertReadHidden(who: Caller, table: string) {
  // Ground truth: the row really exists, so an empty result means hidden.
  const truth = await adminReadAll(table, `id=eq.${seeded[table]}`);
  assertEquals(truth.length, 1, `${table}: seeded row missing for admin`);
  const r = await rest(who, "GET", `${table}?select=*`);
  if (r.status === 200) {
    assertEquals(
      r.body,
      [],
      `${who.name} read ${table} and got rows: ${JSON.stringify(r.body)}`,
    );
  } else {
    assert(isDenied(r), `${who.name} read ${table}: unexpected ${r.status} ${JSON.stringify(r.body)}`);
  }
}

/** Insert must raise an RLS/permission error (42501) and write nothing. */
async function assertInsertDenied(
  who: Caller,
  table: string,
  row: Record<string, unknown>,
  markerCol: string,
) {
  // return=minimal is essential: with RETURNING, Postgres also applies SELECT
  // policies and raises the same 42501 when the caller cannot read the row back,
  // which would mask a missing insert gate.
  const r = await rest(who, "POST", table, row, "return=minimal");
  assert(
    isDenied(r),
    `${who.name} insert ${table}: expected 42501, got ${r.status} ${JSON.stringify(r.body)}`,
  );
  const leaked = await adminReadAll(table, `${markerCol}=eq.${row[markerCol]}`);
  assertEquals(leaked.length, 0, `${table}: denied insert still wrote a row`);
}

/** Update/delete must affect nothing (or error), and the row must be intact. */
async function assertMutationDenied(
  who: Caller,
  method: "PATCH" | "DELETE",
  table: string,
  patch: Record<string, unknown>,
  col: string,
) {
  const before = await adminReadAll(table, `id=eq.${seeded[table]}`);
  assertEquals(before.length, 1);
  const r = await rest(
    who,
    method,
    `${table}?id=eq.${seeded[table]}`,
    method === "PATCH" ? patch : undefined,
  );
  if (r.status >= 200 && r.status < 300) {
    assertEquals(
      r.body,
      [],
      `${who.name} ${method} ${table} affected rows: ${JSON.stringify(r.body)}`,
    );
  } else {
    assert(isDenied(r), `${who.name} ${method} ${table}: unexpected ${r.status} ${JSON.stringify(r.body)}`);
  }
  const after = await adminReadAll(table, `id=eq.${seeded[table]}`);
  assertEquals(after.length, 1, `${table}: row vanished after denied ${method}`);
  assertEquals(
    (after[0] as Record<string, unknown>)[col],
    (before[0] as Record<string, unknown>)[col],
    `${table}.${col} changed after denied ${method}`,
  );
}

// ---- positives: what the device may do -------------------------------------

Deno.test("device can read its tasks", async () => {
  const r = await rest(device, "GET", `tasks?id=eq.${seeded.tasks}`);
  assertEquals(r.status, 200);
  assertEquals((r.body as unknown[]).length, 1);
});

Deno.test("device can insert and update tasks", async () => {
  const ins = await rest(device, "POST", "tasks", { title: `${mark}-dev` });
  assertEquals(ins.status, 201, JSON.stringify(ins.body));
  const id = (ins.body as { id: string }[])[0].id;
  const upd = await rest(device, "PATCH", `tasks?id=eq.${id}`, { status: "done" });
  assertEquals(upd.status, 200);
  assertEquals((upd.body as { status: string }[])[0].status, "done");
});

Deno.test("device can read, insert and update reminders", async () => {
  const sel = await rest(device, "GET", `reminders?id=eq.${seeded.reminders}`);
  assertEquals((sel.body as unknown[]).length, 1);
  const ins = await rest(device, "POST", "reminders", {
    text: `${mark}-dev`,
    due_at: "2030-01-02T00:00:00Z",
  });
  assertEquals(ins.status, 201, JSON.stringify(ins.body));
  const id = (ins.body as { id: string }[])[0].id;
  const upd = await rest(device, "PATCH", `reminders?id=eq.${id}`, {
    fired_at: "2030-01-02T00:00:01Z",
  });
  assertEquals(upd.status, 200);
  assertEquals((upd.body as unknown[]).length, 1);
});

Deno.test("device can insert a presence event", async () => {
  // return=minimal: the device may insert but not select presence_events, so
  // asking Postgres to return the row would (correctly) be refused.
  const r = await rest(device, "POST", "presence_events", { present: false, duration_s: 1717 }, "return=minimal");
  assertEquals(r.status, 201, JSON.stringify(r.body));
  const rows = await adminReadAll("presence_events", "duration_s=eq.1717");
  assertEquals(rows.length, 1);
});

Deno.test("device can select profile", async () => {
  const r = await rest(device, "GET", `profile?id=eq.${seeded.profile}`);
  assertEquals(r.status, 200);
  assertEquals((r.body as unknown[]).length, 1);
});

// ---- negatives: the device must not overreach ------------------------------

Deno.test("device CANNOT read memories", () => assertReadHidden(device, "memories"));
Deno.test("device CANNOT read said_log", () => assertReadHidden(device, "said_log"));
Deno.test("device CANNOT read llm_spend", () => assertReadHidden(device, "llm_spend"));
Deno.test("device CANNOT read conversation", () => assertReadHidden(device, "conversation"));

Deno.test("device CANNOT read presence_events", () => assertReadHidden(device, "presence_events"));

Deno.test("device CANNOT write memories, said_log, conversation, llm_spend", async () => {
  await assertInsertDenied(device, "memories", { fact: `${mark}-w` }, "fact");
  await assertInsertDenied(device, "said_log", { said: `${mark}-w`, topic_key: mark }, "said");
  await assertInsertDenied(device, "conversation", { role: "user", content: `${mark}-w` }, "content");
  await assertInsertDenied(device, "llm_spend", { day: "2999-12-30", calls: 1 }, "day");
  await assertMutationDenied(device, "PATCH", "memories", { fact: "hacked" }, "fact");
  await assertMutationDenied(device, "PATCH", "said_log", { said: "hacked" }, "said");
  await assertMutationDenied(device, "PATCH", "conversation", { content: "hacked" }, "content");
  await assertMutationDenied(device, "PATCH", "llm_spend", { calls: 0 }, "calls");
});

Deno.test("device CANNOT delete any row type, including ones it may otherwise write", async () => {
  for (const [table, col] of [
    ["tasks", "title"],
    ["reminders", "text"],
    ["presence_events", "duration_s"],
    ["profile", "display_name"],
    ["memories", "fact"],
    ["said_log", "said"],
    ["conversation", "content"],
    ["llm_spend", "calls"],
  ]) {
    await assertMutationDenied(device, "DELETE", table, {}, col);
  }
});

Deno.test("device CANNOT insert or update profile", async () => {
  await assertInsertDenied(device, "profile", { display_name: `${mark}-w` }, "display_name");
  await assertMutationDenied(device, "PATCH", "profile", { display_name: "hacked" }, "display_name");
});

Deno.test("device CANNOT update presence_events", () =>
  assertMutationDenied(device, "PATCH", "presence_events", { duration_s: 1 }, "duration_s"));

Deno.test("device CANNOT touch _migrations", async () => {
  const truth = await adminReadAll("_migrations", "select=filename");
  assert(truth.length > 0, "_migrations should have rows for admin");
  const r = await rest(device, "GET", "_migrations?select=*");
  if (r.status === 200) assertEquals(r.body, []);
  else assert(isDenied(r), `unexpected ${r.status} ${JSON.stringify(r.body)}`);
  const w = await rest(device, "POST", "_migrations", { filename: `${mark}.sql` });
  assert(isDenied(w), `insert _migrations: ${w.status} ${JSON.stringify(w.body)}`);
});

// ---- identities without the device claim -----------------------------------

Deno.test("anon (publishable key, no session) is denied everything the device may do", async () => {
  for (const t of ["tasks", "reminders", "profile"]) {
    const truth = await adminReadAll(t, `id=eq.${seeded[t]}`);
    assertEquals(truth.length, 1);
    const r = await rest(anon, "GET", `${t}?select=*`);
    if (r.status === 200) assertEquals(r.body, [], `anon read ${t}`);
    else assert(isDenied(r), `anon read ${t}: ${r.status} ${JSON.stringify(r.body)}`);
  }
  await assertInsertDenied(anon, "presence_events", { present: true, duration_s: 9191 }, "duration_s");
  await assertInsertDenied(anon, "tasks", { title: `${mark}-anon` }, "title");
  await assertMutationDenied(anon, "PATCH", "tasks", { title: "hacked" }, "title");
});

Deno.test("an authenticated user WITHOUT the device claim is denied", async () => {
  for (const t of ["tasks", "reminders", "profile"]) await assertReadHidden(stranger, t);
  await assertInsertDenied(stranger, "tasks", { title: `${mark}-stranger` }, "title");
  await assertInsertDenied(stranger, "presence_events", { present: true, duration_s: 8181 }, "duration_s");
  await assertMutationDenied(stranger, "PATCH", "tasks", { title: "hacked" }, "title");
  await assertMutationDenied(stranger, "PATCH", "reminders", { text: "hacked" }, "text");
});

// ---- cleanup (must run last; Deno runs tests in declaration order) ----------

Deno.test("cleanup test data", async () => {
  for (const [table, id] of Object.entries(seeded)) {
    await rest(admin, "DELETE", `${table}?id=eq.${id}`);
  }
  await rest(admin, "DELETE", `tasks?title=like.${mark}*`);
  await rest(admin, "DELETE", `reminders?text=like.${mark}*`);
  await rest(admin, "DELETE", `presence_events?duration_s=in.(1717,4242)`);
  for (const id of createdUsers) {
    await fetch(`${cfg.url}/auth/v1/admin/users/${id}`, {
      method: "DELETE",
      headers: admin.headers,
    });
  }
});
