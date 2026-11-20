# Toffee Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A working Toffee agent reachable over HTTP — it holds tasks, reminders and memories, decides when to speak, and answers in character — before any Android code exists.

**Architecture:** Supabase hosted project. Postgres holds the data with RLS scoped so the tablet's identity can read only its own rows. Deno Edge Functions hold the agent: prompt assembly, the tool-calling loop against Gemini, the governor that decides whether to speak at all, and a cron-driven reminder sweep that pushes over Realtime.

**Tech Stack:** Supabase (Postgres 15, Edge Functions/Deno, pg_cron, pgvector, Realtime), Gemini Flash via AI Studio, Supabase built-in `gte-small` for embeddings. Deno's own test runner.

**Spec:** `docs/superpowers/specs/2026-11-20-toffee-design.md`

## Global Constraints

- **Zero budget.** Free tiers only. No paid service may be introduced.
- **No provider key ever reaches the device.** The Gemini key and the Supabase secret key exist only as Edge Function secrets.
- **The device identity may not read `memories` raw or `said_log` at all.** RLS enforces this, not application code.
- **Embeddings use Supabase's built-in `gte-small`** inside Edge Functions. No external embedding API.
- **The evidence rule:** the persona prompt must forbid claims the agent cannot substantiate from context or tool results.
- **The governor has two dimensions:** annoyance (how recently it spoke, whether it already made this point) and cost (how much LLM budget today has left).
- **Timezone is `Africa/Cairo`.** All human-facing times resolve in that zone; storage is UTC.
- **No local Supabase.** Docker is unavailable. Migrations are pushed to the hosted project; tests either run pure or hit that project.

## Review Focus

Inputs the spec implies but no task's happy path exercises. Each has a test pinned to the task that owns the code.

- **A reminder whose `due_at` passed while nothing was running** — free projects pause, Wi-Fi drops. It must fire late, not vanish. (Task 9)
- **Two tasks with similar titles** — `complete_task("the deck thing")` must refuse ambiguity rather than silently tick the wrong one. (Task 4)
- **The model returns an unknown tool name or malformed arguments** — must not crash the turn; the agent gets a tool error back and recovers. (Task 7)
- **"Remind me at 3pm" near midnight, and across a DST shift** — Cairo observes DST; a naive UTC offset puts reminders an hour out for half the year. (Task 5)
- **Free-tier quota exhausted mid-turn** — must degrade in character, not 500. (Task 7)

---

### Task 1: Toolchain, project link, and schema

**Files:**
- Create: `supabase/config.toml`
- Create: `supabase/migrations/0001_schema.sql`
- Create: `deno.json`
- Create: `tests/schema.test.ts`
- Create: `.env.example`

**Interfaces:**
- Produces: tables `profile`, `tasks`, `reminders`, `presence_events`, `memories`, `conversation`, `said_log`, `llm_spend`. Column names below are relied on by every later task.

- [ ] **Step 1: Install Deno and the Supabase CLI**

```bash
npm install -g supabase
irm https://deno.land/install.ps1 | iex    # PowerShell
```

Verify both: `deno --version`, `supabase --version`. If Deno has no Windows ARM64 build, install the x64 build — it runs under emulation.

- [ ] **Step 2: Write the failing schema test**

`tests/schema.test.ts` — connects with the secret key from `.env`, queries `information_schema.columns`, asserts each table exists with its required columns:

```ts
Deno.test("schema has every table the agent needs", async () => {
  const want = {
    profile:         ["id", "display_name", "timezone", "working_hours", "facts"],
    tasks:           ["id", "title", "status", "due_at", "project", "created_at", "done_at"],
    reminders:       ["id", "text", "due_at", "fired_at", "recurring", "source"],
    presence_events: ["id", "at", "present", "duration_s"],
    memories:        ["id", "fact", "category", "embedding", "created_at"],
    conversation:    ["id", "role", "content", "at"],
    said_log:        ["id", "said", "reason", "topic_key", "at"],
    llm_spend:       ["id", "day", "calls", "tokens_in", "tokens_out"],
  };
  // assert every table/column pair is present
});
```

- [ ] **Step 3: Run it and watch it fail**

Run: `deno test -A tests/schema.test.ts`
Expected: FAIL — relations do not exist.

