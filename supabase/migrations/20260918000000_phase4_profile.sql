-- Phase 4: profile bio, last-seen timestamp and privacy preference.
alter table public.profiles
  add column if not exists bio text not null default '',
  add column if not exists last_seen_at timestamptz,
  add column if not exists last_seen_visibility text not null default 'everyone';

alter table public.profiles
  drop constraint if exists profiles_last_seen_visibility_check;

alter table public.profiles
  add constraint profiles_last_seen_visibility_check
  check (last_seen_visibility in ('everyone', 'friends', 'nobody'));

create index if not exists idx_profiles_last_seen_at on public.profiles(last_seen_at);

-- Profile photos are stored in the existing profile-pictures bucket.
-- The client uses the existing own-profile update policy on profiles.
