drop policy if exists stories_update_own on public.stories;
create policy stories_update_own on public.stories for update to authenticated
using (user_id = auth.uid())
with check (user_id = auth.uid() and expires_at <= now() + interval '48 hours' and expires_at > now());
