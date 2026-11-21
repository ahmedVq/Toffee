-- Which build the tablet should be running.
--
-- Device Owner can install an APK silently, which is the only maintenance
-- route that survives the kiosk: there is no Settings app, no launcher, and
-- the USB connection has already proved unreliable once. Without this, every
-- change needs physical access and a cable that charges slower than the
-- screen drains.
--
-- The row is the remote switch. Nothing installs until `active` is set, so a
-- build can be uploaded, checked, and only then pointed at the tablet.

create table app_release (
  id           serial primary key,
  version_code integer not null,
  url          text not null,
  sha256       text not null,
  notes        text,
  active       boolean not null default false,
  created_at   timestamptz not null default now()
);

-- "Which build is live" is a single fact, not a race between two rows that
-- both claim to be it.
create unique index app_release_one_active on app_release (active) where active;

alter table app_release enable row level security;

-- The device may read the live row and nothing else: not the history, not a
-- build that has been staged but not released, and never any verb but select.
-- Writing is the secret key's job, which bypasses RLS.
create policy device_select_app_release on app_release
  for select to authenticated
  using (
    (select auth.jwt()) -> 'app_metadata' ->> 'device' = 'true'
    and active
  );
