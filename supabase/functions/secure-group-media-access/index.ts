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

function isUuid(value: string) {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

function isGroupMediaPath(path: string, groupId: string) {
  if (!path || path.length > 300 || !path.startsWith(groupId + "/")) return false;
  const suffix = path.slice(groupId.length + 1);
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\.(jpg|jpeg|png|webp|gif|mp4|webm|mov)$/i.test(suffix);
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
    const messageId = String(body.message_id ?? "").trim();
    const requestedPath = String(body.media_path ?? "").trim();
    const mode = String(body.mode ?? "user");
    const reason = String(body.reason ?? "").trim();

    if (!isUuid(messageId)) return json({ error: "Invalid message id." }, 400);
    if (mode !== "user" && mode !== "admin") return json({ error: "Invalid access mode." }, 400);
    if (mode === "admin" && reason.length < 5) {
      return json({ error: "A reason is required for admin media access." }, 400);
    }

    const { data: profile, error: profileError } = await admin
      .from("profiles")
      .select("role")
      .eq("id", user.id)
      .maybeSingle();

    if (profileError) return json({ error: "Could not verify access." }, 500);

    const isAdmin = profile?.role === "admin";
    if (mode === "admin" && !isAdmin) return json({ error: "Admin access required." }, 403);

    const { data: message, error: messageError } = await admin
      .from("group_messages")
      .select("id,group_id,sender_id,message_type,media_path,deleted_at")
      .eq("id", messageId)
      .maybeSingle();

    if (messageError) return json({ error: "Could not verify group media." }, 500);
    if (!message) return json({ error: "Group message not found." }, 404);
    if (message.deleted_at) return json({ error: "Group media is no longer available." }, 404);
    if (!["image", "video", "audio"].includes(message.message_type)) {
      return json({ error: "Unsupported group media type." }, 400);
    }
    if (!message.media_path) return json({ error: "Group media not found." }, 404);

    const path = String(message.media_path);
    if (requestedPath && requestedPath !== path) return json({ error: "Media reference mismatch." }, 400);
    if (!isGroupMediaPath(path, String(message.group_id))) {
      return json({ error: "Unsupported group media reference." }, 400);
    }

    if (mode === "user") {
      const { data: membership, error: membershipError } = await admin
        .from("group_members")
        .select("user_id")
        .eq("group_id", message.group_id)
        .eq("user_id", user.id)
        .is("left_at", null)
        .maybeSingle();

      if (membershipError) return json({ error: "Could not verify group membership." }, 500);
      if (!membership) return json({ error: "Group media access denied." }, 403);
    }

    const expiresIn = mode === "admin" ? 300 : 600;
    const { data: signed, error: signError } = await admin.storage
      .from("group-media")
      .createSignedUrl(path, expiresIn);

    if (signError || !signed?.signedUrl) {
      return json({ error: "Could not create secure group media access." }, 500);
    }

    const expiresAt = new Date(Date.now() + expiresIn * 1000).toISOString();
    const { error: auditError } = await admin.rpc("log_storage_access", {
      p_accessor_id: user.id,
      p_access_mode: mode,
      p_bucket_id: "group-media",
      p_object_path: path,
      p_message_id: message.id,
      p_reason: mode === "admin" ? reason.slice(0, 500) : null,
      p_expires_at: expiresAt,
    });

    if (auditError) return json({ error: "Could not record media access." }, 500);

    return json({
      url: signed.signedUrl,
      expires_at: expiresAt,
      message_id: message.id,
      group_id: message.group_id,
      access_mode: mode,
    });
  } catch (error) {
    console.error("secure-group-media-access error", error);
    return json({ error: "Secure group media access failed." }, 500);
  }
});