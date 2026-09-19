-- Fix Clear chat for everyone: clear the complete conversation for both participants.
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
    select 1
    from public.messages m
    where (m.sender_id=uid and m.receiver_id=p_other_user_id)
       or (m.sender_id=p_other_user_id and m.receiver_id=uid)
  ) then
    raise exception 'Chat not found';
  end if;

  update public.messages
  set body='This message was deleted',
      deleted_at=coalesce(deleted_at,now())
  where (sender_id=uid and receiver_id=p_other_user_id)
     or (sender_id=p_other_user_id and receiver_id=uid);
end
$$;

revoke execute on function public.clear_chat_for_everyone(uuid) from public,anon;
grant execute on function public.clear_chat_for_everyone(uuid) to authenticated;
