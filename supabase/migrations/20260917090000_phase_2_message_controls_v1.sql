create table if not exists public.message_deletions (
  id uuid primary key default gen_random_uuid(),
  message_id uuid not null references public.messages(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  deleted_at timestamptz not null default now(),
  unique (message_id, user_id)
);

alter table public.message_deletions enable row level security;

drop policy if exists message_deletions_select_own on public.message_deletions;
create policy message_deletions_select_own on public.message_deletions
  for select to authenticated using (auth.uid() = user_id);

drop policy if exists message_deletions_insert_own on public.message_deletions;
create policy message_deletions_insert_own on public.message_deletions
  for insert to authenticated with check (auth.uid() = user_id);

drop policy if exists message_deletions_delete_own on public.message_deletions;
create policy message_deletions_delete_own on public.message_deletions
  for delete to authenticated using (auth.uid() = user_id);

create index if not exists message_deletions_user_message_idx on public.message_deletions (user_id, message_id);
create index if not exists message_reactions_message_idx on public.message_reactions (message_id, created_at desc);

alter table public.message_reactions drop constraint if exists message_reactions_reaction_check;
alter table public.message_reactions add constraint message_reactions_reaction_check check (reaction in ('👍','❤️','😂','😮','😢','😡'));

create unique index if not exists message_reactions_user_message_reaction_idx on public.message_reactions (message_id, user_id, reaction);
