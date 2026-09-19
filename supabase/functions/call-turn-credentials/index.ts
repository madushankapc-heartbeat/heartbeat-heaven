import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
};

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });

  const auth = req.headers.get("Authorization");
  if (!auth?.startsWith("Bearer ")) {
    return new Response(JSON.stringify({ error: "AUTH_REQUIRED" }), {
      status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }

  const keyId = Deno.env.get("CLOUDFLARE_TURN_KEY_ID");
  const apiToken = Deno.env.get("CLOUDFLARE_TURN_API_TOKEN");
  if (!keyId || !apiToken) {
    return new Response(JSON.stringify({ error: "TURN_NOT_CONFIGURED" }), {
      status: 503, headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }

  const response = await fetch(
    `https://rtc.live.cloudflare.com/v1/turn/keys/${encodeURIComponent(keyId)}/credentials/generate-ice-servers`,
    {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${apiToken}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ ttl: 3600 }),
    },
  );

  if (!response.ok) {
    return new Response(JSON.stringify({ error: "TURN_PROVIDER_ERROR" }), {
      status: 502, headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }

  const payload = await response.json();
  const iceServers = Array.isArray(payload?.iceServers)
    ? payload.iceServers.map((server: Record<string, unknown>) => ({
        urls: Array.isArray(server.urls)
          ? server.urls.filter((url: unknown) => typeof url === "string" && !url.includes(":53"))
          : typeof server.urls === "string" ? [server.urls] : [],
        ...(typeof server.username === "string" ? { username: server.username } : {}),
        ...(typeof server.credential === "string" ? { credential: server.credential } : {}),
      })).filter((server: { urls: string[] }) => server.urls.length > 0)
    : [];

  if (!iceServers.some((server: { urls: string[] }) =>
    server.urls.some((url: string) => url.startsWith("turn:") || url.startsWith("turns:"))
  )) {
    return new Response(JSON.stringify({ error: "TURN_PROVIDER_NO_RELAY" }), {
      status: 502, headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }

  return new Response(JSON.stringify({ iceServers, iceTransportPolicy: "relay", expiresInSeconds: 3600 }), {
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
});
