alter table public.profiles add column if not exists avatar_url text;

insert into storage.buckets (id, name, public) values ('profile-pictures', 'profile-pictures', true) on conflict (id) do update set public = true;

drop policy if exists profile_pictures_insert_own on storage.objects;
create policy profile_pictures_insert_own on storage.objects for insert to authenticated with check (bucket_id = 'profile-pictures' and (storage.foldername(name))[1] = (select auth.uid()::text));
drop policy if exists profile_pictures_select_own_metadata on storage.objects;
create policy profile_pictures_select_own_metadata on storage.objects for select to authenticated using (bucket_id = 'profile-pictures' and (storage.foldername(name))[1] = (select auth.uid()::text));
drop policy if exists profile_pictures_update_own on storage.objects;
create policy profile_pictures_update_own on storage.objects for update to authenticated using (bucket_id = 'profile-pictures' and (storage.foldername(name))[1] = (select auth.uid()::text)) with check (bucket_id = 'profile-pictures' and (storage.foldername(name))[1] = (select auth.uid()::text));
drop policy if exists profile_pictures_delete_own on storage.objects;
create policy profile_pictures_delete_own on storage.objects for delete to authenticated using (bucket_id = 'profile-pictures' and (storage.foldername(name))[1] = (select auth.uid()::text));

drop policy if exists messages_blocked_users_denied on public.messages;
create policy messages_blocked_users_denied on public.messages as restrictive for all to authenticated using (not exists (select 1 from public.user_blocks b where (b.blocker_id = (select auth.uid()) and b.blocked_id in (messages.sender_id, messages.receiver_id)) or (b.blocked_id = (select auth.uid()) and b.blocker_id in (messages.sender_id, messages.receiver_id)))) with check (not exists (select 1 from public.user_blocks b where (b.blocker_id = (select auth.uid()) and b.blocked_id in (messages.sender_id, messages.receiver_id)) or (b.blocked_id = (select auth.uid()) and b.blocker_id in (messages.sender_id, messages.receiver_id))));