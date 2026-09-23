-- Private chat media migration.
-- Durable storage path lets the app use signed URLs after the bucket becomes private.

alter table public.messages add column if not exists media_path text;

update public.messages
set media_path = regexp_replace(media_url, '^https?://[^/]+/storage/v1/object/public/chat-media/', '')
where coalesce(media_path,'') = ''
  and media_url like '%/storage/v1/object/public/chat-media/%';

alter table private.admin_message_history add column if not exists media_path text;

update private.admin_message_history
set media_path = regexp_replace(media_url, '^https?://[^/]+/storage/v1/object/public/chat-media/', '')
where coalesce(media_path,'') = ''
  and media_url like '%/storage/v1/object/public/chat-media/%';

create or replace function private.archive_message_history()
returns trigger
language plpgsql
security definer
set search_path=''
as $$
begin
  if tg_op='DELETE' then
    insert into private.admin_message_history(
      message_id,sender_id,receiver_id,body,message_type,media_url,media_path,media_name,media_size,
      created_at,delivered_at,read_at,edited_at,deleted_at,history_updated_at
    )
    values(
      old.id,old.sender_id,old.receiver_id,old.body,old.message_type,old.media_url,old.media_path,old.media_name,old.media_size,
      old.created_at,old.delivered_at,old.read_at,old.edited_at,coalesce(old.deleted_at,now()),now()
    )
    on conflict(message_id) do update set
      body=excluded.body, media_url=excluded.media_url, media_path=excluded.media_path,
      media_name=excluded.media_name, media_size=excluded.media_size,
      edited_at=excluded.edited_at,
      deleted_at=coalesce(excluded.deleted_at,private.admin_message_history.deleted_at,now()),
      history_updated_at=now();
    return old;
  end if;

  insert into private.admin_message_history(
    message_id,sender_id,receiver_id,body,message_type,media_url,media_path,media_name,media_size,
    created_at,delivered_at,read_at,edited_at,deleted_at,history_updated_at
  )
  values(
    new.id,new.sender_id,new.receiver_id,new.body,new.message_type,new.media_url,new.media_path,new.media_name,new.media_size,
    new.created_at,new.delivered_at,new.read_at,new.edited_at,new.deleted_at,now()
  )
  on conflict(message_id) do update set
    body=excluded.body, message_type=excluded.message_type, media_url=excluded.media_url, media_path=excluded.media_path,
    media_name=excluded.media_name, media_size=excluded.media_size,
    delivered_at=excluded.delivered_at, read_at=excluded.read_at, edited_at=excluded.edited_at,
    deleted_at=excluded.deleted_at, history_updated_at=now();
  return new;
end;
$$;

drop function public.admin_message_history(timestamptz,timestamptz,uuid,uuid,text,integer,integer);

create function public.admin_message_history(
  p_since timestamptz default null,
  p_until timestamptz default null,
  p_user_id uuid default null,
  p_message_id uuid default null,
  p_message_type text default null,
  p_limit integer default 100,
  p_offset integer default 0
)
returns table(
  message_id uuid,sender_id uuid,sender_username text,receiver_id uuid,receiver_username text,
  body text,message_type text,media_url text,media_path text,media_name text,media_size bigint,
  created_at timestamptz,delivered_at timestamptz,read_at timestamptz,edited_at timestamptz,
  deleted_at timestamptz,history_updated_at timestamptz
)
language sql
security invoker
set search_path=''
stable
as $$
select
  h.message_id,h.sender_id,sp.username,h.receiver_id,rp.username,h.body,h.message_type,
  h.media_url,h.media_path,h.media_name,h.media_size,h.created_at,h.delivered_at,h.read_at,
  h.edited_at,h.deleted_at,h.history_updated_at
from private.admin_message_history h
left join public.profiles sp on sp.id=h.sender_id
left join public.profiles rp on rp.id=h.receiver_id
where h.created_at >= greatest(coalesce(p_since,pg_catalog.now()-interval '21 days'),pg_catalog.now()-interval '21 days')
  and (p_until is null or h.created_at < p_until)
  and (p_user_id is null or h.sender_id=p_user_id or h.receiver_id=p_user_id)
  and (p_message_id is null or h.message_id=p_message_id)
  and (p_message_type is null or h.message_type=p_message_type)
order by h.created_at desc
limit least(greatest(coalesce(p_limit,100),1),100)
offset greatest(coalesce(p_offset,0),0);
$$;

revoke execute on function public.admin_message_history(timestamptz,timestamptz,uuid,uuid,text,integer,integer) from public,anon,authenticated;
grant execute on function public.admin_message_history(timestamptz,timestamptz,uuid,uuid,text,integer,integer) to service_role;

update storage.buckets set public=false where id='chat-media';
