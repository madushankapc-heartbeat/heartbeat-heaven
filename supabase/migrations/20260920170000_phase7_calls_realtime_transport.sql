-- Phase 7: realtime transport for the new call system.
-- This migration only enables delivery; call authorization remains in call_sessions RLS.

alter table public.call_sessions replica identity full;

do $$
begin
    if not exists (
        select 1
        from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'call_sessions'
    ) then
        alter publication supabase_realtime add table public.call_sessions;
    end if;
end
$$;
