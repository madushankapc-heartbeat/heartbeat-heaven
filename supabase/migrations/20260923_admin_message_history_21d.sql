-- 21-day administrator message history retention.
create schema if not exists private;

create table if not exists private.admin_message_history (
  message_id uuid primary key, sender_id uuid not null, receiver_id uuid not null,
  body text not null, message_type text not null, media_url text, media_path text, media_name text,
  media_size bigint, created_at timestamptz not null, delivered_at timestamptz,
  read_at timestamptz, edited_at timestamptz, deleted_at timestamptz,
  history_updated_at timestamptz not null default now()
);
alter table private.admin_message_history enable row level security;
revoke all on table private.admin_message_history from public, anon, authenticated;
create index if not exists admin_message_history_created_idx on private.admin_message_history(created_at desc);
create index if not exists admin_message_history_sender_idx on private.admin_message_history(sender_id,created_at desc);
create index if not exists admin_message_history_receiver_idx on private.admin_message_history(receiver_id,created_at desc);

create or replace function private.archive_message_history()
returns trigger language plpgsql security definer set search_path=''
as $$
begin
  if tg_op='DELETE' then
    insert into private.admin_message_history(message_id,sender_id,receiver_id,body,message_type,media_url,media_path,media_name,media_size,created_at,delivered_at,read_at,edited_at,deleted_at,history_updated_at)
    values(old.id,old.sender_id,old.receiver_id,old.body,old.message_type,old.media_url,old.media_path,old.media_name,old.media_size,old.created_at,old.delivered_at,old.read_at,old.edited_at,coalesce(old.deleted_at,now()),now())
    on conflict(message_id) do update set body=excluded.body,media_url=excluded.media_url,media_path=excluded.media_path,media_name=excluded.media_name,media_size=excluded.media_size,edited_at=excluded.edited_at,deleted_at=coalesce(excluded.deleted_at,private.admin_message_history.deleted_at,now()),history_updated_at=now();
    return old;
  end if;
  insert into private.admin_message_history(message_id,sender_id,receiver_id,body,message_type,media_url,media_path,media_name,media_size,created_at,delivered_at,read_at,edited_at,deleted_at,history_updated_at)
  values(new.id,new.sender_id,new.receiver_id,new.body,new.message_type,new.media_url,new.media_path,new.media_name,new.media_size,new.created_at,new.delivered_at,new.read_at,new.edited_at,new.deleted_at,now())
  on conflict(message_id) do update set body=excluded.body,message_type=excluded.message_type,media_url=excluded.media_url,media_name=excluded.media_name,media_size=excluded.media_size,delivered_at=excluded.delivered_at,read_at=excluded.read_at,edited_at=excluded.edited_at,deleted_at=excluded.deleted_at,history_updated_at=now();
  return new;
end;
$$;
revoke execute on function private.archive_message_history() from public,anon,authenticated;
drop trigger if exists messages_admin_history_archive on public.messages;
create trigger messages_admin_history_archive after insert or update or delete on public.messages for each row execute function private.archive_message_history();

insert into private.admin_message_history(message_id,sender_id,receiver_id,body,message_type,media_url,media_path,media_name,media_size,created_at,delivered_at,read_at,edited_at,deleted_at,history_updated_at)
select id,sender_id,receiver_id,body,message_type,media_url,media_path,media_name,media_size,created_at,delivered_at,read_at,edited_at,deleted_at,now()
from public.messages where created_at>=now()-interval '21 days'
on conflict(message_id) do nothing;

create or replace function public.admin_message_history(p_since timestamptz default null,p_until timestamptz default null,p_user_id uuid default null,p_message_id uuid default null,p_message_type text default null,p_limit integer default 100,p_offset integer default 0)
returns table(message_id uuid,sender_id uuid,sender_username text,receiver_id uuid,receiver_username text,body text,message_type text,media_url text,media_path text,media_name text,media_size bigint,created_at timestamptz,delivered_at timestamptz,read_at timestamptz,edited_at timestamptz,deleted_at timestamptz,history_updated_at timestamptz)
language sql security invoker set search_path='' stable
as $$
select h.message_id,h.sender_id,sp.username,h.receiver_id,rp.username,h.body,h.message_type,h.media_url,h.media_path,h.media_name,h.media_size,h.created_at,h.delivered_at,h.read_at,h.edited_at,h.deleted_at,h.history_updated_at
from private.admin_message_history h left join public.profiles sp on sp.id=h.sender_id left join public.profiles rp on rp.id=h.receiver_id
where h.created_at>=greatest(coalesce(p_since,pg_catalog.now()-interval '21 days'),pg_catalog.now()-interval '21 days')
and(p_until is null or h.created_at<p_until)
and(p_user_id is null or h.sender_id=p_user_id or h.receiver_id=p_user_id)
and(p_message_id is null or h.message_id=p_message_id)
and(p_message_type is null or h.message_type=p_message_type)
order by h.created_at desc limit least(greatest(coalesce(p_limit,100),1),100) offset greatest(coalesce(p_offset,0),0);
$$;
revoke execute on function public.admin_message_history(timestamptz,timestamptz,uuid,uuid,text,integer,integer) from public,anon,authenticated;
grant execute on function public.admin_message_history(timestamptz,timestamptz,uuid,uuid,text,integer,integer) to service_role;
grant usage on schema private to service_role;
grant select on table private.admin_message_history to service_role;

create table if not exists private.admin_history_access_audit(id uuid primary key default gen_random_uuid(),accessor_id uuid not null,reason text not null,user_filter uuid,message_type_filter text,offset_value integer not null default 0,limit_value integer not null default 100,accessed_at timestamptz not null default now());
alter table private.admin_history_access_audit enable row level security;
revoke all on table private.admin_history_access_audit from public,anon,authenticated;
create index if not exists admin_history_access_audit_accessor_idx on private.admin_history_access_audit(accessor_id,accessed_at desc);

create or replace function public.log_admin_history_access(p_accessor_id uuid,p_reason text,p_user_filter uuid default null,p_message_type_filter text default null,p_offset integer default 0,p_limit integer default 100)
returns void language plpgsql security invoker set search_path=''
as $$ begin
if p_accessor_id is null or pg_catalog.length(pg_catalog.btrim(coalesce(p_reason,'')))<5 then raise exception 'admin history access reason is required'; end if;
insert into private.admin_history_access_audit(accessor_id,reason,user_filter,message_type_filter,offset_value,limit_value)
values(p_accessor_id,left(pg_catalog.btrim(p_reason),500),p_user_filter,p_message_type_filter,greatest(coalesce(p_offset,0),0),least(greatest(coalesce(p_limit,100),1),100));
end; $$;
revoke execute on function public.log_admin_history_access(uuid,text,uuid,text,integer,integer) from public,anon,authenticated;
grant execute on function public.log_admin_history_access(uuid,text,uuid,text,integer,integer) to service_role;
grant select,insert on table private.admin_history_access_audit to service_role;

create or replace function private.cleanup_admin_message_history()
returns void language sql security definer set search_path=''
as $$ delete from private.admin_message_history where created_at<now()-interval '21 days'; $$;
revoke execute on function private.cleanup_admin_message_history() from public,anon,authenticated;
do $$ declare jid bigint; begin select jobid into jid from cron.job where jobname='admin-message-history-21d-cleanup' limit 1; if jid is not null then perform cron.unschedule(jid); end if; end $$;
select cron.schedule('admin-message-history-21d-cleanup','15 0 * * *','select private.cleanup_admin_message_history();');