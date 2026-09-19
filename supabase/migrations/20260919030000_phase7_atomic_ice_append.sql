-- Phase 7.3: atomic WebRTC ICE candidate append
create or replace function public.append_call_ice(p_call_id uuid, p_caller boolean, p_candidate jsonb)
returns public.call_sessions
language plpgsql
security invoker
set search_path = public
as $$
declare r public.call_sessions;
begin
  if auth.uid() is null then raise exception 'AUTH_REQUIRED'; end if;
  if p_candidate is null or jsonb_typeof(p_candidate) <> 'object' then raise exception 'CALL_INVALID_CANDIDATE'; end if;
  update public.call_sessions
     set caller_ice = case when p_caller then caller_ice || jsonb_build_array(p_candidate) else caller_ice end,
         callee_ice = case when not p_caller then callee_ice || jsonb_build_array(p_candidate) else callee_ice end
   where id = p_call_id
     and ((p_caller and caller_id = auth.uid()) or ((not p_caller) and callee_id = auth.uid()))
  returning * into r;
  if r.id is null then raise exception 'CALL_FORBIDDEN'; end if;
  return r;
end $$;
revoke all on function public.append_call_ice(uuid,boolean,jsonb) from public, anon;
grant execute on function public.append_call_ice(uuid,boolean,jsonb) to authenticated;