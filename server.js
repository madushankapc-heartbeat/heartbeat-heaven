const express = require("express");
const multer = require("multer");
const path = require("path");
const crypto = require("crypto");
const { createClient } = require("@supabase/supabase-js");

const app = express();
const PORT = process.env.PORT || 10000;

const SUPABASE_URL = process.env.SUPABASE_URL;

const supabase = createClient(
  SUPABASE_URL,
  process.env.SUPABASE_SERVICE_ROLE_KEY,
  {
    auth: {
      persistSession: false,
      autoRefreshToken: false
    }
  }
);

/* =========================================================
   LEGACY MULTIPART UPLOAD
   ========================================================= */

const upload = multer({
  storage: multer.memoryStorage(),
  limits: {
    fileSize: 50 * 1024 * 1024
  }
});

/* =========================================================
   STUDIO SECURITY — CUSTOM LOGIN
   ========================================================= */

const ADMIN_USER = process.env.ADMIN_USER;
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD;

const SESSION_COOKIE = "hh_studio_session";
const SESSION_TTL = 8 * 60 * 60 * 1000;

const SESSION_SECRET =
  `${process.env.STUDIO_SESSION_SECRET || "heartbeat-heaven-session"}:` +
  `${ADMIN_PASSWORD || ""}:heartbeat-heaven-studio`;

function safeCompare(a, b) {
  const aBuffer = Buffer.from(String(a), "utf8");
  const bBuffer = Buffer.from(String(b), "utf8");

  if (aBuffer.length !== bBuffer.length) return false;

  return crypto.timingSafeEqual(aBuffer, bBuffer);
}

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

function createStudioSession() {
  const payload = {
    user: ADMIN_USER,
    exp: Date.now() + SESSION_TTL
  };

  const data = Buffer
    .from(JSON.stringify(payload), "utf8")
    .toString("base64url");

  const signature = crypto
    .createHmac("sha256", SESSION_SECRET)
    .update(data)
    .digest("base64url");

  return `${data}.${signature}`;
}

function verifyStudioSession(token) {
  if (!token) return false;

  const parts = token.split(".");

  if (parts.length !== 2) return false;

  const [data, signature] = parts;

  const expected = crypto
    .createHmac("sha256", SESSION_SECRET)
    .update(data)
    .digest("base64url");

  const a = Buffer.from(signature, "utf8");
  const b = Buffer.from(expected, "utf8");

  if (a.length !== b.length) return false;

  if (!crypto.timingSafeEqual(a, b)) return false;

  try {
    const payload = JSON.parse(
      Buffer.from(data, "base64url").toString("utf8")
    );

    if (!payload.user || !payload.exp) return false;

    if (!ADMIN_USER || !safeCompare(payload.user, ADMIN_USER)) {
      return false;
    }

    if (Date.now() >= payload.exp) return false;

    return true;
  } catch {
    return false;
  }
}

function setStudioCookie(res, token) {
  res.set(
    "Set-Cookie",
    `${SESSION_COOKIE}=${token}; Max-Age=${Math.floor(
      SESSION_TTL / 1000
    )}; Path=/; HttpOnly; Secure; SameSite=Lax`
  );
}

function clearStudioCookie(res) {
  res.set(
    "Set-Cookie",
    `${SESSION_COOKIE}=; Max-Age=0; Path=/; HttpOnly; Secure; SameSite=Lax`
  );
}

function requireStudioAuth(req, res, next) {
  if (!ADMIN_USER || !ADMIN_PASSWORD) {
    return res.status(503).json({
      error:
        "Studio is not configured. Please set ADMIN_USER and ADMIN_PASSWORD."
    });
  }

  const token = getCookie(req, SESSION_COOKIE);

  if (!verifyStudioSession(token)) {
    return res.status(401).json({
      error: "Studio login required."
    });
  }

  next();
}

function requireStudioPage(req, res, next) {
  if (!ADMIN_USER || !ADMIN_PASSWORD) {
    return res
      .status(503)
      .send(
        "Studio is not configured. Please set ADMIN_USER and ADMIN_PASSWORD."
      );
  }

  const token = getCookie(req, SESSION_COOKIE);

  if (!verifyStudioSession(token)) {
    return res.redirect("/studio-login.html");
  }

  res.set("Cache-Control", "no-store");

  next();
}

