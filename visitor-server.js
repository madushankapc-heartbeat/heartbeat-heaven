const Module = require("module");
const fs = require("fs");
const path = require("path");

const originalLoader = Module._extensions[".js"];
const serverPath = path.join(__dirname, "server.js");

Module._extensions[".js"] = function (module, filename) {
  if (path.resolve(filename) === path.resolve(serverPath)) {
    let source = fs.readFileSync(filename, "utf8");

    const marker = 'app.use(express.urlencoded({ extended: true }));';

    const injection = `

/* =========================================================
   ADMIN LOGIN RATE LIMITING
   ========================================================= */

app.set("trust proxy", 1);

const loginAttempts = new Map();
const LOGIN_WINDOW_MS = 60 * 60 * 1000;
const LOGIN_SHORT_WINDOW_MS = 10 * 60 * 1000;
const LOGIN_SHORT_LIMIT = 5;
const LOGIN_LONG_LIMIT = 15;
const LOGIN_SHORT_BLOCK_MS = 10 * 60 * 1000;
const LOGIN_LONG_BLOCK_MS = 60 * 60 * 1000;

function getLoginClientKey(req) {
  return String(req.ip || "unknown");
}

function getLoginAttemptState(key, now) {
  const existing = loginAttempts.get(key) || {
    failures: [],
    blockedUntil: 0
  };

  existing.failures = existing.failures.filter(
    (timestamp) => now - timestamp < LOGIN_WINDOW_MS
  );

  if (
    existing.blockedUntil &&
    now >= existing.blockedUntil
  ) {
    existing.blockedUntil = 0;
  }

  if (
    existing.failures.length === 0 &&
    !existing.blockedUntil
  ) {
    loginAttempts.delete(key);
    return null;
  }

  loginAttempts.set(key, existing);
  return existing;
}

function loginRateLimit(req, res, next) {
  const key = getLoginClientKey(req);
  const now = Date.now();
  const state = getLoginAttemptState(key, now);

  if (state?.blockedUntil && now < state.blockedUntil) {
    const retryAfter = Math.max(
      1,
      Math.ceil((state.blockedUntil - now) / 1000)
    );

    res.set("Retry-After", String(retryAfter));

    return res.status(429).json({
      success: false,
      error: "Too many login attempts. Please try again later."
    });
  }

  let recorded = false;

  res.on("finish", () => {
    if (recorded) return;
    recorded = true;

    const finishedAt = Date.now();

    if (res.statusCode === 401) {
      const current = getLoginAttemptState(key, finishedAt) || {
        failures: [],
        blockedUntil: 0
      };

      current.failures.push(finishedAt);

      const recentFailures = current.failures.filter(
        (timestamp) =>
          finishedAt - timestamp < LOGIN_SHORT_WINDOW_MS
      );

      if (recentFailures.length >= LOGIN_SHORT_LIMIT) {
        current.blockedUntil = Math.max(
          current.blockedUntil || 0,
          finishedAt + LOGIN_SHORT_BLOCK_MS
        );
      }

      if (current.failures.length >= LOGIN_LONG_LIMIT) {
        current.blockedUntil = Math.max(
          current.blockedUntil || 0,
          finishedAt + LOGIN_LONG_BLOCK_MS
        );
      }

      loginAttempts.set(key, current);
    } else if (res.statusCode === 200) {
      loginAttempts.delete(key);
    }
  });

  next();
}

const originalAppPost = app.post.bind(app);

app.post = function (route, ...handlers) {
  if (route === "/api/studio/login") {
    handlers.unshift(loginRateLimit);
  }

  return originalAppPost(route, ...handlers);
};

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

async function ensureLikeVisitorId(req, res) {
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

    const visitorId = await ensureLikeVisitorId(req, res);

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
      !body.includes('/song-likes.js')
    ) {
      body = body.replace(
        "</body>",
        '<script src="/song-likes.js"></script>\\n</body>'
      );
    }

    return originalSend(body);
  };

  next();
});`;

    if (!source.includes('app.post("/api/visitor-count"')) {
      if (!source.includes(marker)) {
        throw new Error("Visitor counter injection marker not found in server.js");
      }

      source = source.replace(marker, `${marker}${injection}`);
    }

    return module._compile(source, filename);
  }

  return originalLoader(module, filename);
};

require("./server.js");
