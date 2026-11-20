// The tablet's own identity.
//
// `tests/rls.test.ts` already proves the policies are right. This proves the
// *provisioning path* is right: that the script Ahmed will actually run
// produces an identity the policies recognise, that running it twice does not
// quietly mint a second one, and that the thing it hands the tablet can read
// his tasks and nothing he would mind losing.
//
// Non-vacuous by construction: every "cannot read" test seeds a row through
// the admin client first, so zero rows can only mean the policy hid it.

import { assert, assertEquals, assertNotEquals } from "@std/assert";
import { env, provision, remove } from "../scripts/provision-device.ts";

const URL_ = env("SUPABASE_URL");
const PUB = env("SUPABASE_PUBLISHABLE_KEY");
const SECRET = env("SUPABASE_SECRET_KEY");

const adminHeaders = { apikey: SECRET, Authorization: `Bearer ${SECRET}` };

async function rest(
  headers: Record<string, string>,
  method: string,
  path: string,
  body?: unknown,
  prefer = "return=representation",
) {
  const res = await fetch(`${URL_}/rest/v1/${path}`, {
    method,
    headers: { ...headers, "Content-Type": "application/json", Prefer: prefer },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null };
}

function claimsOf(jwt: string): Record<string, unknown> {
  return JSON.parse(
    atob(jwt.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")),
  );
}

async function signIn(email: string, password: string) {
  const res = await fetch(`${URL_}/auth/v1/token?grant_type=password`, {
    method: "POST",
    headers: { apikey: PUB, "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  const session = await res.json();
  assert(res.ok, `sign in failed: ${JSON.stringify(session)}`);
  return session as { access_token: string; refresh_token: string };
}

// One identity per run, so a failed run cannot poison the next one.
const LABEL = `provtest-${crypto.randomUUID().slice(0, 8)}`;
const MARK = `zz-${LABEL}`;

const first = await provision(LABEL);
const session = await signIn(first.email, first.password!);
const deviceHeaders = { apikey: PUB, Authorization: `Bearer ${session.access_token}` };

// ---------------------------------------------------------------- the claim

Deno.test("the provisioned identity carries the device claim", () => {
  const claims = claimsOf(session.access_token);
  assertEquals(claims.role, "authenticated", "it must not be the anon role");
  const appMeta = claims.app_metadata as Record<string, unknown>;
  // Migration 0003 gates on `->> 'device' = 'true'`, and `->>` renders both
  // the boolean and the string as 'true'. This asserts what the policy sees.
  assertEquals(String(appMeta.device), "true");
});

Deno.test("a fresh identity is reported as created, with a password", () => {
  assert(first.created, "the first provision must create");
  assert(first.password !== null, "a created identity must hand back its password once");
  assert(first.password!.length >= 32, "the password must not be guessable");
});

// ---------------------------------------------------------------- what it can do

Deno.test("the device identity can read tasks", async () => {
  const seeded = await rest(adminHeaders, "POST", "tasks", { title: `${MARK} visible` });
  assertEquals(seeded.status, 201);

  const read = await rest(deviceHeaders, "GET", `tasks?title=eq.${MARK} visible`);
  assertEquals(read.status, 200);
  assertEquals((read.body as unknown[]).length, 1, "the tablet must be able to read his tasks");

  await rest(adminHeaders, "DELETE", `tasks?title=like.${MARK}*`);
});

Deno.test("the device identity can read reminders", async () => {
  const seeded = await rest(adminHeaders, "POST", "reminders", {
    text: `${MARK} due`,
    due_at: "2030-01-01T00:00:00Z",
  });
  assertEquals(seeded.status, 201);

  const read = await rest(deviceHeaders, "GET", `reminders?text=eq.${MARK} due`);
  assertEquals(read.status, 200);
  assertEquals((read.body as unknown[]).length, 1);

  await rest(adminHeaders, "DELETE", `reminders?text=like.${MARK}*`);
});

// `return=minimal` on purpose. The device has an INSERT policy on
// presence_events and no SELECT policy — by design, since presence is
// something it reports, not something it reads back. Asking PostgREST for the
// representation adds a RETURNING, Postgres applies the SELECT policy to it,
// and the insert fails with the same 42501 a denied insert gives. The write
// is verified through admin instead, which also makes this test non-vacuous:
// it proves the row landed, not merely that nothing raised.
Deno.test("the device identity can record presence, and cannot read it back", async () => {
  const marker = 900000 + Math.floor(Math.random() * 99999);

  const wrote = await rest(
    deviceHeaders,
    "POST",
    "presence_events",
    { present: true, duration_s: marker },
    "return=minimal",
  );
  assertEquals(wrote.status, 201, `presence insert: ${JSON.stringify(wrote.body)}`);

  const landed = await rest(adminHeaders, "GET", `presence_events?duration_s=eq.${marker}`);
  assertEquals((landed.body as unknown[]).length, 1, "the row must actually be there");

  const readBack = await rest(deviceHeaders, "GET", `presence_events?duration_s=eq.${marker}`);
  assert(
    readBack.status !== 200 || (readBack.body as unknown[]).length === 0,
    "the device has no select policy on presence_events",
  );

  await rest(adminHeaders, "DELETE", `presence_events?duration_s=eq.${marker}`);
});

// ---------------------------------------------------------------- what it cannot

Deno.test("the device identity cannot read memories", async () => {
  const seeded = await rest(adminHeaders, "POST", "memories", { fact: `${MARK} secret` });
  assertEquals(seeded.status, 201, "the row must exist for this test to mean anything");

  const read = await rest(deviceHeaders, "GET", `memories?fact=eq.${MARK} secret`);
  assert(
    read.status !== 200 || (read.body as unknown[]).length === 0,
    `the tablet read a memory it should not see: ${JSON.stringify(read.body)}`,
  );

  const adminSees = await rest(adminHeaders, "GET", `memories?fact=eq.${MARK} secret`);
  assertEquals((adminSees.body as unknown[]).length, 1, "admin must still see it");
  await rest(adminHeaders, "DELETE", `memories?fact=like.${MARK}*`);
});

Deno.test("the device identity cannot read said_log", async () => {
  const seeded = await rest(adminHeaders, "POST", "said_log", {
    said: `${MARK} spoken`,
    topic_key: MARK,
  });
  assertEquals(seeded.status, 201);

  const read = await rest(deviceHeaders, "GET", `said_log?topic_key=eq.${MARK}`);
  assert(
    read.status !== 200 || (read.body as unknown[]).length === 0,
    `the tablet read said_log: ${JSON.stringify(read.body)}`,
  );

  const adminSees = await rest(adminHeaders, "GET", `said_log?topic_key=eq.${MARK}`);
  assertEquals((adminSees.body as unknown[]).length, 1, "admin must still see it");
  await rest(adminHeaders, "DELETE", `said_log?topic_key=eq.${MARK}`);
});

// ---------------------------------------------------------------- idempotence

Deno.test("provisioning twice does not mint a second identity", async () => {
  const second = await provision(LABEL);
  assertEquals(second.id, first.id, "a second run must find the first identity");
  assertEquals(second.created, false);
  assertEquals(second.password, null, "it cannot hand back a password it never knew");
});

Deno.test("rotating gives a new password for the same identity", async () => {
  const rotated = await provision(LABEL, { rotate: true });
  assertEquals(rotated.id, first.id);
  assertEquals(rotated.created, false);
  assert(rotated.password !== null, "a rotation must hand back the new password");
  assertNotEquals(rotated.password, first.password);

  // And the new one actually works.
  const fresh = await signIn(rotated.email, rotated.password!);
  assertEquals(String((claimsOf(fresh.access_token).app_metadata as Record<string, unknown>).device), "true");
});

Deno.test("cleanup: the test identity is removed", async () => {
  await remove(first.id);
  const res = await fetch(`${URL_}/auth/v1/admin/users/${first.id}`, {
    headers: adminHeaders,
  });
  assertEquals(res.status, 404, "the identity must be gone");
  await res.body?.cancel();
});
