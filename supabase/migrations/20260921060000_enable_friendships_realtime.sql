-- Enable Supabase Realtime for friend-request state changes.
alter publication supabase_realtime add table public.friendships;
