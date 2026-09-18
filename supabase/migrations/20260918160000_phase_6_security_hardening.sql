-- Phase 6: Security hardening
create schema if not exists private;

drop view if exists public.public_profiles;
create view public.public_profiles as
select p.id,p.username,p.gender,p.avatar_url,p.bio,
case
 when p.id=auth.uid() then p.last_seen_at
 when p.last_seen_visibility='everyone' then p.last_seen_at
 when p.last_seen_visibility='friends' and exists(
   select 1 from public.friendships f
   where f.status='accepted' and ((f.requester_id=auth.uid() and f.addressee_id=p.id) or (f.addressee_id=auth.uid() and f.requester_id=p.id))
 ) then p.last_seen_at
 else null
end as last_seen_at,p.last_seen_visibility
from public.profiles p
where p.id=auth.uid() or not exists(
 select 1 from public.user_blocks b
 where (b.blocker_id=auth.uid() and b.blocked_id=p.id) or (b.blocked_id=auth.uid() and b.blocker_id=p.id)
);
revoke all on table public.public_profiles from anon,authenticated;
grant select on table public.public_profiles to authenticated;

create or replace function public.get_my_profile()
returns table(id uuid,username text,gender text,age integer,phone text,avatar_url text,bio text,last_seen_at timestamptz,last_seen_visibility text)
language sql security definer set search_path=''
as $$ select p.id,p.username,p.gender,p.age,p.phone,p.avatar_url,p.bio,p.last_seen_at,p.last_seen_visibility from public.profiles p where p.id=auth.uid(); $$;
revoke execute on function public.get_my_profile() from public,anon;
grant execute on function public.get_my_profile() to authenticated;

drop policy if exists profiles_select_for_friend_search on public.profiles;
drop policy if exists profiles_select_own on public.profiles;
create policy profiles_select_own on public.profiles for select to authenticated using(auth.uid()=id);

create or replace function private.profiles_update_guard()
returns trigger language plpgsql security definer set search_path=''
as $$
begin
 if auth.uid() is null then return new; end if;
 if new.id is distinct from old.id or new.role is distinct from old.role or new.created_at is distinct from old.created_at
 or new.username is distinct from old.username or new.gender is distinct from old.gender or new.phone is distinct from old.phone or new.age is distinct from old.age
 then raise exception 'Protected profile fields cannot be changed'; end if;
 if char_length(coalesce(new.bio,''))>160 then raise exception 'Bio is too long'; end if;
 if new.last_seen_visibility not in ('everyone','friends','nobody') then raise exception 'Invalid last seen visibility'; end if;
 if new.avatar_url is not null and (char_length(new.avatar_url)>2048 or new.avatar_url !~ '^https://') then raise exception 'Invalid avatar URL'; end if;
 if new.last_seen_at is not null and new.last_seen_at>now()+interval '5 minutes' then raise exception 'Invalid last seen timestamp'; end if;
 new.updated_at=now(); return new;
end $$;
revoke all on function private.profiles_update_guard() from public,anon;
grant execute on function private.profiles_update_guard() to authenticated;
drop trigger if exists profiles_update_guard on public.profiles;
create trigger profiles_update_guard before update on public.profiles for each row execute function private.profiles_update_guard();

drop policy if exists messages_update_sender_content on public.messages;
drop policy if exists messages_update_receiver_state on public.messages;
drop policy if exists messages_insert_sender on public.messages;
drop policy if exists messages_select_own on public.messages;
drop policy if exists messages_delete_sender on public.messages;
create policy messages_select_participants on public.messages for select to authenticated using(auth.uid()=sender_id or auth.uid()=receiver_id);
create policy messages_insert_sender on public.messages for insert to authenticated with check(auth.uid()=sender_id);
create policy messages_update_sender on public.messages for update to authenticated using(auth.uid()=sender_id) with check(auth.uid()=sender_id);
create policy messages_update_receiver on public.messages for update to authenticated using(auth.uid()=receiver_id) with check(auth.uid()=receiver_id);

create or replace function private.messages_security_guard()
returns trigger language plpgsql security definer set search_path=''
as $$
declare uid uuid:=auth.uid(); is_friend boolean;
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
drop trigger if exists messages_update_guard on public.messages;
drop trigger if exists messages_reply_guard on public.messages;
drop trigger if exists messages_security_guard on public.messages;
create trigger messages_security_guard before insert or update on public.messages for each row execute function private.messages_security_guard();