/* =========================================================
   BASIC MIDDLEWARE
   ========================================================= */

app.use(express.json({ limit: "2mb" }));
app.use(express.urlencoded({ extended: true }));

/* =========================================================
   STUDIO LOGIN
   ========================================================= */

app.get("/studio-login.html", (req, res) => {
  if (
    ADMIN_USER &&
    ADMIN_PASSWORD &&
    verifyStudioSession(getCookie(req, SESSION_COOKIE))
  ) {
    return res.redirect("/admin.html");
  }

  res.set("Cache-Control", "no-store");

  res.sendFile(
    path.join(__dirname, "public", "studio-login.html")
  );
});

app.post("/api/studio/login", (req, res) => {
  if (!ADMIN_USER || !ADMIN_PASSWORD) {
    return res.status(503).json({
      success: false,
      error:
        "Studio is not configured. Please set ADMIN_USER and ADMIN_PASSWORD."
    });
  }

  const username = String(req.body?.username || "");
  const password = String(req.body?.password || "");

  const userOk = safeCompare(username, ADMIN_USER);
  const passwordOk = safeCompare(password, ADMIN_PASSWORD);

  if (!userOk || !passwordOk) {
    return res.status(401).json({
      success: false,
      error: "Invalid username or password."
    });
  }

  const token = createStudioSession();

  setStudioCookie(res, token);

  res.set("Cache-Control", "no-store");

  res.json({
    success: true
  });
});

app.get("/api/studio/me", (req, res) => {
  if (!ADMIN_USER || !ADMIN_PASSWORD) {
    return res.status(503).json({
      authenticated: false,
      error: "Studio is not configured."
    });
  }

  const authenticated = verifyStudioSession(
    getCookie(req, SESSION_COOKIE)
  );

  if (!authenticated) {
    return res.status(401).json({
      authenticated: false
    });
  }

  res.set("Cache-Control", "no-store");

  res.json({
    authenticated: true
  });
});

app.post("/api/studio/logout", (req, res) => {
  clearStudioCookie(res);

  res.set("Cache-Control", "no-store");

  res.json({
    success: true
  });
});

/* =========================================================
   PROTECTED STUDIO FILES
   ========================================================= */

app.get(
  "/admin.html",
  requireStudioPage,
  (req, res) => {
    res.sendFile(
      path.join(__dirname, "public", "admin.html")
    );
  }
);

app.get(
  "/admin.js",
  requireStudioPage,
  (req, res) => {
    res.sendFile(
      path.join(__dirname, "public", "admin.js")
    );
  }
);

/* =========================================================
   HELPERS
   ========================================================= */

function safeFileName(name = "file") {
  const ext = path.extname(name).toLowerCase();

  const base = path
    .basename(name, ext)
    .replace(/[^a-zA-Z0-9-_]/g, "-")
    .replace(/-+/g, "-")
    .slice(0, 80) || "file";

  return `${Date.now()}-${base}${ext}`;
}

function publicUrl(bucket, file) {
  return (
    `${SUPABASE_URL}/storage/v1/object/public/${bucket}/` +
    `${encodeURIComponent(file).replace(/%2F/g, "/")}`
  );
}

function storagePath(url, bucket) {
  if (!url) return null;

  const marker =
    `/storage/v1/object/public/${bucket}/`;

  const i = url.indexOf(marker);

  if (i < 0) return null;

  return decodeURIComponent(
    url.slice(i + marker.length)
  );
}

function validStoragePath(value) {
  if (!value) return true;

  if (typeof value !== "string") return false;

  if (
    value.includes("/") ||
    value.includes("\\") ||
    value.includes("..")
  ) {
    return false;
  }

  return true;
}

/* =========================================================
   DIRECT SUPABASE STORAGE SIGNED UPLOAD URL
   ========================================================= */

