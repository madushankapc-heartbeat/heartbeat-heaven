-- Phase 7: per-user voice/video call blocking
create table if not exists public.call_blocks (
  blocker_id uuid not null references auth.users(id) on delete cascade,
  blocked_id uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (blocker_id, blocked_id),
  constraint call_blocks_no_self check (blocker_id <> blocked_id)
);

alter table public.call_blocks enable row level security;
alter table public.call_blocks force row level security;

revoke all on public.call_blocks from anon;
revoke all on public.call_blocks from authenticated;
grant select, insert, delete on public.call_blocks to authenticated;

drop policy if exists call_blocks_select_own on public.call_blocks;
create policy call_blocks_select_own on public.call_blocks
for select to authenticated
using (auth.uid() = blocker_id or auth.uid() = blocked_id);

drop policy if exists call_blocks_insert_own on public.call_blocks;
create policy call_blocks_insert_own on public.call_blocks
for insert to authenticated
with check (auth.uid() = blocker_id and blocker_id <> blocked_id);

drop policy if exists call_blocks_delete_own on public.call_blocks;
create policy call_blocks_delete_own on public.call_blocks
for delete to authenticated
using (auth.uid() = blocker_id);

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
  and not exists (
    select 1 from public.call_blocks b
    where (b.blocker_id = auth.uid() and b.blocked_id = callee_id)
       or (b.blocker_id = callee_id and b.blocked_id = auth.uid())
  )
);