drop policy if exists message_deletions_insert_own on public.message_deletions;
create policy message_deletions_insert_own on public.message_deletions for insert to authenticated with check(auth.uid()=user_id and exists(select 1 from public.messages m where m.id=message_id and (m.sender_id=auth.uid() or m.receiver_id=auth.uid())));

drop policy if exists message_reactions_blocked_users_denied on public.message_reactions;
create policy message_reactions_blocked_users_denied on public.message_reactions as restrictive for all to authenticated
using(not exists(select 1 from public.messages m join public.user_blocks b on ((b.blocker_id=auth.uid() and b.blocked_id in(m.sender_id,m.receiver_id)) or (b.blocked_id=auth.uid() and b.blocker_id in(m.sender_id,m.receiver_id))) where m.id=message_reactions.message_id))
with check(not exists(select 1 from public.messages m join public.user_blocks b on ((b.blocker_id=auth.uid() and b.blocked_id in(m.sender_id,m.receiver_id)) or (b.blocked_id=auth.uid() and b.blocker_id in(m.sender_id,m.receiver_id))) where m.id=message_reactions.message_id));

drop policy if exists friendships_update_participant on public.friendships;
drop policy if exists friendships_insert_own on public.friendships;
drop policy if exists friendships_select_own on public.friendships;
drop policy if exists friendships_delete_own on public.friendships;
create policy friendships_select_participant on public.friendships for select to authenticated using(auth.uid()=requester_id or auth.uid()=addressee_id);
create policy friendships_insert_requester on public.friendships for insert to authenticated with check(auth.uid()=requester_id);
create policy friendships_update_participant on public.friendships for update to authenticated using(auth.uid()=requester_id or auth.uid()=addressee_id) with check(auth.uid()=requester_id or auth.uid()=addressee_id);
create policy friendships_delete_participant on public.friendships for delete to authenticated using(auth.uid()=requester_id or auth.uid()=addressee_id);
create unique index if not exists friendships_pair_unique on public.friendships(least(requester_id,addressee_id),greatest(requester_id,addressee_id));

create or replace function private.friendships_security_guard()
returns trigger language plpgsql security definer set search_path=''
as $$
declare uid uuid:=auth.uid();
begin
 if uid is null then return new; end if;
 if tg_op='INSERT' then
  if new.requester_id<>uid or new.addressee_id=new.requester_id then raise exception 'Invalid friendship request'; end if;
  if new.status<>'pending' then raise exception 'New friendships must start pending'; end if;
  if exists(select 1 from public.user_blocks b where (b.blocker_id=new.requester_id and b.blocked_id=new.addressee_id) or (b.blocker_id=new.addressee_id and b.blocked_id=new.requester_id)) then raise exception 'Friendship is blocked'; end if;
  return new;
 end if;
 if new.id is distinct from old.id or new.requester_id is distinct from old.requester_id or new.addressee_id is distinct from old.addressee_id or new.created_at is distinct from old.created_at then raise exception 'Friendship identity fields cannot be changed'; end if;
 if exists(select 1 from public.user_blocks b where (b.blocker_id=new.requester_id and b.blocked_id=new.addressee_id) or (b.blocker_id=new.addressee_id and b.blocked_id=new.requester_id)) then raise exception 'Friendship is blocked'; end if;
 if uid=old.addressee_id then
  if old.status<>'pending' or new.status not in('accepted','rejected') then raise exception 'Invalid friendship transition'; end if;
 elsif uid=old.requester_id then
  if old.status<>'rejected' or new.status<>'pending' then raise exception 'Requester cannot change this friendship state'; end if;
 else raise exception 'Not a friendship participant'; end if;
 new.updated_at=now(); return new;
end $$;
revoke all on function private.friendships_security_guard() from public,anon;
grant execute on function private.friendships_security_guard() to authenticated;
drop trigger if exists friendships_update_guard on public.friendships;
drop trigger if exists friendships_security_guard on public.friendships;
create trigger friendships_security_guard before insert or update on public.friendships for each row execute function private.friendships_security_guard();

drop policy if exists chat_reports_select_own on public.chat_reports;
drop policy if exists chat_reports_insert_own on public.chat_reports;
create policy chat_reports_insert_own on public.chat_reports for insert to authenticated with check(auth.uid()=reporter_id and reporter_id<>reported_user_id);
revoke select,update,delete on table public.chat_reports from authenticated;
grant insert on table public.chat_reports to authenticated;