app.post(
  "/api/studio/upload-url",
  requireStudioAuth,
  async (req, res) => {
    try {
      const bucket = String(
        req.body?.bucket || ""
      ).trim();

      const originalName = String(
        req.body?.name || "file"
      ).trim();

      const contentType = String(
        req.body?.contentType || ""
      ).trim();

      if (
        bucket !== "audio" &&
        bucket !== "covers"
      ) {
        return res.status(400).json({
          error: "Invalid storage bucket."
        });
      }

      if (
        bucket === "audio" &&
        contentType &&
        !contentType.startsWith("audio/")
      ) {
        return res.status(400).json({
          error:
            "The selected file is not an audio file."
        });
      }

      if (
        bucket === "covers" &&
        contentType &&
        !contentType.startsWith("image/")
      ) {
        return res.status(400).json({
          error:
            "The selected file is not an image file."
        });
      }

      const filePath = safeFileName(
        originalName
      );

      const {
        data,
        error
      } = await supabase.storage
        .from(bucket)
        .createSignedUploadUrl(filePath);

      if (error) {
        console.error(
          "Signed upload URL error:",
          error
        );

        return res.status(500).json({
          error:
            `Could not create upload URL: ${error.message}`
        });
      }

      if (
        !data ||
        !data.signedUrl ||
        !data.path
      ) {
        console.error(
          "Invalid signed upload response:",
          data
        );

        return res.status(500).json({
          error:
            "Supabase did not return a valid signed upload URL."
        });
      }

      res.json({
        bucket,
        path: data.path,
        signed_url: data.signedUrl,
        token: data.token || null,
        public_url: publicUrl(
          bucket,
          data.path
        ),
        content_type:
          contentType ||
          (
            bucket === "audio"
              ? "audio/mpeg"
              : "image/jpeg"
          )
      });
    } catch (error) {
      console.error(
        "Direct upload URL error:",
        error
      );

      res.status(500).json({
        error:
          error.message ||
          "Could not prepare upload."
      });
    }
  }
);

/* =========================================================
   ADMIN — ALL SONGS FOR STUDIO
   Includes originals + versions
   ========================================================= */

app.get(
  "/api/studio/songs",
  requireStudioAuth,
  async (req, res) => {
    try {
      const {
        data,
        error
      } = await supabase
        .from("songs")
        .select("*")
        .order("parent_song_id", {
          ascending: true,
          nullsFirst: true
        })
        .order("release_date", {
          ascending: false,
          nullsFirst: false
        })
        .order("created_at", {
          ascending: false
        });

      if (error) {
        return res.status(500).json({
          error: error.message
        });
      }

      res.json(data || []);
    } catch (error) {
      console.error(
        "Studio songs error:",
        error
      );

      res.status(500).json({
        error:
          error.message ||
          "Could not load studio songs."
      });
    }
  }
);

/* =========================================================
   ADMIN CREATE SONG / VERSION
   ========================================================= */

app.post(
  "/api/songs",
  requireStudioAuth,
  async (req, res) => {
    let uploadedCoverPath = null;
    let uploadedAudioPath = null;

    try {
      const {
        title,
        artist,
        genre,
        language,
        mood,
        description,
        lyrics,
        release_date,
        cover_path,
        audio_path,
        parent_song_id,
        version_name
      } = req.body || {};

      if (!title || !artist) {
        return res.status(400).json({
          error:
            "Title and artist are required."
        });
      }

      if (!validStoragePath(cover_path)) {
        return res.status(400).json({
          error:
            "Invalid cover storage path."
        });
      }

      if (!validStoragePath(audio_path)) {
        return res.status(400).json({
          error:
            "Invalid audio storage path."
        });
      }

      /*
       * ORIGINAL:
       * parent_song_id = null
       *
       * VERSION:
       * parent_song_id = original song ID
       */

      let finalParentId = null;
      let finalVersionName =
        "Original Version";

      if (
        parent_song_id !== null &&
        parent_song_id !== undefined &&
        String(parent_song_id).trim() !== ""
      ) {
        const parentId = String(
          parent_song_id
        ).trim();

        const {
          data: parentSong,
          error: parentError
        } = await supabase
          .from("songs")
          .select(
            "id,parent_song_id,title"
          )
          .eq("id", parentId)
          .single();

        if (
          parentError ||
          !parentSong
        ) {
          return res.status(400).json({
            error:
              "Selected original song was not found."
          });
        }

        if (
          parentSong.parent_song_id !== null
        ) {
          return res.status(400).json({
            error:
              "A version cannot be used as the parent song. Please select the original song."
          });
        }

        finalParentId =
          Number(parentSong.id);

        finalVersionName =
          String(
            version_name ||
            "New Version"
          ).trim() ||
          "New Version";
      } else {
        finalParentId = null;
        finalVersionName =
          "Original Version";
      }

      uploadedCoverPath =
        cover_path || null;

      uploadedAudioPath =
        audio_path || null;

      const cover_url = cover_path
        ? publicUrl(
            "covers",
            cover_path
          )
        : "";

      const audio_url = audio_path
        ? publicUrl(
            "audio",
            audio_path
          )
        : "";

      const {
        data,
        error
      } = await supabase
        .from("songs")
        .insert({
          title,
          artist,
          genre: genre || "",
          language: language || "",
          mood: mood || "",
          description:
            description || "",
          lyrics: lyrics || "",
          cover_url,
          audio_url,
          release_date:
            release_date || null,
          parent_song_id:
            finalParentId,
          version_name:
            finalVersionName
        })
        .select()
        .single();

      if (error) {
        if (uploadedCoverPath) {
          await supabase.storage
            .from("covers")
            .remove([
              uploadedCoverPath
            ]);
        }

        if (uploadedAudioPath) {
          await supabase.storage
            .from("audio")
            .remove([
              uploadedAudioPath
            ]);
        }

        throw new Error(
          `Database save failed: ${error.message}`
        );
      }

      res.status(201).json(data);
    } catch (e) {
      console.error(
        "Create song error:",
        e
      );

      res.status(500).json({
        error:
          e.message ||
          "Song creation failed."
      });
    }
  }
);

