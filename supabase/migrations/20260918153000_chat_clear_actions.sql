-- Clear an entire one-to-one chat for the current user only.
-- Messages remain for the other participant and are hidden via message_deletions.
create or replace function public.clear_chat_for_me(p_other_user_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  if auth.uid() is null or p_other_user_id is null or p_other_user_id = auth.uid() then
    raise exception 'Invalid chat participant';
  end if;

  insert into public.message_deletions (message_id, user_id)
  select m.id, auth.uid()
  from public.messages m
  where (m.sender_id = auth.uid() and m.receiver_id = p_other_user_id)
     or (m.sender_id = p_other_user_id and m.receiver_id = auth.uid())
  on conflict (message_id, user_id) do nothing;
end;
$$;

-- Clear the entire one-to-one chat for both participants.
create or replace function public.clear_chat_for_everyone(p_other_user_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  if auth.uid() is null or p_other_user_id is null or p_other_user_id = auth.uid() then
    raise exception 'Invalid chat participant';
  end if;

  delete from public.messages m
  where (m.sender_id = auth.uid() and m.receiver_id = p_other_user_id)
     or (m.sender_id = p_other_user_id and m.receiver_id = auth.uid());
end;
$$;

revoke all on function public.clear_chat_for_me(uuid) from public;
grant execute on function public.clear_chat_for_me(uuid) to authenticated;

revoke all on function public.clear_chat_for_everyone(uuid) from public;
grant execute on function public.clear_chat_for_everyone(uuid) to authenticated;
