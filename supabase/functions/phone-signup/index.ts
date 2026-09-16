import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY") ?? Deno.env.get("SUPABASE_PUBLISHABLE_KEY")!;
const cors = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type", "Access-Control-Allow-Methods": "POST, OPTIONS" };
const admin = createClient(SUPABASE_URL, SERVICE_ROLE_KEY, { auth: { autoRefreshToken: false, persistSession: false } });
function json(data: unknown, status = 200) { return new Response(JSON.stringify(data), { status, headers: { ...cors, "Content-Type": "application/json" } }); }
function normalizePhone(value: string) { const raw = value.trim().replace(/[\s\-()]/g, ""); if (raw.startsWith("+")) return "+" + raw.slice(1).replace(/\D/g, ""); const digits = raw.replace(/\D/g, ""); if (digits.startsWith("0") && digits.length >= 9) return "+94" + digits.slice(1); if (digits.startsWith("94") && digits.length >= 10) return "+" + digits; return digits.length >= 8 ? "+" + digits : ""; }
function validUsername(value: string) { return /^[A-Za-z0-9_.-]{3,30}$/.test(value.trim()); }
function normalizeAnswer(value: string) { return value.normalize("NFKC").trim().replace(/\s+/g, " ").toLowerCase(); }
function toB64(bytes: Uint8Array) { let s = ""; for (const b of bytes) s += String.fromCharCode(b); return btoa(s).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", ""); }
async function hashAnswer(answer: string) { const iterations = 150000; const salt = crypto.getRandomValues(new Uint8Array(16)); const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(normalizeAnswer(answer)), "PBKDF2", false, ["deriveBits"]); const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", salt, iterations, hash: "SHA-256" }, key, 256); return `pbkdf2$${iterations}$${toB64(salt)}$${toB64(new Uint8Array(bits))}`; }
async function deviceHash(deviceId: string) { const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(SERVICE_ROLE_KEY), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]); const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(deviceId.trim())); return Array.from(new Uint8Array(sig)).map(b => b.toString(16).padStart(2, "0")).join(""); }

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);
  let createdUserId: string | null = null;
  try {
    const body = await req.json();
    const username = String(body.username ?? "").trim(); const phone = normalizePhone(String(body.phone ?? "")); const password = String(body.password ?? ""); const gender = String(body.gender ?? "").trim().toLowerCase(); const age = Number(body.age); const deviceId = String(body.device_id ?? "").trim(); const question = String(body.recovery_question ?? "").normalize("NFKC").trim(); const answer = String(body.recovery_answer ?? "");
    if (!validUsername(username)) return json({ error: "Username must be 3-30 characters and use letters, numbers, _, ., or -." }, 400);
    if (!phone) return json({ error: "Enter a valid mobile number." }, 400);
    if (password.length < 8) return json({ error: "Password must be at least 8 characters." }, 400);
    if (!Number.isInteger(age) || age < 13 || age > 120) return json({ error: "Enter a valid age." }, 400);
    if (!["male", "female"].includes(gender)) return json({ error: "Select male or female." }, 400);
    if (!deviceId) return json({ error: "Could not identify this device. Please restart the app and try again." }, 400);
    if (question.length < 5 || question.length > 160) return json({ error: "Recovery question must be 5-160 characters." }, 400);
    if (normalizeAnswer(answer).length < 2 || normalizeAnswer(answer).length > 160) return json({ error: "Recovery answer must be 2-160 characters." }, 400);
    const dHash = await deviceHash(deviceId);
    const { data: deviceRow, error: deviceError } = await admin.from("phone_account_security").select("user_id").eq("device_hash", dHash).maybeSingle();
    if (deviceError) return json({ error: "Could not check device account status." }, 500);
    if (deviceRow?.user_id) return json({ error: "This device already has a phone account. Please log in to your existing account." }, 409);
    const { data: usernameRows } = await admin.from("profiles").select("id").ilike("username", username).limit(1); if ((usernameRows ?? []).length) return json({ error: "Username is already taken." }, 409);
    const { data: phoneRows } = await admin.from("profiles").select("id").eq("phone", phone).limit(1); if ((phoneRows ?? []).length) return json({ error: "This mobile number is already linked to an account." }, 409);
    const answerHash = await hashAnswer(answer);
    const { data: created, error: createError } = await admin.auth.admin.createUser({ phone, password, phone_confirm: true, user_metadata: { username, gender, phone, age } });
    if (createError || !created.user) return json({ error: createError?.message || "Could not create account." }, createError?.status === 422 ? 409 : 500);
    createdUserId = created.user.id;
    const { error: securityError } = await admin.from("phone_account_security").insert({ user_id: createdUserId, device_hash: dHash, recovery_question: question, recovery_answer_hash: answerHash });
    if (securityError) { await admin.auth.admin.deleteUser(createdUserId); createdUserId = null; if (securityError.code === "23505") return json({ error: "This device already has a phone account. Please log in to your existing account." }, 409); return json({ error: "Could not finish secure account setup." }, 500); }
    const signInResponse = await fetch(`${SUPABASE_URL}/auth/v1/token?grant_type=password`, { method: "POST", headers: { apikey: ANON_KEY, "Content-Type": "application/json" }, body: JSON.stringify({ phone, password }) });
    const signInText = await signInResponse.text(); if (!signInResponse.ok) return json({ error: "Account was created, but automatic login failed. Please log in with your phone and password." }, 500);
    const session = JSON.parse(signInText); return json({ access_token: session.access_token, refresh_token: session.refresh_token, user: session.user ?? created.user });
  } catch (e) { if (createdUserId) await admin.auth.admin.deleteUser(createdUserId).catch(() => undefined); return json({ error: e instanceof Error ? e.message : "Could not create account." }, 500); }
});
