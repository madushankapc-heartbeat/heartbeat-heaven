-- Fix group media path validation.
-- The previous trigger used a backslash-escaped dot inside a PL/pgSQL
-- string literal; the resulting regex rejected valid UUID.ext paths.
create or replace function public.validate_group_message_media()
returns trigger
language plpgsql
set search_path to 'public', 'pg_temp'
as $function$
declare
  expected_prefix text;
begin
  if new.message_type = 'text' then
    if new.media_path is not null then
      raise exception 'Text group messages cannot contain media_path';
    end if;
    return new;
  end if;

  if new.message_type not in ('image','video') then
    if new.media_path is not null then
      raise exception 'Unsupported group media type';
    end if;
    return new;
  end if;

  if new.media_path is null or btrim(new.media_path) = '' then
    raise exception 'Media path is required for group media messages';
  end if;

  expected_prefix := new.group_id::text || '/';

  if left(new.media_path, length(expected_prefix)) <> expected_prefix then
    raise exception 'Group media path must belong to the same group';
  end if;

  if new.media_path !~ (
    '^' || new.group_id::text ||
    '/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}[.](jpg|jpeg|png|webp|gif|mp4|webm|mov)$'
  ) then
    raise exception 'Invalid group media path';
  end if;

  return new;
end;
$function$;