import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const PUBLISHABLE_KEY = Deno.env.get("SUPABASE_PUBLISHABLE_KEY") ?? Deno.env.get("SUPABASE_ANON_KEY")!;
const cors = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type", "Access-Control-Allow-Methods": "POST, OPTIONS" };
const admin = createClient(SUPABASE_URL, SERVICE_ROLE_KEY, { auth: { autoRefreshToken: false, persistSession: false } });
function json(data: unknown, status = 200) { return new Response(JSON.stringify(data), { status, headers: { ...cors, "Content-Type": "application/json" } }); }
function normalizePhone(value: string) { const raw = value.trim().replace(/[\s\-()]/g, ""); if (raw.startsWith("+")) return "+" + raw.slice(1).replace(/\D/g, ""); const digits = raw.replace(/\D/g, ""); if (digits.startsWith("0") && digits.length >= 9) return "+94" + digits.slice(1); if (digits.startsWith("94") && digits.length >= 10) return "+" + digits; return digits.length >= 8 ? "+" + digits : ""; }
async function deviceHash(deviceId: string) { const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(SERVICE_ROLE_KEY), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]); const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(deviceId.trim())); return Array.from(new Uint8Array(sig)).map(b => b.toString(16).padStart(2, "0")).join(""); }

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);
  try {
    const body = await req.json(); const identifier = String(body.identifier ?? body.phone ?? "").trim(); const password = String(body.password ?? ""); const deviceId = String(body.device_id ?? "").trim();
    if (!identifier || !password) return json({ error: "Enter your phone/username and password." }, 400);
    if (identifier.includes("@")) return json({ error: "Invalid phone or username." }, 400);
    if (!deviceId) return json({ error: "Could not identify this device. Please restart the app and try again." }, 400);
    const dHash = await deviceHash(deviceId);
    const { data: ownedDevice, error: ownedDeviceError } = await admin.from("phone_account_security").select("user_id").eq("device_hash", dHash).maybeSingle();
    if (ownedDeviceError) return json({ error: "Could not check device account status." }, 500);

    let profileQuery = admin.from("profiles").select("id,phone,username").limit(1);
    if (/[A-Za-z]/.test(identifier)) profileQuery = profileQuery.ilike("username", identifier);
    else { const phone = normalizePhone(identifier); if (!phone) return json({ error: "Enter a valid mobile number." }, 400); const digits = phone.replace(/\D/g, ""); profileQuery = profileQuery.or(`phone.eq.${phone},phone.eq.${digits}`); }
    const { data: profile, error: profileError } = await profileQuery.maybeSingle();
    if (profileError || !profile?.id) return json({ error: "Invalid username/phone or password." }, 401);
    const targetUserId = profile.id;
    const { data: binding } = await admin.from("phone_account_security").select("user_id,device_hash").eq("user_id", targetUserId).maybeSingle();
    if (binding?.device_hash && binding.device_hash !== dHash) return json({ error: "This phone account is linked to another device." }, 403);
    if (ownedDevice?.user_id && ownedDevice.user_id !== targetUserId) return json({ error: "This device already has a phone account. Please log in to your existing account." }, 409);

    const { data: userData, error: userError } = await admin.auth.admin.getUserById(targetUserId);
    if (userError || !userData.user) return json({ error: "Invalid username/phone or password." }, 401);
    const user = userData.user; let loginEmail = user.email;
    if (!loginEmail) {
      const phoneDigits = String(profile.phone ?? "").replace(/\D/g, ""); if (!phoneDigits) return json({ error: "This account has no valid phone number." }, 400);
      loginEmail = `phone_${phoneDigits}@phone.heartbeat-heaven.invalid`;
      const { data: updated, error: updateError } = await admin.auth.admin.updateUserById(user.id, { email: loginEmail, email_confirm: true });
      if (updateError || !updated.user?.email) return json({ error: "Could not prepare phone login. Please try again." }, 500); loginEmail = updated.user.email;
    }
    const signInResponse = await fetch(`${SUPABASE_URL}/auth/v1/token?grant_type=password`, { method: "POST", headers: { apikey: PUBLISHABLE_KEY, "Content-Type": "application/json" }, body: JSON.stringify({ email: loginEmail, password }) });
    const signInText = await signInResponse.text();
    if (!signInResponse.ok) return json({ error: "Invalid username/phone or password." }, 401);
    if (!binding) {
      const { error: bindError } = await admin.from("phone_account_security").insert({ user_id: targetUserId, device_hash: dHash, recovery_question: "Legacy phone account recovery is not configured.", recovery_answer_hash: "legacy-unconfigured-recovery-disabled-placeholder-value" });
      if (bindError && bindError.code !== "23505") return json({ error: "Could not secure this phone account to the device." }, 500);
    }
    const session = JSON.parse(signInText); return json({ access_token: session.access_token, refresh_token: session.refresh_token, user: session.user });
  } catch (e) { console.error("phone-login error", e); return json({ error: e instanceof Error ? e.message : "Phone login failed." }, 500); }
});
