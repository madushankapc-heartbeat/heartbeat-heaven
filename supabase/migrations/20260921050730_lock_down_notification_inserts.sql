-- Notifications are created by trusted server-side triggers.
-- Clients may read/update their own notifications, but must not forge notifications.
drop policy if exists notifications_insert_actor on public.notifications;
