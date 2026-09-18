-- Allow media messages to have an empty caption while keeping text messages non-empty.
alter table public.messages drop constraint if exists messages_body_check;

alter table public.messages add constraint messages_body_check
check (
  message_type <> 'text'
  or (char_length(trim(body)) >= 1 and char_length(trim(body)) <= 2000)
);
