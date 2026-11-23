-- Make the reminder sweep actually run.
--
-- 0005 scheduled `toffee-sweep` to post to
-- `current_setting('app.settings.sweep_url', true)`. That setting was never
-- set, and on Supabase it never could be: the `postgres` role is refused
-- permission to set a custom parameter, by either ALTER DATABASE or ALTER
-- ROLE. The `true` argument means "answer NULL rather than complain", so
-- the job registered cleanly and then failed every single minute with
--
--   null value in column "url" of relation "http_request_queue"
--
-- for 4,158 consecutive runs, zero successes, `net._http_response` empty
-- the whole time. Reminders still rang, which is what hid it: the tablet's
-- own alarm is an independent path. What silently never worked was anything
-- due while the tablet was asleep, `fired_at` ever being set, recurring
-- reminders advancing, and the sweep's broadcast.
--
-- Three changes, and the last is the one that stops it coming back.
--
-- The URL now comes from `app_config`, seeded by scripts/migrate.ts out of
-- .env. It is not a secret — it is compiled into the APK, which is public —
-- but it carries the project ref, which does not belong in a migration file
-- in a public repo.
--
-- The service key is gone. `reminders-sweep` is deployed with
-- `verify_jwt: false` and answers HTTP 200 to a request carrying no
-- credentials at all, so that header never bought anything; it only put the
-- service key somewhere new. It was also the other half of the same bug,
-- since `'Bearer ' || NULL` is itself NULL.
--
-- And the lookup is asserted before the schedule is written. A missing URL
-- now stops `migrate.ts` with an instruction, rather than surfacing three
-- days later as a reminder nobody received.

do $$
begin
  if not exists (select 1 from app_config where key = 'sweep_url' and value <> '') then
    raise exception
      'app_config has no sweep_url, so the sweep cron would silently do nothing. scripts/migrate.ts seeds it from SUPABASE_URL in .env — check that .env is present and re-run it.';
  end if;
end
$$;

select cron.unschedule('toffee-sweep')
where exists (select 1 from cron.job where jobname = 'toffee-sweep');

select cron.schedule(
  'toffee-sweep',
  '* * * * *',
  $$
  select net.http_post(
    url := (select value from app_config where key = 'sweep_url'),
    headers := jsonb_build_object('Content-Type', 'application/json'),
    body := '{}'::jsonb
  );
  $$
);
