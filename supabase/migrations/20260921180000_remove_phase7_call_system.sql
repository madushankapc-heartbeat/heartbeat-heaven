-- Remove the abandoned Phase 7 calling system completely.
-- Historical Phase 7 migrations remain in git history for auditability.

do $$
begin
  if exists (
    select 1
    from pg_publication_tables
    where pubname = 'supabase_realtime'
      and schemaname = 'public'
      and tablename = 'call_sessions'
  ) then
    alter publication supabase_realtime drop table public.call_sessions;
  end if;
end
$$;

drop table if exists public.call_blocks cascade;
drop table if exists public.call_sessions cascade;

drop function if exists public.append_call_ice(uuid, boolean, jsonb);
drop function if exists public.publish_call_sdp(uuid, text, text);
drop function if exists public.update_call_status(uuid, text);
drop function if exists public.create_call(uuid, text);
drop function if exists private.call_sessions_security_guard();
