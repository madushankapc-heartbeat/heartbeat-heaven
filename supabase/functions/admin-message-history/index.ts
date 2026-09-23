import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";
const URL_ = Deno.env.get("SUPABASE_URL")!;
const KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const admin = createClient(URL_, KEY, { auth: { autoRefreshToken:false, persistSession:false } });
const cors={"Access-Control-Allow-Origin":"*","Access-Control-Allow-Headers":"authorization, x-client-info, apikey, content-type","Access-Control-Allow-Methods":"POST, OPTIONS"};
const json=(data:unknown,status=200)=>new Response(JSON.stringify(data),{status,headers:{...cors,"Content-Type":"application/json"}});
const validUuid=(v:string)=>/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(v);
Deno.serve(async(req)=>{
 if(req.method==="OPTIONS") return new Response("ok",{headers:cors});
 if(req.method!=="POST") return json({error:"Method not allowed."},405);
 try{
  const token=(req.headers.get("Authorization")??"").replace(/^Bearer\s+/i,"").trim();
  if(!token) return json({error:"Authentication required."},401);
  const {data,error}=await admin.auth.getUser(token); const user=data.user;
  if(error||!user?.id) return json({error:"Authentication required."},401);
  const {data:profile,error:pe}=await admin.from("profiles").select("role").eq("id",user.id).maybeSingle();
  if(pe) return json({error:"Could not verify admin access."},500);
  if(profile?.role!=="admin") return json({error:"Admin access required."},403);
  const b=await req.json().catch(()=>({}));
  const reason=String(b.reason??"").trim(), uid=String(b.user_id??"").trim(), type=String(b.message_type??"").trim();
  const limit=Math.min(Math.max(Number(b.limit??50)||50,1),100), offset=Math.max(Number(b.offset??0)||0,0);
  if(reason.length<5) return json({error:"A reason is required to view admin history."},400);
  if(uid&&!validUuid(uid)) return json({error:"Invalid user id."},400);
  if(type&&!new Set(["text","image","video","audio","file"]).has(type)) return json({error:"Invalid message type."},400);
  const {data:rows,error:he}=await admin.rpc("admin_message_history",{p_since:null,p_until:null,p_user_id:uid||null,p_message_id:null,p_message_type:type||null,p_limit:limit,p_offset:offset});
  if(he) return json({error:"Could not load admin history."},500);
  const {error:ae}=await admin.rpc("log_admin_history_access",{p_accessor_id:user.id,p_reason:reason.slice(0,500),p_user_filter:uid||null,p_message_type_filter:type||null,p_offset:offset,p_limit:limit});
  if(ae) return json({error:"Could not record history access."},500);
  return json({retention_days:21,cutoff:new Date(Date.now()-21*24*60*60*1000).toISOString(),has_more:(rows??[]).length===limit,history:rows??[]});
 }catch(e){console.error("admin-message-history error",e);return json({error:"Admin history request failed."},500);}
});