-- Where the builds themselves live.
--
-- `app_release` says which build the tablet should be running; this is the
-- bucket the row points at. Private, because a public bucket would hand the
-- app binary to anyone who guessed the path, and the tablet already has an
-- identity that can prove it is the tablet.
--
-- The Updater sends its `apikey` and `Authorization` headers only to urls
-- that start with the project's own base url, which a Storage object url
-- does — so a private bucket works with no extra machinery on the device.

insert into storage.buckets (id, name, public)
values ('builds', 'builds', false)
on conflict (id) do nothing;

-- The same rule as the release row itself: the device may read builds, and
-- nothing else, and no verb but select. Uploading is the secret key's job,
-- which bypasses RLS.
create policy device_read_builds on storage.objects
  for select to authenticated
  using (
    bucket_id = 'builds'
    and (select auth.jwt()) -> 'app_metadata' ->> 'device' = 'true'
  );
