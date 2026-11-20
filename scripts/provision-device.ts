// Give the tablet its own identity.
//
// The anti-goal is "no secrets on the device". The tablet does hold a
// credential, but a narrow, revocable one: a Supabase Auth user flagged
// `app_metadata.device`, which migration 0003's policies gate on. It can read
// Ahmed's profile, tasks and reminders and record presence. It cannot read
// `memories` or `said_log`, and it cannot reach any other row. `app_metadata`
// is admin-only — unlike `user_metadata`, a signed-in client cannot write it —
// so the claim cannot be forged from the device.
//
// Revocation is deleting or disabling this user. The tablet goes inert within
// one refresh cycle. That is the answer to "someone picked it up".
//
//   deno run -A scripts/provision-device.ts [label] [--rotate]
//
// The password is printed once and stored nowhere. If it is lost, --rotate
// mints a new one for the same identity.

const ROOT = new URL("../", import.meta.url);

/** Reads .env, falling back to the process environment — the same order
 * scripts/deploy.ts uses. */
export function env(name: string): string {
  const fromProcess = Deno.env.get(name);
  if (fromProcess) return fromProcess;

  const text = Deno.readTextFileSync(new URL(".env", ROOT));
  for (const line of text.split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/);
    if (m && m[1] === name) return m[2].replace(/^["']|["']$/g, "");
  }
  throw new Error(`${name} is not set`);
}

const URL_ = env("SUPABASE_URL").replace(/\/+$/, "");
const SECRET = env("SUPABASE_SECRET_KEY");
const ADMIN = `${URL_}/auth/v1/admin/users`;

const headers = {
  apikey: SECRET,
  Authorization: `Bearer ${SECRET}`,
  "Content-Type": "application/json",
};

export type Provisioned = {
  id: string;
  email: string;
  /** Only when this run set it — on a plain second run there is no password
   * to hand back, because nothing stored the first one. */
  password: string | null;
  created: boolean;
};

type User = { id: string; email: string; app_metadata?: Record<string, unknown> };

/** 32 bytes of CSPRNG, base64url. Nobody types this twice, so length costs
 * nothing and guessability costs everything. */
function newPassword(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...bytes))
    .replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function call(
  url: string,
  method: string,
  body?: unknown,
): Promise<{ ok: boolean; status: number; json: unknown }> {
  const res = await fetch(url, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  return { ok: res.ok, status: res.status, json: text ? JSON.parse(text) : null };
}

/** The admin list endpoint pages; `filter` narrows by email substring, but it
 * is a substring, so the exact match is still checked here. */
async function findByEmail(email: string): Promise<User | null> {
  for (let page = 1; page <= 10; page++) {
    const res = await call(
      `${ADMIN}?page=${page}&per_page=200&filter=${encodeURIComponent(email)}`,
      "GET",
    );
    if (!res.ok) throw new Error(`listing users failed: ${JSON.stringify(res.json)}`);
    const users = (res.json as { users?: User[] }).users ?? [];
    const hit = users.find((u) => u.email?.toLowerCase() === email.toLowerCase());
    if (hit) return hit;
    if (users.length < 200) return null;
  }
  return null;
}

/**
 * Converge on one identity for `label`. Safe to run repeatedly: a second run
 * finds the first identity rather than minting a rival, and repairs the claim
 * if anything has stripped it — an identity without the claim is an identity
 * the policies do not recognise, which is a tablet that has silently stopped
 * working.
 */
export async function provision(
  label = "tablet",
  opts: { rotate?: boolean } = {},
): Promise<Provisioned> {
  const email = `${label}@toffee.local`;
  const existing = await findByEmail(email);

  if (!existing) {
    const password = newPassword();
    const res = await call(ADMIN, "POST", {
      email,
      password,
      email_confirm: true,
      app_metadata: { device: true },
    });
    if (!res.ok) throw new Error(`creating ${email} failed: ${JSON.stringify(res.json)}`);
    return { id: (res.json as User).id, email, password, created: true };
  }

  const patch: Record<string, unknown> = {};
  if (String(existing.app_metadata?.device) !== "true") {
    patch.app_metadata = { ...existing.app_metadata, device: true };
  }
  const password = opts.rotate ? newPassword() : null;
  if (password) patch.password = password;

  if (Object.keys(patch).length > 0) {
    const res = await call(`${ADMIN}/${existing.id}`, "PUT", patch);
    if (!res.ok) throw new Error(`updating ${email} failed: ${JSON.stringify(res.json)}`);
  }

  return { id: existing.id, email, password, created: false };
}

/** Revocation, and the tests' cleanup. */
export async function remove(id: string): Promise<void> {
  const res = await call(`${ADMIN}/${id}`, "DELETE");
  if (!res.ok && res.status !== 404) {
    throw new Error(`deleting ${id} failed: ${JSON.stringify(res.json)}`);
  }
}

if (import.meta.main) {
  const args = Deno.args.filter((a) => !a.startsWith("--"));
  const rotate = Deno.args.includes("--rotate");
  const result = await provision(args[0] ?? "tablet", { rotate });

  console.log(`\nidentity : ${result.id}`);
  console.log(`email    : ${result.email}`);
  if (result.password) {
    console.log(`password : ${result.password}`);
    console.log(
      `\nShown once, stored nowhere. Type it into the tablet at setup.\n` +
        `Lost it? Run again with --rotate.\n`,
    );
  } else {
    console.log(
      `\nAlready provisioned; its password is not recoverable.\n` +
        `Run again with --rotate to set a new one.\n`,
    );
  }
}
