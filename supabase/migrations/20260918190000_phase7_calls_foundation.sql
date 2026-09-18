-- Phase 7: secure 1-to-1 call foundation
create table if not exists public.call_sessions (
  id uuid primary key default gen_random_uuid(),
  caller_id uuid not null references auth.users(id) on delete cascade,
  callee_id uuid not null references auth.users(id) on delete cascade,
  call_type text not null check (call_type in ('voice','video')),
  status text not null default 'ringing' check (status in ('ringing','accepted','declined','missed','ended','failed','cancelled')),
  started_at timestamptz,
  ended_at timestamptz,
  duration_seconds integer not null default 0 check (duration_seconds >= 0 and duration_seconds <= 86400),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint call_sessions_no_self_call check (caller_id <> callee_id)
);

create index if not exists idx_call_sessions_caller_created on public.call_sessions(caller_id, created_at desc);
create index if not exists idx_call_sessions_callee_created on public.call_sessions(callee_id, created_at desc);
create index if not exists idx_call_sessions_active_callee on public.call_sessions(callee_id, status) where status = 'ringing';

alter table public.call_sessions enable row level security;
alter table public.call_sessions force row level security;

revoke all on public.call_sessions from anon;
revoke all on public.call_sessions from authenticated;
grant select, insert, update on public.call_sessions to authenticated;

drop policy if exists call_sessions_select_participant on public.call_sessions;
create policy call_sessions_select_participant on public.call_sessions
for select to authenticated using (auth.uid() = caller_id or auth.uid() = callee_id);

drop policy if exists call_sessions_insert_caller on public.call_sessions;
create policy call_sessions_insert_caller on public.call_sessions
for insert to authenticated with check (
  caller_id = auth.uid()
  and caller_id <> callee_id
  and exists (
    select 1 from public.friendships f
    where ((f.requester_id = auth.uid() and f.addressee_id = callee_id)
       or (f.requester_id = callee_id and f.addressee_id = auth.uid()))
      and f.status = 'accepted'
  )
  and not exists (
    select 1 from public.user_blocks b
    where (b.blocker_id = auth.uid() and b.blocked_id = callee_id)
       or (b.blocker_id = callee_id and b.blocked_id = auth.uid())
  )
);

create or replace function private.call_sessions_security_guard()
returns trigger language plpgsql security definer set search_path = public, private
as $$
begin
  if tg_op = 'INSERT' then
    if new.caller_id <> auth.uid() then raise exception 'CALL_FORBIDDEN'; end if;
    if new.status <> 'ringing' then raise exception 'CALL_INVALID_STATUS'; end if;
    if new.started_at is not null or new.ended_at is not null then raise exception 'CALL_INVALID_TIMES'; end if;
    return new;
  end if;
  if tg_op = 'UPDATE' then
    if auth.uid() <> old.caller_id and auth.uid() <> old.callee_id then raise exception 'CALL_FORBIDDEN'; end if;
    if new.caller_id <> old.caller_id or new.callee_id <> old.callee_id or new.call_type <> old.call_type or new.created_at <> old.created_at then
      raise exception 'CALL_IMMUTABLE_FIELDS';
    end if;
    if old.status in ('ended','failed','declined','missed','cancelled') then raise exception 'CALL_FINALIZED'; end if;
    if auth.uid() = old.callee_id and new.status not in ('accepted','declined','missed','ended','failed','cancelled') then raise exception 'CALL_INVALID_STATUS'; end if;
    if auth.uid() = old.caller_id and new.status not in ('cancelled','ended','failed') then raise exception 'CALL_INVALID_STATUS'; end if;
    if new.started_at is not null and new.started_at > now() + interval '5 minutes' then raise exception 'CALL_INVALID_TIME'; end if;
    if new.ended_at is not null and new.ended_at > now() + interval '5 minutes' then raise exception 'CALL_INVALID_TIME'; end if;
    new.updated_at := now();
    if new.ended_at is not null and new.started_at is not null then
      new.duration_seconds := greatest(0, least(86400, floor(extract(epoch from (new.ended_at-new.started_at)))::integer));
    else
      new.duration_seconds := old.duration_seconds;
    end if;
    return new;
  end if;
  return new;
end $$;

drop trigger if exists call_sessions_security_guard on public.call_sessions;
create trigger call_sessions_security_guard before insert or update on public.call_sessions
for each row execute function private.call_sessions_security_guard();

create or replace function public.create_call(p_callee_id uuid, p_call_type text)
returns public.call_sessions language plpgsql
as $$
declare r public.call_sessions;
begin
  if auth.uid() is null then raise exception 'AUTH_REQUIRED'; end if;
  if p_call_type not in ('voice','video') then raise exception 'CALL_INVALID_TYPE'; end if;
  insert into public.call_sessions(caller_id, callee_id, call_type)
  values (auth.uid(), p_callee_id, p_call_type)
  returning * into r;
  return r;
end $$;

drop policy if exists call_sessions_update_participant on public.call_sessions;
create policy call_sessions_update_participant on public.call_sessions
for update to authenticated using (auth.uid() = caller_id or auth.uid() = callee_id) with check (auth.uid() = caller_id or auth.uid() = callee_id);

create or replace function public.update_call_status(p_call_id uuid, p_status text)
returns public.call_sessions language plpgsql
as $$
declare r public.call_sessions;
begin
  if auth.uid() is null then raise exception 'AUTH_REQUIRED'; end if;
  update public.call_sessions
     set status = p_status,
         started_at = case when p_status = 'accepted' and started_at is null then now() else started_at end,
         ended_at = case when p_status in ('ended','failed','declined','missed','cancelled') then coalesce(ended_at, now()) else ended_at end,
         updated_at = now()
   where id = p_call_id and (caller_id = auth.uid() or callee_id = auth.uid())
  returning * into r;
  if r.id is null then raise exception 'CALL_NOT_FOUND'; end if;
  return r;
end $$;

revoke all on function public.create_call(uuid,text) from public, anon;
revoke all on function public.update_call_status(uuid,text) from public, anon;
grant execute on function public.create_call(uuid,text) to authenticated;
grant execute on function public.update_call_status(uuid,text) to authenticated;
