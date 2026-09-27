drop function if exists public.admin_users();

create function public.admin_users()
returns table(
  id uuid,
  username text,
  gender text,
  age integer,
  phone text,
  email text,
  role text,
  created_at timestamptz,
  last_sign_in_at timestamptz,
  last_seen_at timestamptz,
  avatar_url text
)
language plpgsql
security definer
set search_path = public, auth
as $$
begin
  return query
  select
    p.id,
    p.username,
    p.gender,
    p.age,
    p.phone,
    u.email,
    p.role,
    p.created_at,
    u.last_sign_in_at,
    p.last_seen_at,
    p.avatar_url
  from public.profiles p
  join auth.users u on u.id = p.id
  where public.is_admin()
  order by p.created_at desc;
end;
$$;

alter function public.admin_users() owner to postgres;
revoke all on function public.admin_users() from public;
revoke all on function public.admin_users() from anon;
grant execute on function public.admin_users() to authenticated;
grant execute on function public.admin_users() to service_role;