/* =========================================================
   ADMIN EDIT SONG / VERSION
   ========================================================= */

app.put(
  "/api/songs/:id",
  requireStudioAuth,
  async (req, res) => {
    try {
      const {
        data: oldSong,
        error: findError
      } = await supabase
        .from("songs")
        .select("*")
        .eq("id", req.params.id)
        .single();

      if (
        findError ||
        !oldSong
      ) {
        return res.status(404).json({
          error: "Song not found."
        });
      }

      const {
        title,
        artist,
        genre,
        language,
        mood,
        description,
        lyrics,
        release_date,
        cover_path,
        audio_path,
        version_name
      } = req.body || {};

      if (!title || !artist) {
        return res.status(400).json({
          error:
            "Title and artist are required."
        });
      }

      if (
        !validStoragePath(
          cover_path
        ) ||
        !validStoragePath(
          audio_path
        )
      ) {
        return res.status(400).json({
          error:
            "Invalid storage path."
        });
      }

      let finalCoverUrl =
        oldSong.cover_url || "";

      let finalAudioUrl =
        oldSong.audio_url || "";

      if (cover_path) {
        finalCoverUrl =
          publicUrl(
            "covers",
            cover_path
          );
      }

      if (audio_path) {
        finalAudioUrl =
          publicUrl(
            "audio",
            audio_path
          );
      }

      /*
       * Parent cannot be changed here.
       *
       * Original stays original.
       * Version stays attached to its original.
       *
       * This prevents accidentally breaking
       * the Song Hub structure.
       */

      let finalVersionName =
        oldSong.version_name ||
        "Original Version";

      if (
        oldSong.parent_song_id !== null
      ) {
        finalVersionName =
          String(
            version_name ||
            finalVersionName
          ).trim() ||
          "New Version";
      } else {
        finalVersionName =
          "Original Version";
      }

      const {
        data,
        error
      } = await supabase
        .from("songs")
        .update({
          title,
          artist,
          genre: genre || "",
          language: language || "",
          mood: mood || "",
          description:
            description || "",
          lyrics: lyrics || "",
          cover_url:
            finalCoverUrl,
          audio_url:
            finalAudioUrl,
          release_date:
            release_date || null,
          version_name:
            finalVersionName
        })
        .eq("id", req.params.id)
        .select()
        .single();

      if (error) {
        if (cover_path) {
          await supabase.storage
            .from("covers")
            .remove([
              cover_path
            ]);
        }

        if (audio_path) {
          await supabase.storage
            .from("audio")
            .remove([
              audio_path
            ]);
        }

        throw new Error(
          `Database update failed: ${error.message}`
        );
      }

      /*
       * Remove old media only after
       * database update succeeds.
       */

      if (cover_path) {
        const oldCoverPath =
          storagePath(
            oldSong.cover_url,
            "covers"
          );

        if (
          oldCoverPath &&
          oldCoverPath !== cover_path
        ) {
          await supabase.storage
            .from("covers")
            .remove([
              oldCoverPath
            ]);
        }
      }

      if (audio_path) {
        const oldAudioPath =
          storagePath(
            oldSong.audio_url,
            "audio"
          );

        if (
          oldAudioPath &&
          oldAudioPath !== audio_path
        ) {
          await supabase.storage
            .from("audio")
            .remove([
              oldAudioPath
            ]);
        }
      }

      res.json(data);
    } catch (e) {
      console.error(
        "Edit song error:",
        e
      );

      res.status(500).json({
        error:
          e.message ||
          "Update failed."
      });
    }
  }
);