create or replace function private.chat_report_guard()
returns trigger language plpgsql security definer set search_path=''
as $$
begin
 if auth.uid() is null or new.reporter_id<>auth.uid() then raise exception 'Invalid report author'; end if;
 if new.reported_user_id=new.reporter_id then raise exception 'Cannot report yourself'; end if;
 if not exists(select 1 from public.profiles p where p.id=new.reported_user_id) then raise exception 'Reported user does not exist'; end if;
 new.reason=btrim(coalesce(new.reason,''));
 if char_length(new.reason)<3 or char_length(new.reason)>500 then raise exception 'Report reason must be 3-500 characters'; end if;
 if exists(select 1 from public.chat_reports r where r.reporter_id=new.reporter_id and r.reported_user_id=new.reported_user_id and r.created_at>now()-interval '24 hours') then raise exception 'A recent report for this user already exists'; end if;
 return new;
end $$;
revoke all on function private.chat_report_guard() from public,anon;
grant execute on function private.chat_report_guard() to authenticated;
drop trigger if exists chat_report_guard on public.chat_reports;
create trigger chat_report_guard before insert on public.chat_reports for each row execute function private.chat_report_guard();

alter table public.user_blocks enable row level security;
drop policy if exists user_blocks_own on public.user_blocks;
create policy user_blocks_select_own on public.user_blocks for select to authenticated using(auth.uid()=blocker_id);
create policy user_blocks_insert_own on public.user_blocks for insert to authenticated with check(auth.uid()=blocker_id and blocker_id<>blocked_id);
create policy user_blocks_delete_own on public.user_blocks for delete to authenticated using(auth.uid()=blocker_id);

revoke all on table public.messages from anon;
revoke delete on table public.messages from authenticated;
revoke all on table public.friendships from anon;
revoke all on table public.user_blocks from anon;
revoke all on table public.message_deletions from anon;
revoke all on table public.message_reactions from anon;
revoke all on table public.chat_reports from anon;

revoke execute on function public.clear_chat_for_me(uuid) from public,anon;
grant execute on function public.clear_chat_for_me(uuid) to authenticated;
revoke execute on function public.clear_chat_for_everyone(uuid) from public,anon;
grant execute on function public.clear_chat_for_everyone(uuid) to authenticated;
revoke execute on function public.delete_message_for_me(uuid) from public,anon;
grant execute on function public.delete_message_for_me(uuid) to authenticated;
revoke execute on function public.delete_message_for_everyone(uuid) from public,anon;
grant execute on function public.delete_message_for_everyone(uuid) to authenticated;
revoke execute on function public.edit_my_message(uuid,text) from public,anon;
grant execute on function public.edit_my_message(uuid,text) to authenticated;

create or replace function public.edit_my_message(p_message_id uuid,p_body text)
returns public.messages language plpgsql security definer set search_path=''
as $$
declare r public.messages; b text:=btrim(coalesce(p_body,''));
begin
 if auth.uid() is null then raise exception 'Authentication required'; end if;
 if length(b)=0 or length(b)>2000 then raise exception 'Message body must be 1-2000 characters'; end if;
 update public.messages set body=b,edited_at=now()
 where id=p_message_id and sender_id=auth.uid() and deleted_at is null and message_type='text'
 returning * into r;
 if not found then raise exception 'Message not found or cannot be edited'; end if;
 return r;
end $$;

create or replace function public.delete_message_for_everyone(p_message_id uuid)
returns public.messages language plpgsql security definer set search_path=''
as $$
declare r public.messages;
begin
 if auth.uid() is null then raise exception 'Authentication required'; end if;
 update public.messages set body='This message was deleted',deleted_at=coalesce(deleted_at,now())
 where id=p_message_id and sender_id=auth.uid() and deleted_at is null returning * into r;
 if not found then raise exception 'Message not found or cannot be deleted'; end if;
 return r;
end $$;

create or replace function public.clear_chat_for_everyone(p_other_user_id uuid)
returns void language plpgsql security definer set search_path=''
as $$
begin
 if auth.uid() is null or p_other_user_id is null or p_other_user_id=auth.uid() then raise exception 'Invalid chat participant'; end if;
 update public.messages set body='This message was deleted',deleted_at=coalesce(deleted_at,now())
 where sender_id=auth.uid() and receiver_id=p_other_user_id and deleted_at is null;
end $$;

revoke execute on function public.set_message_reaction(uuid,text) from public,anon;
grant execute on function public.set_message_reaction(uuid,text) to authenticated;
revoke execute on function public.remove_message_reaction(uuid) from public,anon;
grant execute on function public.remove_message_reaction(uuid) to authenticated;
