import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

const admin = createClient(SUPABASE_URL, SERVICE_ROLE_KEY, {
  auth: { autoRefreshToken: false, persistSession: false },
});

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

function parseObjectUrl(value: string): { bucket: string; path: string } | null {
  try {
    const url = new URL(value);
    const marker = "/storage/v1/object/public/";
    const i = url.pathname.indexOf(marker);
    if (i < 0) return null;
    const rest = url.pathname.slice(i + marker.length);
    const slash = rest.indexOf("/");
    if (slash <= 0 || slash === rest.length - 1) return null;
    return {
      bucket: decodeURIComponent(rest.slice(0, slash)),
      path: decodeURIComponent(rest.slice(slash + 1)),
    };
  } catch {
    return null;
  }
}

function isValidUuid(value: string) {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "Method not allowed." }, 405);

  try {
    const authHeader = req.headers.get("Authorization") ?? "";
    const token = authHeader.replace(/^Bearer\s+/i, "").trim();
    if (!token) return json({ error: "Authentication required." }, 401);

    const { data: userData, error: userError } = await admin.auth.getUser(token);
    const user = userData.user;
    if (userError || !user?.id) return json({ error: "Authentication required." }, 401);

    const body = await req.json().catch(() => ({}));
    const requestedUrl = String(body.media_url ?? "").trim();
    const requestedMessageId = String(body.message_id ?? "").trim();
    const mode = String(body.mode ?? "user");
    const reason = String(body.reason ?? "").trim();

    if (mode !== "user" && mode !== "admin") return json({ error: "Invalid access mode." }, 400);
    if (!requestedUrl && !requestedMessageId) return json({ error: "Media reference is required." }, 400);
    if (mode === "admin" && reason.length < 5) return json({ error: "A reason is required for admin media access." }, 400);
    if (requestedMessageId && !isValidUuid(requestedMessageId)) return json({ error: "Invalid message id." }, 400);

    const { data: profile, error: profileError } = await admin
      .from("profiles")
      .select("role")
      .eq("id", user.id)
      .maybeSingle();
    if (profileError) return json({ error: "Could not verify access." }, 500);

    const isAdmin = profile?.role === "admin";
    if (mode === "admin" && !isAdmin) return json({ error: "Admin access required." }, 403);

    let messageQuery = admin
      .from("messages")
      .select("id,sender_id,receiver_id,media_url,deleted_at")
      .not("media_url", "is", null)
      .limit(1);

    if (requestedMessageId) {
      messageQuery = messageQuery.eq("id", requestedMessageId);
    } else {
      messageQuery = messageQuery.eq("media_url", requestedUrl);
    }

    const { data: messages, error: messageError } = await messageQuery;
    if (messageError) return json({ error: "Could not verify media." }, 500);

    const message = messages?.[0];
    if (!message?.media_url || message.deleted_at) return json({ error: "Media not found." }, 404);

    const parsed = parseObjectUrl(String(message.media_url));
    if (!parsed || parsed.bucket !== "chat-media") {
      return json({ error: "Unsupported media reference." }, 400);
    }

    const participant = message.sender_id === user.id || message.receiver_id === user.id;
    if (mode === "user" && !participant) return json({ error: "Media access denied." }, 403);
    if (mode === "admin" && !isAdmin) return json({ error: "Admin access required." }, 403);

    const expiresIn = mode === "admin" ? 300 : 600;
    const { data: signed, error: signError } = await admin.storage
      .from(parsed.bucket)
      .createSignedUrl(parsed.path, expiresIn);
    if (signError || !signed?.signedUrl) return json({ error: "Could not create secure media access." }, 500);

    const expiresAt = new Date(Date.now() + expiresIn * 1000).toISOString();
    const { error: auditError } = await admin
      .schema("private")
      .from("storage_access_audit")
      .insert({
        accessor_id: user.id,
        access_mode: mode,
        bucket_id: parsed.bucket,
        object_path: parsed.path,
        message_id: message.id,
        reason: mode === "admin" ? reason.slice(0, 500) : null,
        expires_at: expiresAt,
      });
    if (auditError) return json({ error: "Could not record media access." }, 500);

    return json({
      url: signed.signedUrl,
      expires_at: expiresAt,
      message_id: message.id,
      access_mode: mode,
    });
  } catch (error) {
    console.error("secure-media-access error", error);
    return json({ error: "Secure media access failed." }, 500);
  }
});
