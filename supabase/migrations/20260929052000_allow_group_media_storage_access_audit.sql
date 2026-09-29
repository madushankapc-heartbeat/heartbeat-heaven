-- Allow secure group-media access to be recorded in the same private audit table.
-- Keep an explicit bucket allowlist; do not grant direct client execute access.
create or replace function public.log_storage_access(
  p_accessor_id uuid,
  p_access_mode text,
  p_bucket_id text,
  p_object_path text,
  p_message_id uuid,
  p_reason text,
  p_expires_at timestamp with time zone
)
returns void
language plpgsql
security definer
set search_path to 'private'
as $function$
begin
  if p_accessor_id is null then
    raise exception 'accessor_id is required';
  end if;

  if p_access_mode not in ('user', 'admin') then
    raise exception 'invalid access_mode';
  end if;

  if p_bucket_id not in ('chat-media', 'group-media') then
    raise exception 'invalid bucket';
  end if;

  insert into storage_access_audit(
    accessor_id, access_mode, bucket_id, object_path,
    message_id, reason, expires_at
  )
  values (
    p_accessor_id, p_access_mode, p_bucket_id, p_object_path,
    p_message_id,
    case when p_access_mode = 'admin' then left(coalesce(p_reason,''), 500) else null end,
    p_expires_at
  );
end;
$function$;
