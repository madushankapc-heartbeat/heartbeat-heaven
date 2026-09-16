create table if not exists public.phone_account_security (
  user_id uuid primary key references auth.users(id) on delete cascade,
  device_hash text not null,
  recovery_question text not null,
  recovery_answer_hash text not null,
  failed_attempts integer not null default 0,
  locked_until timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint phone_account_security_device_hash_check check (device_hash ~ '^[0-9a-f]{64}$'),
  constraint phone_account_security_question_check check (char_length(btrim(recovery_question)) between 5 and 160),
  constraint phone_account_security_answer_hash_check check (char_length(recovery_answer_hash) between 40 and 512),
  constraint phone_account_security_failed_attempts_check check (failed_attempts between 0 and 5)
);

create unique index if not exists phone_account_security_device_hash_key
  on public.phone_account_security(device_hash);

create index if not exists phone_account_security_locked_until_idx
  on public.phone_account_security(locked_until);

alter table public.phone_account_security enable row level security;
revoke all on table public.phone_account_security from anon, authenticated;