/* =========================================================
   ADMIN DELETE SONG / VERSION
   ========================================================= */

app.delete(
  "/api/songs/:id",
  requireStudioAuth,
  async (req, res) => {
    try {
      const {
        data: song,
        error: findError
      } = await supabase
        .from("songs")
        .select("*")
        .eq("id", req.params.id)
        .single();

      if (
        findError ||
        !song
      ) {
        return res.status(404).json({
          error: "Song not found."
        });
      }

      /*
       * If this is an ORIGINAL song,
       * check whether it has versions.
       */

      if (
        song.parent_song_id === null
      ) {
        const {
          data: children,
          error: childError
        } = await supabase
          .from("songs")
          .select("id")
          .eq(
            "parent_song_id",
            song.id
          )
          .limit(1);

        if (childError) {
          return res.status(500).json({
            error:
              childError.message
          });
        }

        if (
          children &&
          children.length > 0
        ) {
          return res.status(409).json({
            error:
              "This original song has versions. Delete its versions first."
          });
        }
      }

      const coverPath =
        storagePath(
          song.cover_url,
          "covers"
        );

      const audioPath =
        storagePath(
          song.audio_url,
          "audio"
        );

      const {
        error
      } = await supabase
        .from("songs")
        .delete()
        .eq(
          "id",
          req.params.id
        );

      if (error) {
        return res.status(500).json({
          error: error.message
        });
      }

      if (coverPath) {
        await supabase.storage
          .from("covers")
          .remove([
            coverPath
          ]);
      }

      if (audioPath) {
        await supabase.storage
          .from("audio")
          .remove([
            audioPath
          ]);
      }

      res.json({
        success: true
      });
    } catch (e) {
      console.error(
        "Delete song error:",
        e
      );

      res.status(500).json({
        error:
          e.message ||
          "Delete failed."
      });
    }
  }
);

/* =========================================================
   SEO DYNAMIC SONG HUB PAGE
   ========================================================= */

