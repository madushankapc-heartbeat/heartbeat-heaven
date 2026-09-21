drop policy if exists "friendships_update_participant" on public.friendships;

create policy "friendships_update_addressee_pending"
on public.friendships
for update
to authenticated
using (
  (select auth.uid()) = addressee_id
  and status = 'pending'
)
with check (
  (select auth.uid()) = addressee_id
  and status in ('accepted', 'rejected')
);

create policy "friendships_update_requester_rejected"
on public.friendships
for update
to authenticated
using (
  (select auth.uid()) = requester_id
  and status = 'rejected'
)
with check (
  (select auth.uid()) = requester_id
  and status = 'pending'
);
