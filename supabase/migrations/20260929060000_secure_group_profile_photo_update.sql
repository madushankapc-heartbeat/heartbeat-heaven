-- Secure, owner-authorized group profile photo update.
-- This avoids treating a PostgREST PATCH with zero affected rows as success.
create or replace function public.set_group_photo_path(
  p_group_id uuid,
  p_photo_path text
)
returns public.groups
language plpgsql
security definer
set search_path to 'public', 'pg_temp'
as $function$
declare
  updated_group public.groups;
begin
  if auth.uid() is null then
    raise exception 'Authentication required';
  end if;

  if p_photo_path is null or btrim(p_photo_path) = '' then
    raise exception 'Photo path is required';
  end if;

  if p_photo_path !~ (
    '^' || p_group_id::text ||
    '/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}[.](jpg|jpeg|png|webp|gif)$'
  ) then
    raise exception 'Invalid group profile photo path';
  end if;

  update public.groups
     set photo_path = p_photo_path,
         updated_at = now()
   where id = p_group_id
     and owner_id = auth.uid()
   returning * into updated_group;

  if not found then
    raise exception 'Only the group owner can update the group picture';
  end if;

  return updated_group;
end;
$function$;

revoke all on function public.set_group_photo_path(uuid, text) from public;
grant execute on function public.set_group_photo_path(uuid, text) to authenticated;
