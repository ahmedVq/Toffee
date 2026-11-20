-- Row-level security for the tablet ("device") identity.
--
-- The device is a Supabase Auth user whose JWT carries app_metadata.device =
-- true (app_metadata can only be set with the secret key, never by a client).
-- Policies target role `authenticated` AND require that claim, so an ordinary
-- self-signed-up user is denied exactly like anon. Anything without a policy
-- is denied by default; the secret key bypasses RLS entirely.
--
-- Granted to the device:
--   tasks, reminders   select / insert / update
--   presence_events    insert
--   profile            select
-- Denied (no policy): memories, said_log, conversation, llm_spend by any verb;
-- delete on everything.

create policy device_select_tasks on tasks
  for select to authenticated
  using ((select auth.jwt()) -> 'app_metadata' ->> 'device' = 'true');
create policy device_insert_tasks on tasks
  for insert to authenticated
  with check ((select auth.jwt()) -> 'app_metadata' ->> 'device' = 'true');
create policy device_update_tasks on tasks
  for update to authenticated
  using ((select auth.jwt()) -> 'app_metadata' ->> 'device' = 'true')
  with check ((select auth.jwt()) -> 'app_metadata' ->> 'device' = 'true');

create policy device_select_reminders on reminders
  for select to authenticated
  using ((select auth.jwt()) -> 'app_metadata' ->> 'device' = 'true');
create policy device_insert_reminders on reminders
  for insert to authenticated
  with check ((select auth.jwt()) -> 'app_metadata' ->> 'device' = 'true');
create policy device_update_reminders on reminders
  for update to authenticated
  using ((select auth.jwt()) -> 'app_metadata' ->> 'device' = 'true')
  with check ((select auth.jwt()) -> 'app_metadata' ->> 'device' = 'true');

create policy device_insert_presence_events on presence_events
  for insert to authenticated
  with check ((select auth.jwt()) -> 'app_metadata' ->> 'device' = 'true');

create policy device_select_profile on profile
  for select to authenticated
  using ((select auth.jwt()) -> 'app_metadata' ->> 'device' = 'true');

-- The migration ledger had no RLS. Enabled with no policies: only the secret
-- key (which bypasses RLS) can reach it, which is how scripts/migrate.ts runs.
alter table _migrations enable row level security;
