create or replace function private.enforce_chat_message_retention(
  p_sender_id uuid,
  p_receiver_id uuid
)
returns void
language plpgsql
security definer
set search_path=''
as $$
declare
  v_key text;
  v_count integer;
begin
  if p_sender_id is null or p_receiver_id is null or p_sender_id = p_receiver_id then
    return;
  end if;

  v_key := least(p_sender_id::text, p_receiver_id::text) || ':' ||
           greatest(p_sender_id::text, p_receiver_id::text);

  perform pg_advisory_xact_lock(hashtextextended(v_key, 0));

  select count(*)::integer
    into v_count
  from public.messages m
  where (m.sender_id = p_sender_id and m.receiver_id = p_receiver_id)
     or (m.sender_id = p_receiver_id and m.receiver_id = p_sender_id);

  if v_count >= 500 then
    delete from public.messages m
    where m.id in (
      select old.id
      from public.messages old
      where (old.sender_id = p_sender_id and old.receiver_id = p_receiver_id)
         or (old.sender_id = p_receiver_id and old.receiver_id = p_sender_id)
      order by old.created_at asc, old.id asc
      limit 250
    );
  end if;
end
$$;

create or replace function private.messages_chat_retention_trigger()
returns trigger
language plpgsql
security definer
set search_path=''
as $$
begin
  perform private.enforce_chat_message_retention(new.sender_id, new.receiver_id);
  return new;
end
$$;

revoke all on function private.enforce_chat_message_retention(uuid,uuid) from public, anon, authenticated;
revoke all on function private.messages_chat_retention_trigger() from public, anon, authenticated;

drop trigger if exists messages_chat_retention on public.messages;
create trigger messages_chat_retention
after insert on public.messages
for each row
execute function private.messages_chat_retention_trigger();

drop index if exists public.messages_pair_created_desc_idx;