app.get(
  "/song.html",
  async (req, res, next) => {
    try {
      const id = req.query.id;

      if (!id) {
        return next();
      }

      /*
       * First find requested song.
       */

      const {
        data: requestedSong,
        error: requestedError
      } = await supabase
        .from("songs")
        .select("*")
        .eq("id", id)
        .single();

      if (
        requestedError ||
        !requestedSong
      ) {
        return next();
      }

      /*
       * If someone opens a VERSION URL,
       * resolve it to the ORIGINAL song.
       */

      let originalSong =
        requestedSong;

      if (
        requestedSong.parent_song_id !== null
      ) {
        const {
          data: parentSong,
          error: parentError
        } = await supabase
          .from("songs")
          .select("*")
          .eq(
            "id",
            requestedSong.parent_song_id
          )
          .single();

        if (
          !parentError &&
          parentSong
        ) {
          return res.redirect(
            301,
            `/song.html?id=${parentSong.id}`
          );
        }
      }

      /*
       * Load versions.
       */

      const {
        data: versions,
        error: versionsError
      } = await supabase
        .from("songs")
        .select("*")
        .eq(
          "parent_song_id",
          originalSong.id
        )
        .order("created_at", {
          ascending: true
        });

      if (versionsError) {
        console.error(
          "Song versions error:",
          versionsError
        );
      }

      const allVersions = [
        originalSong,
        ...(versions || [])
      ];

      const title =
        originalSong.title ||
        "New Song";

      const artist =
        originalSong.artist ||
        "Madushanka";

      const language =
        originalSong.language ||
        "Music";

      const genre =
        originalSong.genre ||
        "Music";

      const mood =
        originalSong.mood ||
        "";

      const description =
        originalSong.description ||
        `Listen to ${title} by ${artist} on HEARTBEAT HEAVEN.`;

      const pageUrl =
        `https://heartbeat-heaven.onrender.com/song.html?id=${originalSong.id}`;

      const coverUrl =
        originalSong.cover_url ||
        "";

      const escapeHtml =
        (value) =>
          String(value || "")
            .replace(
              /&/g,
              "&amp;"
            )
            .replace(
              /</g,
              "&lt;"
            )
            .replace(
              />/g,
              "&gt;"
            )
            .replace(
              /"/g,
              "&quot;"
            )
            .replace(
              /'/g,
              "&#039;"
            );

      const seoDescription =
        `${title} by ${artist}. Listen to the original song and its versions on HEARTBEAT HEAVEN. ${description}`;

      /*
       * Server-rendered version list
       * helps search engines understand
       * the Song Hub without JavaScript.
       */

      const versionHtml =
        allVersions
          .map(
            (version, index) => `
              <article class="version-item">
                ${
                  version.cover_url
                    ? `
                    <img
                      src="${escapeHtml(
                        version.cover_url
                      )}"
                      alt="${escapeHtml(
                        version.version_name ||
                          "Song version"
                      )} cover"
                    >
                    `
                    : ""
                }

                <div>
                  <p class="eyebrow">
                    ${
                      index === 0
                        ? "ORIGINAL"
                        : "VERSION"
                    }
                  </p>

                  <h2>
                    ${escapeHtml(
                      version.version_name ||
                        "Original Version"
                    )}
                  </h2>

                  <p>
                    ${escapeHtml(
                      version.title ||
                        title
                    )}
                    — ${escapeHtml(
                      version.artist ||
                        artist
                    )}
                  </p>

                  ${
                    version.audio_url
                      ? `
                      <audio
                        controls
                        preload="metadata"
                        src="${escapeHtml(
                          version.audio_url
                        )}"
                        style="width:100%;margin-top:10px"
                      ></audio>
                      `
                      : ""
                  }
                </div>
              </article>
            `
          )
          .join("");

      const html =
        `<!doctype html>
<html lang="en">
<head>

<meta charset="utf-8">

<meta
  name="viewport"
  content="width=device-width,initial-scale=1"
>

<meta
  name="theme-color"
  content="#090909"
>

<meta
  name="robots"
  content="index, follow"
>

<meta
  name="description"
  content="${escapeHtml(
    seoDescription.slice(0, 300)
  )}"
>

<meta
  name="author"
  content="${escapeHtml(
    artist
  )}"
>

<link
  rel="canonical"
  href="${escapeHtml(
    pageUrl
  )}"
>

<title>
${escapeHtml(
  title
)}
 | ${escapeHtml(
    language
  )} ${escapeHtml(
    genre
  )} Song — ${escapeHtml(
    artist
  )}
</title>

<meta
  property="og:type"
  content="music.song"
>

<meta
  property="og:title"
  content="${escapeHtml(
    title
  )} | HEARTBEAT HEAVEN"
>

<meta
  property="og:description"
  content="${escapeHtml(
    seoDescription.slice(0, 300)
  )}"
>

<meta
  property="og:url"
  content="${escapeHtml(
    pageUrl
  )}"
>

<meta
  property="og:site_name"
  content="HEARTBEAT HEAVEN"
>

${
  coverUrl
    ? `
<meta
  property="og:image"
  content="${escapeHtml(
    coverUrl
  )}"
>
`
    : ""
}

<meta
  name="twitter:card"
  content="summary_large_image"
>

<meta
  name="twitter:title"
  content="${escapeHtml(
    title
  )} | HEARTBEAT HEAVEN"
>

<meta
  name="twitter:description"
  content="${escapeHtml(
    seoDescription.slice(0, 300)
  )}"
>

${
  coverUrl
    ? `
<meta
  name="twitter:image"
  content="${escapeHtml(
    coverUrl
  )}"
