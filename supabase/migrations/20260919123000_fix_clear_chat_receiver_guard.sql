-- Phase 7: Fix Clear chat for everyone receiver-side security guard conflict.
-- The RPC validates the conversation and sets a transaction-local marker so the
-- existing message trigger permits only this controlled soft-delete transition.

create or replace function private.messages_security_guard()
returns trigger
language plpgsql
security definer
set search_path=''
as $$
declare
  uid uuid := auth.uid();
  is_friend boolean;
  clear_all boolean := coalesce(current_setting('app.clear_chat_for_everyone', true), 'false') = 'true';
begin
  if uid is null then return new; end if;

  if tg_op='INSERT' then
    if new.sender_id<>uid or new.receiver_id=new.sender_id then raise exception 'Invalid message sender or recipient'; end if;
    if exists(select 1 from public.user_blocks b where (b.blocker_id=new.sender_id and b.blocked_id=new.receiver_id) or (b.blocker_id=new.receiver_id and b.blocked_id=new.sender_id)) then raise exception 'Messaging is blocked'; end if;
    select exists(select 1 from public.friendships f where f.status='accepted' and ((f.requester_id=new.sender_id and f.addressee_id=new.receiver_id) or (f.addressee_id=new.sender_id and f.requester_id=new.receiver_id))) into is_friend;
    if not is_friend then raise exception 'Messages require an accepted friendship'; end if;
    if new.message_type not in ('text','image','video','audio','file') then raise exception 'Invalid message type'; end if;
    if char_length(coalesce(new.body,''))>2000 then raise exception 'Message is too long'; end if;
    if new.message_type='text' and char_length(btrim(coalesce(new.body,'')))=0 then raise exception 'Text message cannot be empty'; end if;
    if new.message_type<>'text' and new.media_url is null then raise exception 'Media URL is required'; end if;
    if new.media_url is not null and (char_length(new.media_url)>4096 or new.media_url !~ '^https://') then raise exception 'Invalid media URL'; end if;
    if new.media_name is not null and char_length(new.media_name)>255 then raise exception 'Media name is too long'; end if;
    if new.media_size is not null and (new.media_size<0 or new.media_size>52428800) then raise exception 'Invalid media size'; end if;
    if new.reply_to_id is not null and not exists(select 1 from public.messages m where m.id=new.reply_to_id and ((m.sender_id=new.sender_id and m.receiver_id=new.receiver_id) or (m.sender_id=new.receiver_id and m.receiver_id=new.sender_id))) then raise exception 'Reply target is outside this conversation'; end if;
    return new;
  end if;

  if new.id is distinct from old.id or new.sender_id is distinct from old.sender_id or new.receiver_id is distinct from old.receiver_id or new.created_at is distinct from old.created_at
  or new.reply_to_id is distinct from old.reply_to_id or new.message_type is distinct from old.message_type or new.media_url is distinct from old.media_url or new.media_name is distinct from old.media_name or new.media_size is distinct from old.media_size
  then raise exception 'Message identity/content metadata cannot be changed'; end if;

  if clear_all and ((old.sender_id=uid or old.receiver_id=uid) and (new.sender_id=old.sender_id and new.receiver_id=old.receiver_id)) then
    if new.body is distinct from old.body or new.deleted_at is distinct from old.deleted_at then
      if old.deleted_at is null and new.deleted_at is not null and new.body='This message was deleted' then
        new.deleted_at=now();
        new.body='This message was deleted';
        new.edited_at=old.edited_at;
        return new;
      end if;
      raise exception 'Invalid clear-chat message transition';
    end if;
  end if;

  if uid=old.sender_id then
    if new.read_at is distinct from old.read_at or new.delivered_at is distinct from old.delivered_at then raise exception 'Sender cannot change delivery state'; end if;
    if new.deleted_at is distinct from old.deleted_at then
      if old.deleted_at is not null or new.deleted_at is null then raise exception 'Invalid message delete transition'; end if;
      new.deleted_at=now(); new.body='This message was deleted'; new.edited_at=old.edited_at; return new;
    end if;
    if new.body is distinct from old.body then
      if old.deleted_at is not null then raise exception 'Deleted messages cannot be edited'; end if;
      if char_length(btrim(coalesce(new.body,'')))=0 or char_length(new.body)>2000 then raise exception 'Invalid message body'; end if;
      new.edited_at=now();
    else new.edited_at=old.edited_at; end if;
    return new;
  end if;

  if uid=old.receiver_id then
    if new.body is distinct from old.body or new.edited_at is distinct from old.edited_at or new.deleted_at is distinct from old.deleted_at
    or new.reply_to_id is distinct from old.reply_to_id or new.message_type is distinct from old.message_type or new.media_url is distinct from old.media_url or new.media_name is distinct from old.media_name or new.media_size is distinct from old.media_size
    then raise exception 'Receiver cannot change message content'; end if;
    if new.delivered_at is distinct from old.delivered_at then new.delivered_at=now(); end if;
    if new.read_at is distinct from old.read_at then
      if new.read_at is null then new.read_at=old.read_at; else new.read_at=now(); if new.delivered_at is null then new.delivered_at=coalesce(old.delivered_at,now()); end if; end if;
    end if;
    return new;
  end if;

  raise exception 'Not a message participant';
end $$;
revoke all on function private.messages_security_guard() from public,anon;
grant execute on function private.messages_security_guard() to authenticated;

create or replace function public.clear_chat_for_everyone(p_other_user_id uuid)
returns void
language plpgsql
security definer
set search_path=''
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null or p_other_user_id is null or p_other_user_id=uid then
    raise exception 'Invalid chat participant';
  end if;
  if not exists (
    select 1 from public.messages m
    where (m.sender_id=uid and m.receiver_id=p_other_user_id)
       or (m.sender_id=p_other_user_id and m.receiver_id=uid)
  ) then
    raise exception 'Chat not found';
  end if;
  perform set_config('app.clear_chat_for_everyone','true',true);
  update public.messages
  set body='This message was deleted', deleted_at=coalesce(deleted_at,now())
  where (sender_id=uid and receiver_id=p_other_user_id)
     or (sender_id=p_other_user_id and receiver_id=uid);
end
$$;
revoke execute on function public.clear_chat_for_everyone(uuid) from public,anon;
grant execute on function public.clear_chat_for_everyone(uuid) to authenticated;
