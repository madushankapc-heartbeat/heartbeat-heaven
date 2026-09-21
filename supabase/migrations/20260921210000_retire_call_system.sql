-- Retire the legacy voice/video call system.
-- Keep older Phase 7 migration files for migration-history integrity.
-- This migration is idempotent and removes any call objects if a fresh/older database still has them.

drop function if exists public.create_call(uuid, text);
drop function if exists public.update_call_status(uuid, text);
drop function if exists public.publish_call_sdp(uuid, text, text);
drop function if exists public.append_call_ice(uuid, text, jsonb);
drop function if exists public.resolve_call(uuid);

drop function if exists private.call_sessions_security_guard();

do $$
declare
  pub record;
begin
  for pub in
    select distinct p.pubname
    from pg_publication p
    join pg_publication_tables pt on pt.pubname = p.pubname
    where pt.schemaname = 'public'
      and pt.tablename in ('call_sessions', 'call_blocks')
  loop
    execute format(
      'alter publication %I drop table if exists public.call_sessions',
      pub.pubname
    );
    execute format(
      'alter publication %I drop table if exists public.call_blocks',
      pub.pubname
    );
  end loop;
end $$;

drop table if exists public.call_sessions cascade;
drop table if exists public.call_blocks cascade;
