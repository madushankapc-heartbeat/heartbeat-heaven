create table if not exists public.stories (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  media_type text not null default 'text' check (media_type in ('text','image','video')),
  storage_path text,
  caption text not null default '' check (char_length(caption) <= 1000),
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default (now() + interval '48 hours'),
  constraint stories_media_check check ((media_type='text' and storage_path is null) or (media_type in ('image','video') and storage_path is not null))
);
create index if not exists stories_active_idx on public.stories (expires_at, created_at desc);
create index if not exists stories_user_idx on public.stories (user_id, created_at desc);

create table if not exists public.story_views (
  story_id uuid not null references public.stories(id) on delete cascade,
  viewer_id uuid not null references public.profiles(id) on delete cascade,
  viewed_at timestamptz not null default now(),
  primary key (story_id, viewer_id)
);
create table if not exists public.story_likes (
  story_id uuid not null references public.stories(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (story_id, user_id)
);

alter table public.stories enable row level security;
alter table public.story_views enable row level security;
alter table public.story_likes enable row level security;

drop policy if exists stories_select_authenticated on public.stories;
create policy stories_select_authenticated on public.stories for select to authenticated using (expires_at > now());
drop policy if exists stories_insert_own on public.stories;
create policy stories_insert_own on public.stories for insert to authenticated with check (user_id = auth.uid() and expires_at <= now() + interval '48 hours' and expires_at > now());
drop policy if exists stories_delete_own on public.stories;
create policy stories_delete_own on public.stories for delete to authenticated using (user_id = auth.uid());

drop policy if exists story_views_select_own on public.story_views;
create policy story_views_select_own on public.story_views for select to authenticated using (viewer_id = auth.uid() or exists (select 1 from public.stories s where s.id = story_id and s.user_id = auth.uid()));
drop policy if exists story_views_insert_own on public.story_views;
create policy story_views_insert_own on public.story_views for insert to authenticated with check (viewer_id = auth.uid());

drop policy if exists story_likes_select_authenticated on public.story_likes;
create policy story_likes_select_authenticated on public.story_likes for select to authenticated using (exists (select 1 from public.stories s where s.id = story_id and s.expires_at > now()));
drop policy if exists story_likes_insert_own on public.story_likes;
create policy story_likes_insert_own on public.story_likes for insert to authenticated with check (user_id = auth.uid() and exists (select 1 from public.stories s where s.id = story_id and s.expires_at > now()));
drop policy if exists story_likes_delete_own on public.story_likes;
create policy story_likes_delete_own on public.story_likes for delete to authenticated using (user_id = auth.uid());

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('stories','stories',true,20971520,array['image/jpeg','image/png','image/webp','image/gif','video/mp4','video/webm','video/quicktime'])
on conflict (id) do update set public=true, file_size_limit=20971520, allowed_mime_types=excluded.allowed_mime_types;

drop policy if exists stories_storage_insert on storage.objects;
create policy stories_storage_insert on storage.objects for insert to authenticated with check (bucket_id='stories' and (storage.foldername(name))[1] = auth.uid()::text);
drop policy if exists stories_storage_update on storage.objects;
create policy stories_storage_update on storage.objects for update to authenticated using (bucket_id='stories' and (storage.foldername(name))[1] = auth.uid()::text) with check (bucket_id='stories' and (storage.foldername(name))[1] = auth.uid()::text);
drop policy if exists stories_storage_delete on storage.objects;
create policy stories_storage_delete on storage.objects for delete to authenticated using (bucket_id='stories' and (storage.foldername(name))[1] = auth.uid()::text);

create or replace function public.cleanup_expired_stories()
returns void language plpgsql security definer set search_path to ''
as $$ begin delete from public.stories where expires_at <= now(); end; $$;
revoke all on function public.cleanup_expired_stories() from public, anon, authenticated;
create extension if not exists pg_cron with schema pg_catalog;
select cron.schedule('heartbeat-heaven-cleanup-stories','*/15 * * * *','select public.cleanup_expired_stories()');