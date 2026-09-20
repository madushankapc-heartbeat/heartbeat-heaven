-- Harden call SDP RPC exposure.
revoke all on function public.publish_call_sdp(uuid,text,text) from public, anon;
grant execute on function public.publish_call_sdp(uuid,text,text) to authenticated;