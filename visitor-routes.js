const crypto = require("crypto");

function getCookie(req, name) {
  const cookieHeader = req.headers.cookie || "";

  for (const part of cookieHeader.split(";")) {
    const i = part.indexOf("=");

    if (i === -1) continue;

    const key = part.slice(0, i).trim();
    const value = part.slice(i + 1).trim();

    if (key === name) return value;
  }

  return null;
}

function install(app, { supabase }) {
  /* =========================================================
     PUBLIC VISITOR COUNTER
     ========================================================= */

  async function getVisitorTotal() {
    const { data, error } = await supabase
      .from("site_stats")
      .select("total_visitors")
      .eq("id", 1)
      .maybeSingle();

    if (error) throw error;
    return Number(data?.total_visitors ?? 0);
  }

  async function handleVisitorCount(req, res) {
    try {
      const userAgent = String(req.headers["user-agent"] || "").toLowerCase();
      const likelyBot = /bot|crawler|spider|slurp|bingpreview|facebookexternalhit|linkedinbot|whatsapp|telegrambot|headless/i.test(userAgent);

      const cookieName = "hh_visitor_id_v2";
      const existingVisitor = getCookie(req, cookieName);

      if (!existingVisitor && !likelyBot) {
        const visitorId = crypto.randomBytes(24).toString("hex");

        const { data, error } = await supabase.rpc("increment_site_visitors");

        if (error) {
          console.error("Visitor counter RPC error:", error);
          return res.status(503).json({ error: "visitor_counter_unavailable" });
        }

        res.set(
          "Set-Cookie",
          cookieName + "=" + visitorId + "; Max-Age=31536000; Path=/; HttpOnly; Secure; SameSite=Lax"
        );

        res.set("Cache-Control", "no-store, no-cache, must-revalidate");
        return res.json({ total_visitors: Number(data ?? 0) });
      }

      const totalVisitors = await getVisitorTotal();
      res.set("Cache-Control", "no-store, no-cache, must-revalidate");
      return res.json({ total_visitors: totalVisitors });
    } catch (error) {
      console.error("Visitor counter request error:", error);
      return res.status(503).json({ error: "visitor_counter_unavailable" });
    }
  }

  app.get("/api/visitor-count", handleVisitorCount);
  app.post("/api/visitor-count", handleVisitorCount);

  /* =========================================================
     SONG LIKES
     ========================================================= */

  function validSongId(value) {
    const n = Number(value);
    return Number.isInteger(n) && n > 0 ? n : null;
  }

  function getLikeVisitorId(req) {
    return getCookie(req, "hh_like_visitor_id");
  }

  function ensureLikeVisitorId(req, res) {
    let visitorId = getLikeVisitorId(req);

    if (!visitorId) {
      visitorId = crypto.randomBytes(24).toString("hex");
      res.set(
        "Set-Cookie",
        "hh_like_visitor_id=" + visitorId + "; Max-Age=31536000; Path=/; HttpOnly; Secure; SameSite=Lax"
      );
    }

    return visitorId;
  }

  async function getSongLikeState(songId, visitorId) {
    const { count, error: countError } = await supabase
      .from("song_likes")
      .select("id", { count: "exact", head: true })
      .eq("song_id", songId);

    if (countError) throw countError;

    let liked = false;

    if (visitorId) {
      const { data, error } = await supabase
        .from("song_likes")
        .select("id")
        .eq("song_id", songId)
        .eq("visitor_id", visitorId)
        .maybeSingle();

      if (error) throw error;
      liked = Boolean(data);
    }

    return {
      like_count: Number(count || 0),
      liked
    };
  }

  app.get("/api/song-likes/:id", async (req, res) => {
    try {
      const songId = validSongId(req.params.id);
      if (!songId) return res.status(400).json({ error: "invalid_song_id" });

      const state = await getSongLikeState(songId, getLikeVisitorId(req));
      res.set("Cache-Control", "no-store, no-cache, must-revalidate");
      res.json(state);
    } catch (error) {
      console.error("Song likes GET error:", error);
      res.status(503).json({ error: "song_likes_unavailable" });
    }
  });

  app.post("/api/song-likes/:id", async (req, res) => {
    try {
      const songId = validSongId(req.params.id);
      if (!songId) return res.status(400).json({ error: "invalid_song_id" });

      const visitorId = ensureLikeVisitorId(req, res);

      const { error } = await supabase
        .from("song_likes")
        .insert({ song_id: songId, visitor_id: visitorId });

      if (error && error.code !== "23505") throw error;

      const state = await getSongLikeState(songId, visitorId);
      res.set("Cache-Control", "no-store, no-cache, must-revalidate");
      res.json(state);
    } catch (error) {
      console.error("Song likes POST error:", error);
      res.status(503).json({ error: "song_likes_unavailable" });
    }
  });

  app.delete("/api/song-likes/:id", async (req, res) => {
    try {
      const songId = validSongId(req.params.id);
      if (!songId) return res.status(400).json({ error: "invalid_song_id" });

      const visitorId = getLikeVisitorId(req);
      if (visitorId) {
        const { error } = await supabase
          .from("song_likes")
          .delete()
          .eq("song_id", songId)
          .eq("visitor_id", visitorId);

        if (error) throw error;
      }

      const state = await getSongLikeState(songId, visitorId);
      res.set("Cache-Control", "no-store, no-cache, must-revalidate");
      res.json(state);
    } catch (error) {
      console.error("Song likes DELETE error:", error);
      res.status(503).json({ error: "song_likes_unavailable" });
    }
  });

  /* =========================================================
     SONG LIKE SCRIPT FOR SERVER-RENDERED SONG HUB
     ========================================================= */

  app.use((req, res, next) => {
    const originalSend = res.send.bind(res);

    res.send = function (body) {
      if (
        req.path === "/song.html" &&
        typeof body === "string" &&
        body.includes('id="songPage"') &&
        !body.includes("/song-likes.js")
      ) {
        body = body.replace(
          "</body>",
          '<script src="/song-likes.js"></script>\n</body>'
        );
      }

      return originalSend(body);
    };

    next();
  });

  /* =========================================================
     ANDROID ADMIN STUDIO HANDOFF
     ========================================================= */

  const MOBILE_HANDOFF_TTL = 60 * 1000;
  const mobileHandoffs = new Map();
  const STUDIO_SESSION_COOKIE = "hh_studio_session";
  const STUDIO_SESSION_TTL = 8 * 60 * 60 * 1000;

  function studioSessionSecret() {
    return (
      `${process.env.STUDIO_SESSION_SECRET || "heartbeat-heaven-session"}:` +
      `${process.env.ADMIN_PASSWORD || ""}:heartbeat-heaven-studio`
    );
  }

  function createStudioSession() {
    const data = Buffer
      .from(
        JSON.stringify({
          user: process.env.ADMIN_USER,
          exp: Date.now() + STUDIO_SESSION_TTL
        }),
        "utf8"
      )
      .toString("base64url");

    const signature = crypto
      .createHmac("sha256", studioSessionSecret())
      .update(data)
      .digest("base64url");

    return `${data}.${signature}`;
  }

  function setStudioSessionCookie(res, token) {
    res.set(
      "Set-Cookie",
      `${STUDIO_SESSION_COOKIE}=${token}; Max-Age=${Math.floor(STUDIO_SESSION_TTL / 1000)}; Path=/; HttpOnly; Secure; SameSite=Lax`
    );
  }

  function cleanupMobileHandoffs() {
    const now = Date.now();
    for (const [token, entry] of mobileHandoffs.entries()) {
      if (!entry || entry.exp <= now) mobileHandoffs.delete(token);
    }
  }

  app.post("/api/studio/mobile-handoff", async (req, res) => {
    try {
      cleanupMobileHandoffs();

      const authorization = String(req.headers.authorization || "");
      const match = authorization.match(/^Bearer\s+(.+)$/i);
      const accessToken = match?.[1]?.trim();

      if (!accessToken) {
        return res.status(401).json({ error: "Native app authentication required." });
      }

      const {
        data: userData,
        error: userError
      } = await supabase.auth.getUser(accessToken);

      if (userError || !userData?.user) {
        return res.status(401).json({ error: "Native app session is invalid or expired." });
      }

      const { data: profile, error: profileError } = await supabase
        .from("profiles")
        .select("id,role")
        .eq("id", userData.user.id)
        .maybeSingle();

      if (profileError) {
        console.error("Mobile Studio profile check error:", profileError);
        return res.status(503).json({ error: "Unable to verify Studio access." });
      }

      if (!profile || profile.role !== "admin") {
        return res.status(403).json({ error: "Studio access restricted." });
      }

      if (!process.env.ADMIN_USER || !process.env.ADMIN_PASSWORD) {
        return res.status(503).json({ error: "Studio is not configured." });
      }

      const handoffToken = crypto.randomBytes(32).toString("base64url");
      mobileHandoffs.set(handoffToken, {
        userId: userData.user.id,
        exp: Date.now() + MOBILE_HANDOFF_TTL
      });

      res.set("Cache-Control", "no-store, no-cache, must-revalidate");
      res.set("Pragma", "no-cache");
      res.json({
        handoff_url:
          `https://heartbeat-heaven.onrender.com/api/studio/mobile-handoff/${encodeURIComponent(handoffToken)}`
      });
    } catch (error) {
      console.error("Mobile Studio handoff error:", error);
      res.status(503).json({ error: "Unable to prepare Studio access." });
    }
  });

  app.get("/api/studio/mobile-handoff/:token", async (req, res) => {
    try {
      cleanupMobileHandoffs();

      const token = String(req.params.token || "");
      const entry = mobileHandoffs.get(token);

      mobileHandoffs.delete(token);

      if (!entry || entry.exp <= Date.now()) {
        return res.status(401).send("Studio handoff expired. Please return to the app and try again.");
      }

      const { data: profile, error: profileError } = await supabase
        .from("profiles")
        .select("id,role")
        .eq("id", entry.userId)
        .maybeSingle();

      if (profileError || !profile || profile.role !== "admin") {
        return res.status(403).send("Studio access restricted.");
      }

      const sessionToken = createStudioSession();
      setStudioSessionCookie(res, sessionToken);
      res.set("Cache-Control", "no-store, no-cache, must-revalidate");
      res.set("Pragma", "no-cache");
      res.set("Referrer-Policy", "no-referrer");
      res.redirect(303, "/admin.html");
    } catch (error) {
      console.error("Mobile Studio handoff exchange error:", error);
      res.status(503).send("Unable to open Studio. Please try again from the app.");
    }
  });
}

module.exports = {
  install
};
