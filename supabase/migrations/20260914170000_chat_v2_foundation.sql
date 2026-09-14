alter table public.messages
  add column if not exists delivered_at timestamptz,
  add column if not exists edited_at timestamptz,
  add column if not exists deleted_at timestamptz,
  add column if not exists reply_to_id uuid references public.messages(id) on delete set null,
  add column if not exists message_type text not null default 'text',
  add column if not exists media_url text,
  add column if not exists media_name text,
  add column if not exists media_size bigint;

alter table public.messages drop constraint if exists messages_message_type_check;
alter table public.messages add constraint messages_message_type_check check (message_type in ('text','image','video','audio','file'));

create index if not exists messages_conversation_created_idx on public.messages (sender_id, receiver_id, created_at desc);
create index if not exists messages_receiver_unread_idx on public.messages (receiver_id, read_at, created_at desc);
create index if not exists messages_reply_to_idx on public.messages (reply_to_id);

create table if not exists public.message_reactions (
  id uuid primary key default gen_random_uuid(),
  message_id uuid not null references public.messages(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  reaction text not null,
  created_at timestamptz not null default now(),
  unique (message_id, user_id, reaction)
);
create index if not exists message_reactions_message_idx on public.message_reactions (message_id, created_at);
alter table public.message_reactions enable row level security;
drop policy if exists message_reactions_select_participants on public.message_reactions;
create policy message_reactions_select_participants on public.message_reactions for select using (exists (select 1 from public.messages m where m.id = message_id and (auth.uid() = m.sender_id or auth.uid() = m.receiver_id)));
drop policy if exists message_reactions_insert_own on public.message_reactions;
create policy message_reactions_insert_own on public.message_reactions for insert with check (auth.uid() = user_id and exists (select 1 from public.messages m where m.id = message_id and (auth.uid() = m.sender_id or auth.uid() = m.receiver_id)));
drop policy if exists message_reactions_delete_own on public.message_reactions;
create policy message_reactions_delete_own on public.message_reactions for delete using (auth.uid() = user_id);

create table if not exists public.chat_mutes (
  user_id uuid not null references public.profiles(id) on delete cascade,
  other_user_id uuid not null references public.profiles(id) on delete cascade,
  muted_until timestamptz,
  created_at timestamptz not null default now(),
  primary key (user_id, other_user_id),
  check (user_id <> other_user_id)
);
alter table public.chat_mutes enable row level security;
drop policy if exists chat_mutes_own on public.chat_mutes;
create policy chat_mutes_own on public.chat_mutes for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create table if not exists public.user_blocks (
  blocker_id uuid not null references public.profiles(id) on delete cascade,
  blocked_id uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (blocker_id, blocked_id),
  check (blocker_id <> blocked_id)
);
alter table public.user_blocks enable row level security;
drop policy if exists user_blocks_own on public.user_blocks;
create policy user_blocks_own on public.user_blocks for all using (auth.uid() = blocker_id) with check (auth.uid() = blocker_id);

create table if not exists public.chat_reports (
  id uuid primary key default gen_random_uuid(),
  reporter_id uuid not null references public.profiles(id) on delete cascade,
  reported_user_id uuid not null references public.profiles(id) on delete cascade,
  reason text not null,
  created_at timestamptz not null default now(),
  check (reporter_id <> reported_user_id)
);
create index if not exists chat_reports_reporter_idx on public.chat_reports (reporter_id, created_at desc);
alter table public.chat_reports enable row level security;
drop policy if exists chat_reports_insert_own on public.chat_reports;
create policy chat_reports_insert_own on public.chat_reports for insert with check (auth.uid() = reporter_id);
drop policy if exists chat_reports_select_own on public.chat_reports;
create policy chat_reports_select_own on public.chat_reports for select using (auth.uid() = reporter_id);

create table if not exists public.notifications (
  id uuid primary key default gen_random_uuid(),
  recipient_id uuid not null references public.profiles(id) on delete cascade,
  actor_id uuid references public.profiles(id) on delete set null,
  kind text not null,
  title text not null,
  body text,
  entity_id uuid,
  read_at timestamptz,
  created_at timestamptz not null default now()
);
create index if not exists notifications_recipient_created_idx on public.notifications (recipient_id, created_at desc);
create index if not exists notifications_unread_idx on public.notifications (recipient_id, read_at, created_at desc);
alter table public.notifications enable row level security;
drop policy if exists notifications_select_own on public.notifications;
create policy notifications_select_own on public.notifications for select using (auth.uid() = recipient_id);
drop policy if exists notifications_update_own on public.notifications;
create policy notifications_update_own on public.notifications for update using (auth.uid() = recipient_id) with check (auth.uid() = recipient_id);
drop policy if exists notifications_insert_actor on public.notifications;
create policy notifications_insert_actor on public.notifications for insert with check (auth.uid() = actor_id);
