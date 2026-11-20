-- Toffee backend schema. Single-user companion: no per-user ownership columns.
-- All tables are accessed with the secret (service-role) key only, so RLS is
-- enabled with no policies, which denies the anon/publishable key entirely.

create extension if not exists vector;
create extension if not exists pg_cron;

create table profile (
  id            uuid primary key default gen_random_uuid(),
  display_name  text,
  timezone      text not null default 'UTC',
  working_hours jsonb not null default '{}'::jsonb,
  facts         jsonb not null default '{}'::jsonb
);

create table tasks (
  id         uuid primary key default gen_random_uuid(),
  title      text not null,
  status     text not null default 'open' check (status in ('open', 'done', 'cancelled')),
  due_at     timestamptz,
  project    text,
  created_at timestamptz not null default now(),
  done_at    timestamptz
);

create table reminders (
  id       uuid primary key default gen_random_uuid(),
  text     text not null,
  due_at   timestamptz not null,
  fired_at timestamptz,
  recurring text,
  source   text
);

create table presence_events (
  id         uuid primary key default gen_random_uuid(),
  at         timestamptz not null default now(),
  present    boolean not null,
  duration_s integer
);

create table memories (
  id         uuid primary key default gen_random_uuid(),
  fact       text not null,
  category   text,
  embedding  vector(384),
  created_at timestamptz not null default now()
);

create table conversation (
  id      uuid primary key default gen_random_uuid(),
  role    text not null check (role in ('user', 'assistant')),
  content text not null,
  at      timestamptz not null default now()
);

create table said_log (
  id        uuid primary key default gen_random_uuid(),
  said      text not null,
  reason    text,
  topic_key text not null,
  at        timestamptz not null default now()
);

create table llm_spend (
  id         uuid primary key default gen_random_uuid(),
  day        date not null unique,
  calls      integer not null default 0,
  tokens_in  bigint not null default 0,
  tokens_out bigint not null default 0
);

create index tasks_status_due_idx on tasks (status, due_at);
create index reminders_due_idx on reminders (due_at) where fired_at is null;
create index presence_events_at_idx on presence_events (at desc);
create index conversation_at_idx on conversation (at desc);
create index said_log_topic_at_idx on said_log (topic_key, at desc);

alter table profile enable row level security;
alter table tasks enable row level security;
alter table reminders enable row level security;
alter table presence_events enable row level security;
alter table memories enable row level security;
alter table conversation enable row level security;
alter table said_log enable row level security;
alter table llm_spend enable row level security;
