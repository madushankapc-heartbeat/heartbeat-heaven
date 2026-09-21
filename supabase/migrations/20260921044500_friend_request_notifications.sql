-- Create a notification automatically when a pending friend request is created.
create or replace function private.create_friend_request_notification()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
  if new.status = 'pending' then
    insert into public.notifications (
      recipient_id,
      actor_id,
      kind,
      title,
      body,
      entity_id
    )
    values (
      new.addressee_id,
      new.requester_id,
      'friend_request',
      'New friend request',
      'You have a new friend request.',
      new.id
    );
  end if;

  return new;
end;
$$;

drop trigger if exists friendship_request_notification on public.friendships;

create trigger friendship_request_notification
after insert on public.friendships
for each row
when (new.status = 'pending')
execute function private.create_friend_request_notification();

revoke execute on function private.create_friend_request_notification() from public, anon, authenticated;
