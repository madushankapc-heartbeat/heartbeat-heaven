create table if not exists public.chat_pins (
  user_id uuid not null references auth.users(id) on delete cascade,
  other_user_id uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, other_user_id),
  check (user_id <> other_user_id)
);

alter table public.chat_pins enable row level security;

drop policy if exists chat_pins_own on public.chat_pins;
create policy chat_pins_own
on public.chat_pins
for all
using (auth.uid() = user_id)
with check (auth.uid() = user_id);

create index if not exists chat_pins_user_created_idx
on public.chat_pins (user_id, created_at desc);
