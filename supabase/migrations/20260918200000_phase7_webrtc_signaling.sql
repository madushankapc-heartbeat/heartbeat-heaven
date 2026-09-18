-- Phase 7.1: WebRTC signaling fields for secure 1-to-1 calls
alter table public.call_sessions
  add column if not exists offer_sdp text,
  add column if not exists answer_sdp text,
  add column if not exists caller_ice jsonb not null default '[]'::jsonb,
  add column if not exists callee_ice jsonb not null default '[]'::jsonb;

create or replace function private.call_sessions_security_guard()
returns trigger language plpgsql security definer set search_path = public, private
as $$
begin
  if tg_op = 'INSERT' then
    if new.caller_id <> auth.uid() then raise exception 'CALL_FORBIDDEN'; end if;
    if new.status <> 'ringing' then raise exception 'CALL_INVALID_STATUS'; end if;
    if new.started_at is not null or new.ended_at is not null then raise exception 'CALL_INVALID_TIMES'; end if;
    if new.offer_sdp is not null and length(new.offer_sdp) > 20000 then raise exception 'CALL_SIGNAL_TOO_LARGE'; end if;
    return new;
  end if;

  if tg_op = 'UPDATE' then
    if auth.uid() <> old.caller_id and auth.uid() <> old.callee_id then raise exception 'CALL_FORBIDDEN'; end if;
    if new.caller_id <> old.caller_id or new.callee_id <> old.callee_id or new.call_type <> old.call_type or new.created_at <> old.created_at then
      raise exception 'CALL_IMMUTABLE_FIELDS';
    end if;
    if old.status in ('ended','failed','declined','missed','cancelled') then raise exception 'CALL_FINALIZED'; end if;

    if auth.uid() = old.callee_id and new.status not in ('accepted','declined','missed','ended','failed','cancelled') then
      raise exception 'CALL_INVALID_STATUS';
    end if;
    if auth.uid() = old.caller_id and new.status not in ('cancelled','ended','failed') then
      raise exception 'CALL_INVALID_STATUS';
    end if;

    if auth.uid() = old.caller_id then
      if new.answer_sdp is distinct from old.answer_sdp or new.callee_ice is distinct from old.callee_ice then
        raise exception 'CALL_SIGNAL_FORBIDDEN';
      end if;
    end if;
    if auth.uid() = old.callee_id then
      if new.offer_sdp is distinct from old.offer_sdp or new.caller_ice is distinct from old.caller_ice then
        raise exception 'CALL_SIGNAL_FORBIDDEN';
      end if;
    end if;

    if new.offer_sdp is not null and length(new.offer_sdp) > 20000 then raise exception 'CALL_SIGNAL_TOO_LARGE'; end if;
    if new.answer_sdp is not null and length(new.answer_sdp) > 20000 then raise exception 'CALL_SIGNAL_TOO_LARGE'; end if;
    if jsonb_array_length(new.caller_ice) > 200 then raise exception 'CALL_SIGNAL_TOO_LARGE'; end if;
    if jsonb_array_length(new.callee_ice) > 200 then raise exception 'CALL_SIGNAL_TOO_LARGE'; end if;
    if new.started_at is not null and new.started_at > now() + interval '5 minutes' then raise exception 'CALL_INVALID_TIME'; end if;
    if new.ended_at is not null and new.ended_at > now() + interval '5 minutes' then raise exception 'CALL_INVALID_TIME'; end if;

    new.updated_at := now();
    if new.ended_at is not null and new.started_at is not null then
      new.duration_seconds := greatest(0, least(86400, floor(extract(epoch from (new.ended_at-new.started_at)))::integer));
    else
      new.duration_seconds := old.duration_seconds;
    end if;
    return new;
  end if;
  return new;
end $$;

drop trigger if exists call_sessions_security_guard on public.call_sessions;
create trigger call_sessions_security_guard before insert or update on public.call_sessions
for each row execute function private.call_sessions_security_guard();
