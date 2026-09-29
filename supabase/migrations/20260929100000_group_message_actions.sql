-- Phase B: group message actions, reactions, replies and mutation hardening.
-- Group-specific only. Existing 1-to-1 message tables/functions are untouched.

create table if not exists public.group_message_reactions (
  id uuid primary key default gen_random_uuid(),
  group_message_id uuid not null references public.group_messages(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  reaction text not null,
  created_at timestamptz not null default now(),
  constraint group_message_reactions_reaction_check check (reaction in ('👍','❤️','😂','😮','😢','😡')),
  constraint group_message_reactions_unique_user_message unique (group_message_id, user_id)
);

create index if not exists idx_group_message_reactions_message
  on public.group_message_reactions(group_message_id);

alter publication supabase_realtime add table public.group_message_reactions;

create index if not exists idx_group_message_reactions_user
  on public.group_message_reactions(user_id);

alter table public.group_message_reactions enable row level security;

drop policy if exists group_message_reactions_select_member on public.group_message_reactions;
create policy group_message_reactions_select_member
on public.group_message_reactions for select to authenticated
using (
  exists (
    select 1
    from public.group_messages gm
    where gm.id = group_message_id
      and group_is_member(gm.group_id)
  )
);

drop policy if exists group_message_reactions_insert_member on public.group_message_reactions;
create policy group_message_reactions_insert_member
on public.group_message_reactions for insert to authenticated
with check (
  user_id = auth.uid()
  and exists (
    select 1
    from public.group_messages gm
    where gm.id = group_message_id
      and group_is_member(gm.group_id)
      and gm.deleted_at is null
  )
);

drop policy if exists group_message_reactions_update_own on public.group_message_reactions;
create policy group_message_reactions_update_own
on public.group_message_reactions for update to authenticated
using (user_id = auth.uid())
with check (
  user_id = auth.uid()
  and exists (
    select 1
    from public.group_messages gm
    where gm.id = group_message_id
      and group_is_member(gm.group_id)
      and gm.deleted_at is null
  )
);

drop policy if exists group_message_reactions_delete_own on public.group_message_reactions;
create policy group_message_reactions_delete_own
on public.group_message_reactions for delete to authenticated
using (user_id = auth.uid());

create or replace function public.validate_group_message_reply()
returns trigger
language plpgsql
set search_path = public, pg_temp
as $function$
declare
  target_group uuid;
begin
  if new.reply_to_id is null then
    return new;
  end if;

  if new.reply_to_id = new.id then
    raise exception 'A message cannot reply to itself';
  end if;

  select group_id into target_group
  from public.group_messages
  where id = new.reply_to_id;

  if target_group is null then
    raise exception 'Reply target not found';
  end if;

  if target_group <> new.group_id then
    raise exception 'Reply target must belong to the same group';
  end if;

  return new;
end;
$function$;

drop trigger if exists group_messages_reply_guard on public.group_messages;
create trigger group_messages_reply_guard
before insert or update of group_id, reply_to_id
on public.group_messages
for each row execute function public.validate_group_message_reply();

create or replace function public.group_messages_prevent_identity_change()
returns trigger
language plpgsql
set search_path = public, pg_temp
as $function$
begin
  if new.group_id <> old.group_id
     or new.sender_id <> old.sender_id
     or new.created_at <> old.created_at
     or coalesce(new.reply_to_id, '00000000-0000-0000-0000-000000000000'::uuid)
        <> coalesce(old.reply_to_id, '00000000-0000-0000-0000-000000000000'::uuid) then
    raise exception 'Group message identity fields cannot be changed';
  end if;

  if old.deleted_at is not null
     and (new.body is distinct from old.body
       or new.message_type is distinct from old.message_type
       or new.media_path is distinct from old.media_path
       or new.edited_at is distinct from old.edited_at) then
    raise exception 'Deleted group messages cannot be modified';
  end if;

  if old.message_type <> 'text'
     and (new.body is distinct from old.body
       or new.message_type is distinct from old.message_type
       or new.media_path is distinct from old.media_path) then
    raise exception 'Media group messages cannot be edited';
  end if;

  return new;
end;
$function$;

drop policy if exists group_messages_update_own on public.group_messages;
create policy group_messages_update_own
on public.group_messages for update to authenticated
using (
  sender_id = auth.uid()
  and group_is_member(group_id)
  and deleted_at is null
)
with check (
  sender_id = auth.uid()
  and group_is_member(group_id)
  and deleted_at is null
);

create or replace function public.edit_my_group_message(
  p_message_id uuid,
  p_body text
)
returns public.group_messages
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_row public.group_messages;
  v_body text := btrim(coalesce(p_body, ''));
begin
  if (select auth.uid()) is null then
    raise exception 'Authentication required';
  end if;

  if length(v_body) = 0 or length(v_body) > 4000 then
    raise exception 'Message body must be 1-4000 characters';
  end if;

  update public.group_messages
     set body = v_body,
         edited_at = now()
   where id = p_message_id
     and sender_id = (select auth.uid())
     and deleted_at is null
     and message_type = 'text'
     and public.group_is_member(group_id)
   returning * into v_row;

  if not found then
    raise exception 'Group message not found or cannot be edited';
  end if;

  return v_row;
end;
$function$;

create or replace function public.delete_my_group_message(
  p_message_id uuid
)
returns public.group_messages
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_row public.group_messages;
begin
  if (select auth.uid()) is null then
    raise exception 'Authentication required';
  end if;

  update public.group_messages
     set body = 'This message was deleted',
         deleted_at = coalesce(deleted_at, now())
   where id = p_message_id
     and sender_id = (select auth.uid())
     and deleted_at is null
     and public.group_is_member(group_id)
   returning * into v_row;

  if not found then
    raise exception 'Group message not found or cannot be deleted';
  end if;

  return v_row;
end;
$function$;

create or replace function public.admin_delete_group_message(
  p_message_id uuid
)
returns public.group_messages
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_row public.group_messages;
begin
  if (select auth.uid()) is null then
    raise exception 'Authentication required';
  end if;

  update public.group_messages gm
     set body = 'This message was deleted',
         deleted_at = coalesce(deleted_at, now())
   where gm.id = p_message_id
     and gm.deleted_at is null
     and public.group_is_admin(gm.group_id, (select auth.uid()))
   returning gm.* into v_row;

  if not found then
    raise exception 'Group message not found or cannot be deleted';
  end if;

  return v_row;
end;
$function$;

create or replace function public.set_group_message_reaction(
  p_message_id uuid,
  p_reaction text
)
returns public.group_message_reactions
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_group_id uuid;
  v_row public.group_message_reactions;
  v_reaction text := btrim(coalesce(p_reaction, ''));
begin
  if (select auth.uid()) is null then
    raise exception 'Authentication required';
  end if;

  if v_reaction not in ('👍','❤️','😂','😮','😢','😡') then
    raise exception 'Unsupported reaction';
  end if;

  select group_id into v_group_id
  from public.group_messages
  where id = p_message_id
    and deleted_at is null;

  if v_group_id is null or not public.group_is_member(v_group_id, (select auth.uid())) then
    raise exception 'Group message not found or reaction not allowed';
  end if;

  insert into public.group_message_reactions(group_message_id, user_id, reaction)
  values (p_message_id, (select auth.uid()), v_reaction)
  on conflict (group_message_id, user_id)
  do update set reaction = excluded.reaction
  returning * into v_row;

  return v_row;
end;
$function$;

create or replace function public.remove_group_message_reaction(
  p_message_id uuid
)
returns void
language plpgsql
security definer
set search_path = ''
as $function$
begin
  if (select auth.uid()) is null then
    raise exception 'Authentication required';
  end if;

  delete from public.group_message_reactions
  where group_message_id = p_message_id
    and user_id = (select auth.uid());
end;
$function$;

revoke execute on function public.edit_my_group_message(uuid,text) from anon;
revoke execute on function public.delete_my_group_message(uuid) from anon;
revoke execute on function public.admin_delete_group_message(uuid) from anon;
revoke execute on function public.set_group_message_reaction(uuid,text) from anon;
revoke execute on function public.remove_group_message_reaction(uuid) from anon;

grant execute on function public.edit_my_group_message(uuid,text) to authenticated;
grant execute on function public.delete_my_group_message(uuid) to authenticated;
grant execute on function public.admin_delete_group_message(uuid) to authenticated;
grant execute on function public.set_group_message_reaction(uuid,text) to authenticated;
grant execute on function public.remove_group_message_reaction(uuid) to authenticated;
