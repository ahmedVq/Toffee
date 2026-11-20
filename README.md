# Toffee

A desk companion that lives on an old Android tablet, keeps Ahmed's tasks and
reminders, watches whether he is at the desk, and is deliberately unkind about
all of it.

This repository is the **backend**: the database, the agent, and its tools.
The tablet app is a separate plan.

## Layout

```
supabase/
  migrations/        schema, RLS, memory search, cron
  functions/
    _shared/         the agent: llm, persona, governor, tools, handlers
    turn/            POST /turn   — Ahmed said something
    event/           POST /event  — something happened; speak or stay quiet
    reminders-sweep/ called by pg_cron every minute
scripts/
  migrate.ts         applies migrations over the Management API
  deploy.ts          deploys functions over the Management API
  talk.sh            say something to it
tests/               deno tests, run against the hosted project
```

## Running the tests

```
deno test -A tests/
```

They run against the live Supabase project and clean up after themselves —
every fixture is prefixed `zz` and wiped by prefix, so a failed run cannot
poison the next one.

## Deploying

There is no Supabase CLI here: Windows Smart App Control blocks the binary, so
everything goes through the Management API instead.

```
deno run -A scripts/migrate.ts     # apply pending migrations
deno run -A scripts/deploy.ts      # deploy all three functions
```

Migrations must not contain their own transaction control — the API wraps each
file in one implicit transaction, which is what makes a failed migration leave
nothing behind. That is verified, not assumed.

## Secrets

`.env` at the repo root, gitignored:

```
SUPABASE_URL=
SUPABASE_PROJECT_REF=
SUPABASE_PUBLISHABLE_KEY=
SUPABASE_SECRET_KEY=
SUPABASE_ACCESS_TOKEN=
GEMINI_API_KEY=
```

Supabase reserves every `SUPABASE_*` secret name and injects its own into Edge
Functions, so only `GEMINI_API_KEY` is set as a function secret. In production
the service key arrives as `SUPABASE_SERVICE_ROLE_KEY`; `db.ts` accepts either
name.

## The two loops

```
Ahmed speaks     → /turn  → tools → reply
Something happens → /event → governor → maybe speak
```

The second is what makes it a companion rather than a voice assistant. The
governor is consulted **before** the model is called — deciding not to speak
once the reply exists means paying for it anyway.

It suppresses opinions and never obligations: a reminder bypasses the
cooldown, the repeat check and the budget, because a timer that stays quiet
when the agent was chatty earlier is a timer you cannot trust.

## The rule that makes the personality work

Toffee may only mock what it can point at — a task untouched for days, the
hour, how long he has been sitting there. A claim it cannot substantiate is
not made. Generic cruelty is not funny; specific cruelty is.

## Known gaps

- **The paraphrase test for memory has never run.** `Supabase.ai` exists only
  inside the Edge runtime, so local tests inject a fake embedder and prove the
  plumbing — 384 dimensions, ranking by vector distance rather than keyword.
  Whether gte-small places paraphrases near each other is unverified until
  something exercises `recall` through a deployed function.
- `sweepHandler` returns reminders as they were read, so `firedAt` reads null
  in the response even though the row is stamped. Cosmetic.
