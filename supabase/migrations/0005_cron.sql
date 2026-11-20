-- Fire due reminders every minute.
--
-- 0005, not 0004: 0004 is memory_search. The plan said 0004 for cron, written
-- before the conversation-role fix took 0002 and shifted everything.
--
-- The schedule carries no lower bound of its own; catching up on anything
-- missed while the project was paused is the sweep's job, not cron's.

create extension if not exists pg_net;

select cron.unschedule('toffee-sweep')
where exists (select 1 from cron.job where jobname = 'toffee-sweep');

select cron.schedule(
  'toffee-sweep',
  '* * * * *',
  $$
  select net.http_post(
    url := current_setting('app.settings.sweep_url', true),
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'Authorization', 'Bearer ' || current_setting('app.settings.service_key', true)
    ),
    body := '{}'::jsonb
  );
  $$
);
