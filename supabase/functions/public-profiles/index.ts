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

function escapeIlike(value: string) {
  return value.replace(/[\\%_]/g, (m) => "\\" + m);
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);

  try {
    const authHeader = req.headers.get("Authorization") ?? "";
    const token = authHeader.replace(/^Bearer\s+/i, "").trim();
    if (!token) return json({ error: "Authentication required." }, 401);

    const { data: userData, error: userError } = await admin.auth.getUser(token);
    if (userError || !userData.user?.id) return json({ error: "Authentication required." }, 401);

    const mine = userData.user.id;
    const body = await req.json().catch(() => ({}));
    const mode = String(body.mode ?? "");
    const q = String(body.q ?? "").trim();
    const ids = Array.isArray(body.ids) ? body.ids.map(String).filter(Boolean).slice(0, 500) : [];
    const since = String(body.since ?? "").trim();

    if (!["online", "search", "ids"].includes(mode)) {
      return json({ error: "Invalid profile query." }, 400);
    }

    let query = admin
      .from("profiles")
      .select("id,username,gender,avatar_url,bio,last_seen_at,last_seen_visibility");

    if (mode === "search") {
      if (!q) return json([]);
      query = query.ilike("username", "%" + escapeIlike(q) + "%").limit(20);
    } else if (mode === "ids") {
      if (ids.length === 0) return json([]);
      query = query.in("id", ids).limit(500);
    } else {
      if (!since) return json([]);
      const sinceDate = new Date(since);
      if (Number.isNaN(sinceDate.getTime())) return json({ error: "Invalid since value." }, 400);
      query = query.gte("last_seen_at", sinceDate.toISOString()).limit(100);
    }

    const { data: profiles, error: profileError } = await query;
    if (profileError) return json({ error: "Could not load public profiles." }, 500);

    const { data: blocks, error: blockError } = await admin
      .from("user_blocks")
      .select("blocker_id,blocked_id")
      .or(`blocker_id.eq.${mine},blocked_id.eq.${mine}`)
      .limit(1000);
    if (blockError) return json({ error: "Could not verify profile visibility." }, 500);

    const blocked = new Set<string>();
    for (const row of blocks ?? []) {
      if (row.blocker_id === mine) blocked.add(row.blocked_id);
      if (row.blocked_id === mine) blocked.add(row.blocker_id);
    }

    const { data: friendships, error: friendshipError } = await admin
      .from("friendships")
      .select("requester_id,addressee_id")
      .eq("status", "accepted")
      .or(`requester_id.eq.${mine},addressee_id.eq.${mine}`)
      .limit(1000);
    if (friendshipError) return json({ error: "Could not verify profile visibility." }, 500);

    const friendIds = new Set<string>();
    for (const row of friendships ?? []) {
      friendIds.add(row.requester_id === mine ? row.addressee_id : row.requester_id);
    }

    const result = (profiles ?? [])
      .filter((p) => p.id === mine || !blocked.has(p.id))
      .map((p) => {
        const isFriend = friendIds.has(p.id);
        const canSeeLastSeen =
          p.id === mine ||
          p.last_seen_visibility === "everyone" ||
          (p.last_seen_visibility === "friends" && isFriend);

        return {
          id: p.id,
          username: p.username,
          gender: p.gender,
          avatar_url: p.avatar_url,
          bio: p.bio,
          last_seen_at: canSeeLastSeen ? p.last_seen_at : null,
          last_seen_visibility: p.last_seen_visibility,
        };
      })
      .filter((p) => mode !== "online" || (p.id !== mine && p.last_seen_at !== null));

    return json(result);
  } catch (error) {
    console.error("public-profiles error", error);
    return json({ error: "Public profile lookup failed." }, 500);
  }
});
