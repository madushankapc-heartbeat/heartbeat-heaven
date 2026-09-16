import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const PUBLISHABLE_KEY = Deno.env.get("SUPABASE_PUBLISHABLE_KEY") ?? Deno.env.get("SUPABASE_ANON_KEY")!;

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...cors, "Content-Type": "application/json" },
  });
}

function normalizePhone(value: string) {
  const raw = value.trim().replace(/[\s\-()]/g, "");
  if (raw.startsWith("+")) return "+" + raw.slice(1).replace(/\D/g, "");
  const digits = raw.replace(/\D/g, "");
  if (digits.startsWith("0") && digits.length >= 9) return "+94" + digits.slice(1);
  if (digits.startsWith("94") && digits.length >= 10) return "+" + digits;
  return digits.length >= 8 ? "+" + digits : "";
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);

  try {
    const body = await req.json();
    const identifier = String(body.identifier ?? body.phone ?? "").trim();
    const password = String(body.password ?? "");
    if (!identifier || !password) return json({ error: "Enter your phone/username and password." }, 400);
    if (identifier.includes("@")) return json({ error: "Invalid phone or username." }, 400);

    const admin = createClient(SUPABASE_URL, SERVICE_ROLE_KEY, {
      auth: { autoRefreshToken: false, persistSession: false },
    });

    let profileQuery = admin.from("profiles").select("id,phone,username").limit(1);
    if (/[A-Za-z]/.test(identifier)) {
      profileQuery = profileQuery.ilike("username", identifier);
    } else {
      const phone = normalizePhone(identifier);
      if (!phone) return json({ error: "Enter a valid mobile number." }, 400);
      const digits = phone.replace(/\D/g, "");
      profileQuery = profileQuery.or(`phone.eq.${phone},phone.eq.${digits}`);
    }

    const { data: profile, error: profileError } = await profileQuery.maybeSingle();
    if (profileError || !profile?.id) return json({ error: "Invalid username/phone or password." }, 401);

    const { data: userData, error: userError } = await admin.auth.admin.getUserById(profile.id);
    if (userError || !userData.user) return json({ error: "Invalid username/phone or password." }, 401);

    const user = userData.user;
    let loginEmail = user.email;

    if (!loginEmail) {
      const phoneDigits = String(profile.phone ?? "").replace(/\D/g, "");
      if (!phoneDigits) return json({ error: "This account has no valid phone number." }, 400);
      loginEmail = `phone_${phoneDigits}@phone.heartbeat-heaven.invalid`;
      const { data: updated, error: updateError } = await admin.auth.admin.updateUserById(user.id, {
        email: loginEmail,
        email_confirm: true,
      });
      if (updateError || !updated.user?.email) {
        console.error("phone-login: failed to assign internal email alias", updateError);
        return json({ error: "Could not prepare phone login. Please try again." }, 500);
      }
      loginEmail = updated.user.email;
    }

    const signInResponse = await fetch(`${SUPABASE_URL}/auth/v1/token?grant_type=password`, {
      method: "POST",
      headers: { apikey: PUBLISHABLE_KEY, "Content-Type": "application/json" },
      body: JSON.stringify({ email: loginEmail, password }),
    });

    const signInText = await signInResponse.text();
    if (!signInResponse.ok) {
      let message = "Invalid username/phone or password.";
      try {
        const error = JSON.parse(signInText);
        message = error.msg || error.message || error.error_description || message;
      } catch (_) {}
      return json({ error: message }, signInResponse.status >= 400 && signInResponse.status < 500 ? 401 : 500);
    }

    const session = JSON.parse(signInText);
    return json({ access_token: session.access_token, refresh_token: session.refresh_token, user: session.user });
  } catch (e) {
    console.error("phone-login error", e);
    return json({ error: e instanceof Error ? e.message : "Phone login failed." }, 500);
  }
});
