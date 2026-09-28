-- Step 2: private group profile pictures.
-- groups.photo_path already exists; this migration adds only the dedicated private storage bucket and policies.

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('group-profile-pictures','group-profile-pictures',false,5242880,array['image/jpeg','image/png','image/webp','image/gif'])
on conflict (id) do update set public=excluded.public,file_size_limit=excluded.file_size_limit,allowed_mime_types=excluded.allowed_mime_types;

drop policy if exists group_profile_pictures_select_member on storage.objects;
create policy group_profile_pictures_select_member on storage.objects for select to authenticated
using (bucket_id='group-profile-pictures' and group_is_member(((storage.foldername(name))[1])::uuid));

drop policy if exists group_profile_pictures_insert_owner on storage.objects;
create policy group_profile_pictures_insert_owner on storage.objects for insert to authenticated
with check (bucket_id='group-profile-pictures' and group_is_owner(((storage.foldername(name))[1])::uuid));

drop policy if exists group_profile_pictures_update_owner on storage.objects;
create policy group_profile_pictures_update_owner on storage.objects for update to authenticated
using (bucket_id='group-profile-pictures' and group_is_owner(((storage.foldername(name))[1])::uuid))
with check (bucket_id='group-profile-pictures' and group_is_owner(((storage.foldername(name))[1])::uuid));

drop policy if exists group_profile_pictures_delete_owner on storage.objects;
create policy group_profile_pictures_delete_owner on storage.objects for delete to authenticated
using (bucket_id='group-profile-pictures' and group_is_owner(((storage.foldername(name))[1])::uuid));
