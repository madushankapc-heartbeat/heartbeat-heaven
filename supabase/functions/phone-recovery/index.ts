import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const cors = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type", "Access-Control-Allow-Methods": "POST, OPTIONS" };
const admin = createClient(SUPABASE_URL, SERVICE_ROLE_KEY, { auth: { autoRefreshToken: false, persistSession: false } });
const LEGACY_HASH = "legacy-unconfigured-recovery-disabled-placeholder-value";
function json(data: unknown, status = 200) { return new Response(JSON.stringify(data), { status, headers: { ...cors, "Content-Type": "application/json" } }); }
function normalizePhone(value: string) { const raw = value.trim().replace(/[\s\-()]/g, ""); if (raw.startsWith("+")) return "+" + raw.slice(1).replace(/\D/g, ""); const digits = raw.replace(/\D/g, ""); if (digits.startsWith("0") && digits.length >= 9) return "+94" + digits.slice(1); if (digits.startsWith("94") && digits.length >= 10) return "+" + digits; return digits.length >= 8 ? "+" + digits : ""; }
function normalizeAnswer(value: string) { return value.normalize("NFKC").trim().replace(/\s+/g, " ").toLowerCase(); }
function fromB64(value: string) { const s = value.replaceAll("-", "+").replaceAll("_", "/") + "=".repeat((4 - value.length % 4) % 4); const raw = atob(s); return Uint8Array.from(raw, c => c.charCodeAt(0)); }
async function deviceHash(deviceId: string) { const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(SERVICE_ROLE_KEY), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]); const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(deviceId.trim())); return Array.from(new Uint8Array(sig)).map(b => b.toString(16).padStart(2, "0")).join(""); }
async function verifyAnswer(answer: string, encoded: string) {
  const parts = encoded.split("$"); if (parts.length !== 4 || parts[0] !== "pbkdf2") return false;
  const iterations = Number(parts[1]); if (!Number.isInteger(iterations) || iterations < 100000 || iterations > 500000) return false;
  const salt = fromB64(parts[2]); const expected = fromB64(parts[3]);
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(normalizeAnswer(answer)), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", salt, iterations, hash: "SHA-256" }, key, expected.length * 8);
  const actual = new Uint8Array(bits); if (actual.length !== expected.length) return false;
  let diff = 0; for (let i = 0; i < actual.length; i++) diff |= actual[i] ^ expected[i]; return diff === 0;
}
async function findProfile(identifier: string) {
  const value = identifier.trim(); if (!value || value.includes("@")) return null;
  const phone = normalizePhone(value);
  if (phone) { const { data } = await admin.from("profiles").select("id,phone,username").eq("phone", phone).maybeSingle(); if (data) return data; }
  const { data } = await admin.from("profiles").select("id,phone,username").ilike("username", value).maybeSingle(); return data ?? null;
}
async function boundSecurity(userId: string, dHash: string) {
  const { data } = await admin.from("phone_account_security").select("user_id,device_hash,recovery_question,recovery_answer_hash,failed_attempts,locked_until").eq("user_id", userId).maybeSingle();
  if (!data || data.device_hash !== dHash) return null; return data;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);
  try {
    const body = await req.json(); const action = String(body.action ?? "question"); const identifier = String(body.identifier ?? "").trim(); const deviceId = String(body.device_id ?? "").trim();
    if (!identifier || !deviceId) return json({ error: "Enter your username or phone number." }, 400);
    const profile = await findProfile(identifier); if (!profile?.id) return json({ error: "Phone account recovery is not available for this account on this device." }, 404);
    const dHash = await deviceHash(deviceId); const security = await boundSecurity(profile.id, dHash);
    if (!security) return json({ error: "This phone account is not linked to this device." }, 403);

    if (action === "question") {
      if (security.recovery_answer_hash === LEGACY_HASH) return json({ error: "Recovery question is not configured for this legacy phone account. Use Delete Account Permanently or log in and set up recovery." }, 409);
      return json({ recovery_question: security.recovery_question });
    }

    if (action === "delete") {
      if (String(body.confirmation ?? "") !== "DELETE") return json({ error: "Type DELETE to permanently remove this account." }, 400);
      const { error } = await admin.auth.admin.deleteUser(profile.id); if (error) return json({ error: "Could not permanently delete the account." }, 500);
      return json({ deleted: true, message: "Your phone account has been permanently deleted." });
    }

    if (action !== "reset") return json({ error: "Invalid recovery action." }, 400);
    const now = new Date(); const lockedUntil = security.locked_until ? new Date(security.locked_until) : null;
    if (lockedUntil && lockedUntil.getTime() > now.getTime()) return json({ error: "Too many incorrect recovery attempts. Please try again later." }, 429);
    if (security.recovery_answer_hash === LEGACY_HASH) return json({ error: "Recovery question is not configured for this legacy phone account. Use Delete Account Permanently or log in and set up recovery." }, 409);
    const answer = String(body.recovery_answer ?? ""); const newPassword = String(body.new_password ?? "");
    if (normalizeAnswer(answer).length < 2) return json({ error: "Enter your recovery answer." }, 400);
    if (newPassword.length < 8) return json({ error: "New password must be at least 8 characters." }, 400);
    const correct = await verifyAnswer(answer, security.recovery_answer_hash);
    if (!correct) {
      const nextAttempts = Math.min(5, Number(security.failed_attempts ?? 0) + 1); const lock = nextAttempts >= 5 ? new Date(now.getTime() + 15 * 60 * 1000).toISOString() : null;
      await admin.from("phone_account_security").update({ failed_attempts: nextAttempts, locked_until: lock, updated_at: now.toISOString() }).eq("user_id", profile.id);
      return json({ error: nextAttempts >= 5 ? "Too many incorrect recovery attempts. Please try again in 15 minutes." : "Incorrect recovery answer." }, 401);
    }
    const { error: updateError } = await admin.auth.admin.updateUserById(profile.id, { password: newPassword });
    if (updateError) return json({ error: "Could not update the password. Please try again." }, 500);
    await admin.from("phone_account_security").update({ failed_attempts: 0, locked_until: null, updated_at: now.toISOString() }).eq("user_id", profile.id);
    await admin.auth.admin.signOut(profile.id, "global").catch(() => undefined);
    return json({ reset: true, message: "Password changed successfully. Please log in with your new password." });
  } catch (e) { console.error("phone-recovery error", e); return json({ error: e instanceof Error ? e.message : "Phone recovery failed." }, 500); }
});