>
`
    : ""
}

<script type="application/ld+json">
${JSON.stringify({
  "@context":
    "https://schema.org",

  "@type":
    "MusicRecording",

  name: title,

  url: pageUrl,

  description:
    seoDescription,

  inLanguage:
    language,

  genre:
    genre,

  byArtist: {
    "@type":
      "Person",
    name:
      artist
  },

  publisher: {
    "@type":
      "Organization",
    name:
      "HEARTBEAT HEAVEN",
    url:
      "https://heartbeat-heaven.onrender.com/"
  },

  ...(originalSong.release_date
    ? {
        datePublished:
          originalSong.release_date
      }
    : {}),

  ...(coverUrl
    ? {
        image:
          coverUrl
      }
    : {}),

  ...(originalSong.audio_url
    ? {
        audio: {
          "@type":
            "AudioObject",
          contentUrl:
            originalSong.audio_url
        }
      }
    : {})
})}
</script>

<link
  rel="stylesheet"
  href="/styles.css"
>

</head>

<body>

<header class="topbar">

<a
  class="brand"
  href="/"
>

<span>♥</span>

<div>

<strong>
HEARTBEAT HEAVEN
</strong>

<small>
Original Music by Madushanka
</small>

</div>

</a>

<nav>

<a href="/">
Home
</a>

<a href="/songs.html">
Songs
</a>

</nav>

</header>

<main
  class="song-page"
  id="songPage"
  itemscope
  itemtype="https://schema.org/MusicRecording"
>

<section class="song-hero">

${
  coverUrl
    ? `
<img
  class="song-cover"
  src="${escapeHtml(
    coverUrl
  )}"
  alt="${escapeHtml(
    title
  )} cover"
  itemprop="image"
>
`
    : ""
}

<div>

<p class="eyebrow">
${escapeHtml(
  genre
)} • ${escapeHtml(
  language
)}
</p>

<h1
  class="song-title"
  itemprop="name"
>
${escapeHtml(
  title
)}
</h1>

<p class="meta">
${escapeHtml(
  artist
)}
${
  mood
    ? ` • ${escapeHtml(
        mood
      )}`
    : ""
}
</p>

<p
  class="song-desc"
  itemprop="description"
>
${escapeHtml(
  description
)}
</p>

${
  originalSong.audio_url
    ? `
<audio
  controls
  preload="metadata"
  style="width:100%;margin-top:25px"
  src="${escapeHtml(
    originalSong.audio_url
  )}"
></audio>
`
    : ""
}

</div>

</section>

<section class="song-versions">

<p class="eyebrow">
VERSIONS
</p>

<h2>
Listen to all versions
</h2>

<div class="versions-list">

${versionHtml}

</div>

</section>

<section class="lyrics-wrap">

<p class="eyebrow">
LYRICS
</p>

<div
  class="lyrics"
  itemprop="lyrics"
>
${
  escapeHtml(
    originalSong.lyrics
  ) ||
  "Lyrics will be added soon."
}
</div>

</section>

</main>

<script src="/song.js"></script>

</body>

</html>`;

      res
        .status(200)
        .type("html")
        .send(html);

    } catch (error) {
      console.error(
        "Song SEO page error:",
        error
      );

      next();
    }
  }
);

/* =========================================================
   PUBLIC FILES
   ========================================================= */

app.use(
  "/uploads",
  express.static(
    path.join(
      __dirname,
      "uploads"
    )
  )
);

app.use(
  express.static(
    path.join(
      __dirname,
      "public"
    )
  )
);

/* =========================================================
   PUBLIC SONG HUB API
   IMPORTANT:
   This route MUST come before /api/songs/:id
   ========================================================= */

app.get(
  "/api/songs/:id/hub",
  async (req, res) => {
    try {
      const {
        data: requestedSong,
        error: requestedError
      } = await supabase
        .from("songs")
        .select("*")
        .eq("id", req.params.id)
        .single();

      if (
        requestedError ||
        !requestedSong
      ) {
        return res.status(404).json({
          error:
            "Song not found."
        });
      }

      let original =
        requestedSong;

      /*
       * If requested ID is a version,
       * resolve its original song.
       */

      if (
        requestedSong.parent_song_id !== null
      ) {
        const {
          data: parentSong,
          error: parentError
        } = await supabase
          .from("songs")
          .select("*")
          .eq(
            "id",
            requestedSong.parent_song_id
          )
          .single();

        if (
          parentError ||
          !parentSong
        ) {
          return res.status(404).json({
            error:
              "Original song not found."
          });
        }

        original =
          parentSong;
      }

      const {
        data: versions,
        error: versionsError
      } = await supabase
        .from("songs")
        .select("*")
        .eq(
          "parent_song_id",
          original.id
        )
        .order("created_at", {
          ascending: true
        });

      if (versionsError) {
        return res.status(500).json({
          error:
            versionsError.message
        });
      }

      res.json({
        original,
        versions:
          versions || []
      });

    } catch (error) {
      console.error(
        "Song Hub API error:",
        error
      );

      res.status(500).json({
        error:
          error.message ||
          "Could not load Song Hub."
      });
    }
  }
);