- [ ] **Step 4: Write `supabase/migrations/0001_schema.sql`**

Enable `vector` and `pg_cron`. Create the eight tables above. `memories.embedding` is `vector(384)` (gte-small's dimension). `said_log.topic_key` is the dedupe key the governor matches on. `llm_spend.day` is a `date` with a unique constraint, so a daily counter upserts.

- [ ] **Step 5: Push and re-run**

```bash
supabase link --project-ref <ref>
supabase db push
deno test -A tests/schema.test.ts
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add supabase/migrations/0001_schema.sql tests/schema.test.ts deno.json .env.example
git commit -m "feat: schema for tasks, reminders, memory and spend"
```

---

### Task 2: RLS, and proving the device cannot overreach

**Files:**
- Create: `supabase/migrations/0002_rls.sql`
- Create: `tests/rls.test.ts`

**Interfaces:**
- Consumes: tables from Task 1.
- Produces: the device may `select`/`insert`/`update` `tasks` and `reminders`, `insert` `presence_events`, and `select` `profile`. It may not touch `memories`, `said_log`, `conversation` or `llm_spend` by any verb.

- [ ] **Step 1: Write the failing policy test**

`tests/rls.test.ts` — two clients, one with the publishable key acting as the device identity, one with the secret key. Assertions, each its own `Deno.test`:

```ts
"device can read its tasks"              → rows returned
"device can insert a presence event"     → no error
"device CANNOT read memories"            → error or zero rows
"device CANNOT read said_log"            → error or zero rows
"device CANNOT read llm_spend"           → error or zero rows
"device CANNOT delete another row type"  → error
```

The negative cases are the point. A policy that merely returns nothing is acceptable; silently returning data is not.

- [ ] **Step 2: Run it and watch it fail**

Run: `deno test -A tests/rls.test.ts`
Expected: FAIL — RLS not yet enabled, so the device sees everything.

- [ ] **Step 3: Write `0002_rls.sql`**

Enable RLS on all eight tables. Grant the authenticated device role exactly the verbs listed in Interfaces. Everything else stays denied by default.

- [ ] **Step 4: Push and re-run**

Run: `supabase db push && deno test -A tests/rls.test.ts`
Expected: PASS, negatives included.

- [ ] **Step 5: Commit**

---

### Task 3: Supabase client and shared types

**Files:**
- Create: `supabase/functions/_shared/db.ts`
- Create: `supabase/functions/_shared/types.ts`

**Interfaces:**
- Produces:
  - `serviceClient(): SupabaseClient` — secret key, used inside functions only.
  - `type Task = { id: string; title: string; status: "open" | "done"; dueAt: string | null; project: string | null }`
  - `type Reminder = { id: string; text: string; dueAt: string; firedAt: string | null; recurring: string | null }`
  - `type Memory = { id: string; fact: string; category: string }`
  - `type Msg = { role: "user" | "model"; content: string }` — the history shape `runTurn` takes in Task 7.
  - `type Ctx` — the context block injected into every prompt: `{ now: string; present: boolean; atDeskMinutes: number | null; tabletBattery: number | null; openTaskCount: number; lastSaid: string | null }`

- [ ] **Step 1: Write the failing test**

`tests/db.test.ts`: `"serviceClient reads a row the device cannot"` — asserts the service client can select from `said_log`, proving it bypasses the Task 2 policies.

- [ ] **Step 2: Run, fail. Step 3: Implement. Step 4: Run, pass. Step 5: Commit.**

---

### Task 4: Task tools

**Files:**
- Create: `supabase/functions/_shared/tools/tasks.ts`
- Create: `tests/tools.tasks.test.ts`

**Interfaces:**
- Consumes: `serviceClient`, `Task` from Task 3.
- Produces:
  - `listTasks(filter: "open" | "done" | "stale" | "all"): Promise<Task[]>` — `stale` means open and `created_at` older than 3 days.
  - `addTask(title: string, dueAt?: string): Promise<Task>`
  - `completeTask(titleQuery: string): Promise<Task | AmbiguousMatch | null>`
  - `uncompleteTask(titleQuery: string): Promise<Task | AmbiguousMatch | null>`
  - `deleteTask(titleQuery: string): Promise<boolean>`
  - `type AmbiguousMatch = { ambiguous: true; candidates: Task[] }`

- [ ] **Step 1: Write the failing tests**

```ts
"addTask stores and returns the task"
"listTasks('open') excludes done tasks"
"listTasks('stale') returns only open tasks older than three days"
"completeTask matches loosely: 'the deck thing' finds 'Fix the deck'"
"completeTask is idempotent — completing a done task is not an error"
"completeTask on an unknown title returns null, not a throw"
```

Review Focus test, same file:

```ts
"completeTask refuses when two tasks match equally well" → returns
  { ambiguous: true, candidates: [...] } and changes nothing in the database
```

- [ ] **Step 2: Run, fail. Step 3: Implement.**

Matching: normalise case and punctuation, then score by token overlap. If the top two scores are within 15% of each other, return `AmbiguousMatch` rather than guessing. The agent then asks which one.

- [ ] **Step 4: Run, pass. Step 5: Commit.**

---

### Task 5: Reminder and timer tools

**Files:**
- Create: `supabase/functions/_shared/tools/time.ts`
- Create: `tests/tools.time.test.ts`

**Interfaces:**
- Consumes: `serviceClient`, `Reminder`.
- Produces:
  - `parseWhen(phrase: string, nowUtc: Date, tz = "Africa/Cairo"): Date | null`
  - `setReminder(text: string, phrase: string): Promise<Reminder | null>`
  - `startTimer(minutes: number, label: string): Promise<Reminder>`
  - `listReminders(): Promise<Reminder[]>` — pending only, soonest first.
  - `cancelReminder(query: string): Promise<boolean>`
  - `nag(taskTitleQuery: string, phrase: string): Promise<Reminder | null>` — the spec's nag tool: a reminder bound to a specific task, so the agent can promise to come back to something and then actually do it.

- [ ] **Step 1: Write the failing tests**

```ts
"'in 50 minutes' resolves to now + 50m"
"'in an hour and a half' resolves to now + 90m"
"'at 3pm' resolves to today 15:00 Cairo when now is morning"
"'at 3pm' resolves to tomorrow 15:00 Cairo when now is evening"
"'tomorrow morning' resolves to tomorrow 09:00 Cairo"
"gibberish returns null rather than a wrong time"
"stored due_at is UTC, not local"
"nag('the deck thing', 'in 2 hours') stores a reminder naming that task"
"nag on an unknown task returns null rather than a free-floating reminder"
```

Review Focus test, same file:

```ts
"'at 3pm' lands at 15:00 Cairo on both sides of the DST change"
  — run with nowUtc fixed either side of Cairo's transition and assert
    the local wall-clock hour is 15 in both, not 14 in one
```

- [ ] **Step 2: Run, fail. Step 3: Implement.**

Resolve wall-clock times with `Intl.DateTimeFormat` in the `Africa/Cairo` zone rather than a fixed offset — that is what makes the DST test pass.

- [ ] **Step 4: Run, pass. Step 5: Commit.**

---

### Task 6: Memory tools with local embeddings

**Files:**
- Create: `supabase/functions/_shared/tools/memory.ts`
- Create: `supabase/migrations/0003_memory_search.sql`
- Create: `tests/tools.memory.test.ts`

**Interfaces:**
- Consumes: `serviceClient`, `Memory`.
- Produces:
  - `embed(text: string): Promise<number[]>` — via `new Supabase.ai.Session("gte-small")`, 384 dims.
  - `remember(fact: string, category: string): Promise<Memory>`
  - `recall(query: string, limit = 5): Promise<Memory[]>`
  - `updateProfile(field: string, value: string): Promise<void>`
- SQL: `match_memories(query_embedding vector(384), match_count int)` returning rows by cosine distance.

- [ ] **Step 1: Write the failing tests**

```ts
"embed returns 384 numbers"
"recall finds a fact by paraphrase, not just keyword"
   — remember("Ahmed hates standup meetings"), recall("does he like
     morning meetings") ranks it first
"recall returns [] on an empty store rather than throwing"
```

- [ ] **Step 2: Run, fail. Step 3: Implement.** The paraphrase test is the one that proves embeddings are wired, not substring search.

- [ ] **Step 4: Run, pass. Step 5: Commit.**

---

### Task 7: Gemini client with tool calling

**Files:**
- Create: `supabase/functions/_shared/llm.ts`
- Create: `tests/llm.test.ts`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `type ToolSpec = { name: string; description: string; parameters: object }`
  - `type ToolResult = { name: string; result: unknown }`
  - `runTurn(opts: { system: string; history: Msg[]; tools: ToolSpec[]; dispatch: (name: string, args: unknown) => Promise<unknown>; onSentence?: (s: string) => void }): Promise<{ reply: string; calls: ToolResult[]; spent: { in: number; out: number } }>`

`onSentence` fires at each sentence boundary while streaming, so the tablet can begin speaking before the reply finishes. This is what protects the comic timing the spec cares about.

- [ ] **Step 1: Write the failing tests**

With `fetch` stubbed, so no network and no quota:

```ts
"sends tool declarations in the request body"
"executes a functionCall from the model and sends the result back"
"loops when the model calls a second tool after the first result"
"stops after 5 tool rounds rather than looping forever"
"onSentence fires per sentence while streaming"
```

Review Focus tests, same file:

```ts
"an unknown tool name returns an error to the model and the turn continues"
   — dispatch throws; assert the turn still resolves with a reply
"malformed tool arguments do not throw out of runTurn"
"a 429 from the API resolves with a spoken fallback, not a rejection"
   — assert reply is non-empty and calls is []
```

- [ ] **Step 2: Run, fail. Step 3: Implement.** The quota case returns a fixed in-character line; it must never surface a stack trace to the tablet.

- [ ] **Step 4: Run, pass. Step 5: Commit.**

---

### Task 8: Persona, context assembly and the governor

**Files:**
- Create: `supabase/functions/_shared/persona.ts`
- Create: `supabase/functions/_shared/governor.ts`
- Create: `tests/persona.test.ts`
- Create: `tests/governor.test.ts`

**Interfaces:**
- Consumes: `Ctx` from Task 3, `said_log` and `llm_spend` from Task 1.
- Produces:
  - `SYSTEM_PROMPT: string` — the character, including the evidence rule verbatim.
  - `buildContext(): Promise<Ctx>`
  - `renderSystem(ctx: Ctx): string`
  - `maySpeak(topicKey: string, now: Date): Promise<{ ok: boolean; why: string }>`
  - `recordSaid(said: string, topicKey: string, reason: string): Promise<void>`
  - `noteSpend(inTok: number, outTok: number): Promise<void>`

- [ ] **Step 1: Write the failing tests**

`persona.test.ts`:
```ts
"renderSystem includes the current time, presence and open task count"
"SYSTEM_PROMPT contains the evidence rule"
"renderSystem names the user as Ahmed"
```

`governor.test.ts`:
```ts
"maySpeak is true when nothing has been said recently"
"maySpeak is false within the cooldown window"
"maySpeak is false for a topicKey already used today"
"maySpeak is true for a different topicKey inside the same day"
"maySpeak is false once the daily LLM call budget is spent"
"a reminder topicKey bypasses the cooldown"   // a due reminder always speaks
```

That last one matters: the governor suppresses opinions, never obligations.

- [ ] **Step 2: Run, fail. Step 3: Implement.** Cooldown and daily budget are constants at the top of `governor.ts`, so tuning needs no code reading.

- [ ] **Step 4: Run, pass. Step 5: Commit.**

---

### Task 9: Reminder sweep and cron

**Files:**
- Create: `supabase/functions/reminders-sweep/index.ts`
- Create: `supabase/migrations/0004_cron.sql`
- Create: `tests/sweep.test.ts`

**Interfaces:**
- Consumes: `serviceClient`, `Reminder`.
- Produces: `POST /reminders-sweep` → `{ fired: Reminder[] }`, marks `fired_at`, and broadcasts each on the Realtime channel `toffee` with event `reminder`.

- [ ] **Step 1: Write the failing tests**

```ts
"a reminder due in the past is returned and marked fired"
"a reminder due in the future is untouched"
"an already-fired reminder is not fired twice"
"a recurring reminder reschedules itself after firing"
```

Review Focus test, same file:

```ts
"a reminder four hours overdue still fires"
   — insert due_at = now - 4h, sweep, assert it fires. Proves a paused
     project or dropped Wi-Fi delays reminders rather than losing them.
```

- [ ] **Step 2: Run, fail. Step 3: Implement.** The sweep selects `due_at <= now() AND fired_at IS NULL` with no lower bound — the absence of a lower bound is what makes the overdue test pass.

- [ ] **Step 4: Schedule it**

`0004_cron.sql`: `cron.schedule('toffee-sweep', '* * * * *', ...)` invoking the function.

- [ ] **Step 5: Run, pass. Step 6: Commit.**

---

### Task 10: The `/turn` endpoint

**Files:**
- Create: `supabase/functions/turn/index.ts`
- Create: `supabase/functions/_shared/tools/index.ts`
- Create: `tests/turn.test.ts`

**Interfaces:**
- Consumes: everything above.
- Produces: `POST /turn { text: string }` → `{ reply: string; mood: Mood; calls: ToolResult[] }` where `type Mood = "neutral" | "happy" | "sad" | "frustrated" | "loving" | "smug" | "sleepy" | "listening"`.
- Must call `noteSpend(spent.in, spent.out)` after every `runTurn`. Without this the governor's cost dimension never trips and the free tier is spent silently.
- `tools/index.ts` exports `ALL_TOOLS: ToolSpec[]` and `dispatch(name, args)` wiring Tasks 4–6 into the shape Task 7 expects.

- [ ] **Step 1: Write the failing tests**

With the LLM stubbed:
```ts
"a turn appends both sides to conversation"
"'add buy milk to my list' results in a task existing"
"'what am I meant to be doing' calls listTasks"
"the reply carries a mood the face can render"
"an empty text body is rejected with 400, not a crash"
"a completed turn increases today's row in llm_spend"
```

- [ ] **Step 2: Run, fail. Step 3: Implement.** Mood comes back as a structured field from the model alongside the reply, not parsed out of prose.

- [ ] **Step 4: Run, pass. Step 5: Commit.**

---

### Task 11: The `/event` endpoint

**Files:**
- Create: `supabase/functions/event/index.ts`
- Create: `tests/event.test.ts`

**Interfaces:**
- Consumes: `maySpeak`, `recordSaid`, `runTurn`, `buildContext`.
- Produces: `POST /event { kind: "arrived" | "left" | "still_here" | "reminder_due" | "idle_check"; payload?: object }` → `{ speak: string | null; mood: Mood }`. `speak: null` means stay quiet.
- Also calls `recordSaid` whenever it speaks, and `noteSpend` whenever it spends. A turn that speaks without recording lets the agent repeat itself forever.

- [ ] **Step 1: Write the failing tests**

```ts
"an 'arrived' event may produce speech"
"a second 'arrived' inside the cooldown returns speak: null"
"a 'reminder_due' event always speaks, cooldown or not"
"'still_here' after two hours produces speech about sitting too long"
"an unknown event kind returns 400"
"when the governor refuses, no LLM call is made"
```

That last one is the cost dimension: refusing must happen *before* spending, not after.

- [ ] **Step 2: Run, fail. Step 3: Implement. Step 4: Run, pass. Step 5: Commit.**

---

### Task 12: Deploy and talk to it

**Files:**
- Create: `README.md`
- Create: `scripts/talk.sh`

- [ ] **Step 1: Deploy**

```bash
supabase secrets set GEMINI_API_KEY=... SUPABASE_SERVICE_KEY=...
supabase functions deploy turn event reminders-sweep
```

- [ ] **Step 2: Hold a conversation**

`scripts/talk.sh "add charge the tablet to my list"` then `scripts/talk.sh "what's on my list"`. Expected: the task exists, and the reply is in character and cites something true.

- [ ] **Step 3: Set a real reminder and wait for it**

`scripts/talk.sh "remind me in 2 minutes to stop building this"`, then watch the Realtime channel. Expected: it fires within 60 seconds of due.

- [ ] **Step 4: Write `README.md`** — what Toffee is, how to run the tests, how to deploy, and the rule that secrets live only in Edge Function secrets.

- [ ] **Step 5: Commit and push**

```bash
git push -u origin main
```