/* =========================================================
   GOOGLE SEO — SITEMAP
   ONLY ORIGINAL SONG HUB URLs
   ========================================================= */

app.get(
  "/sitemap.xml",
  async (req, res) => {
    try {
      const {
        data: songs,
        error
      } = await supabase
        .from("songs")
        .select(
          "id, release_date, created_at, parent_song_id"
        )
        .is(
          "parent_song_id",
          null
        );

      if (error) {
        console.error(
          "Sitemap error:",
          error
        );

        return res
          .status(500)
          .type("text/plain")
          .send(
            "Sitemap error"
          );
      }

      const baseUrl =
        "https://heartbeat-heaven.onrender.com";

      const now =
        new Date().toISOString();

      const urls = [
        {
          loc:
            `${baseUrl}/`,
          lastmod:
            now
        },

        {
          loc:
            `${baseUrl}/songs.html`,
          lastmod:
            now
        }
      ];

      (songs || []).forEach(
        (song) => {
          urls.push({
            loc:
              `${baseUrl}/song.html?id=${song.id}`,

            lastmod:
              song.release_date
                ? new Date(
                    song.release_date
                  ).toISOString()
                : new Date(
                    song.created_at
                  ).toISOString()
          });
        }
      );

      const xml =
        `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
${urls
  .map(
    (url) =>
      `  <url>
    <loc>${url.loc}</loc>
    <lastmod>${url.lastmod}</lastmod>
  </url>`
  )
  .join("\n")}
</urlset>`;

      res
        .status(200)
        .type("application/xml")
        .send(xml);

    } catch (error) {
      console.error(
        "Sitemap generation failed:",
        error
      );

      res
        .status(500)
        .type("text/plain")
        .send(
          "Sitemap generation failed"
        );
    }
  }
);

/* =========================================================
   HEALTH
   ========================================================= */

app.get(
  "/api/health",
  async (req, res) => {
    const {
      error
    } = await supabase
      .from("songs")
      .select("id")
      .limit(1);

    if (error) {
      return res.status(500).json({
        ok: false,
        error:
          error.message
      });
    }

    res.json({
      ok: true
    });
  }
);

/* =========================================================
   PUBLIC SONGS
   IMPORTANT:
   Homepage receives ORIGINAL SONGS ONLY
   ========================================================= */

app.get(
  "/api/songs",
  async (req, res) => {
    const {
      data,
      error
    } = await supabase
      .from("songs")
      .select("*")
      .is(
        "parent_song_id",
        null
      )
      .order("release_date", {
        ascending: false,
        nullsFirst: false
      })
      .order("created_at", {
        ascending: false
      });

    if (error) {
      return res.status(500).json({
        error:
          error.message
      });
    }

    res.json(
      data || []
    );
  }
);

/* =========================================================
   PUBLIC SINGLE SONG API
   Kept for compatibility
   ========================================================= */

app.get(
  "/api/songs/:id",
  async (req, res) => {
    const {
      data,
      error
    } = await supabase
      .from("songs")
      .select("*")
      .eq(
        "id",
        req.params.id
      )
      .single();

    if (error) {
      return res.status(404).json({
        error:
          "Song not found"
      });
    }

    res.json(data);
  }
);

/* =========================================================
   FALLBACK
   ========================================================= */

app.get(
  "/{*splat}",
  (req, res) => {
    res.sendFile(
      path.join(
        __dirname,
        "public",
        "index.html"
      )
    );
  }
);

/* =========================================================
   SERVER
   ========================================================= */

app.listen(
  PORT,
  "0.0.0.0",
  () => {
    console.log(
      `Heartbeat Heaven running on port ${PORT}`
    );
  }
);
