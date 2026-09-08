const express = require("express");
const multer = require("multer");
const path = require("path");
const crypto = require("crypto");
const { createClient } = require("@supabase/supabase-js");

const app = express();
const PORT = process.env.PORT || 10000;

const supabase = createClient(
  process.env.SUPABASE_URL,
  process.env.SUPABASE_SERVICE_ROLE_KEY,
  {
    auth: {
      persistSession: false,
      autoRefreshToken: false
    }
  }
);

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

// If STUDIO_SESSION_SECRET is not set on Render,
// ADMIN_PASSWORD is also included so changing the
// admin password automatically invalidates old sessions.
const SESSION_SECRET =
  `${process.env.STUDIO_SESSION_SECRET || "heartbeat-heaven-session"}:${ADMIN_PASSWORD || ""}:heartbeat-heaven-studio`;


/* =========================================================
   SAFE STRING COMPARISON
========================================================= */

function safeCompare(a, b) {

  const aBuffer = Buffer.from(
    String(a),
    "utf8"
  );

  const bBuffer = Buffer.from(
    String(b),
    "utf8"
  );

  if (aBuffer.length !== bBuffer.length) {
    return false;
  }

  return crypto.timingSafeEqual(
    aBuffer,
    bBuffer
  );
}


/* =========================================================
   COOKIE HELPER
========================================================= */

function getCookie(req, name) {

  const cookieHeader =
    req.headers.cookie || "";

  for (const part of cookieHeader.split(";")) {

    const i =
      part.indexOf("=");

    if (i === -1) {
      continue;
    }

    const key =
      part
        .slice(0, i)
        .trim();

    const value =
      part
        .slice(i + 1)
        .trim();

    if (key === name) {
      return value;
    }

  }

  return null;
}


/* =========================================================
   CREATE STUDIO SESSION
========================================================= */

function createStudioSession() {

  const payload = {
    user: ADMIN_USER,
    exp: Date.now() + SESSION_TTL
  };

  const data =
    Buffer
      .from(
        JSON.stringify(payload),
        "utf8"
      )
      .toString("base64url");


  const signature =
    crypto
      .createHmac(
        "sha256",
        SESSION_SECRET
      )
      .update(data)
      .digest("base64url");


  return `${data}.${signature}`;
}


/* =========================================================
   VERIFY STUDIO SESSION
========================================================= */

function verifyStudioSession(token) {

  if (!token) {
    return false;
  }


  const parts =
    token.split(".");


  if (parts.length !== 2) {
    return false;
  }


  const [
    data,
    signature
  ] = parts;


  const expected =
    crypto
      .createHmac(
        "sha256",
        SESSION_SECRET
      )
      .update(data)
      .digest("base64url");


  const a =
    Buffer.from(
      signature,
      "utf8"
    );

  const b =
    Buffer.from(
      expected,
      "utf8"
    );


  if (a.length !== b.length) {
    return false;
  }


  if (
    !crypto.timingSafeEqual(
      a,
      b
    )
  ) {
    return false;
  }


  try {

    const payload =
      JSON.parse(
        Buffer
          .from(
            data,
            "base64url"
          )
          .toString("utf8")
      );


    if (
      !payload.user ||
      !payload.exp
    ) {
      return false;
    }


    if (
      !ADMIN_USER ||
      !safeCompare(
        payload.user,
        ADMIN_USER
      )
    ) {
      return false;
    }


    if (
      Date.now() >= payload.exp
    ) {
      return false;
    }


    return true;

  } catch {

    return false;

  }

}


/* =========================================================
   SET SESSION COOKIE
========================================================= */

function setStudioCookie(
  res,
  token
) {

  res.set(
    "Set-Cookie",
    `${SESSION_COOKIE}=${token}; Max-Age=${Math.floor(
      SESSION_TTL / 1000
    )}; Path=/; HttpOnly; Secure; SameSite=Lax`
  );

}


/* =========================================================
   CLEAR SESSION COOKIE
========================================================= */

function clearStudioCookie(res) {

  res.set(
    "Set-Cookie",
    `${SESSION_COOKIE}=; Max-Age=0; Path=/; HttpOnly; Secure; SameSite=Lax`
  );

}


/* =========================================================
   API AUTH MIDDLEWARE
========================================================= */

function requireStudioAuth(
  req,
  res,
  next
) {

  if (
    !ADMIN_USER ||
    !ADMIN_PASSWORD
  ) {

    return res
      .status(503)
      .json({
        error:
          "Studio is not configured. Please set ADMIN_USER and ADMIN_PASSWORD."
      });

  }


  const token =
    getCookie(
      req,
      SESSION_COOKIE
    );


  if (
    !verifyStudioSession(token)
  ) {

    return res
      .status(401)
      .json({
        error:
          "Studio login required."
      });

  }


  next();

}


/* =========================================================
   STUDIO PAGE AUTH
========================================================= */

function requireStudioPage(
  req,
  res,
  next
) {

  if (
    !ADMIN_USER ||
    !ADMIN_PASSWORD
  ) {

    return res
      .status(503)
      .send(
        "Studio is not configured. Please set ADMIN_USER and ADMIN_PASSWORD."
      );

  }


  const token =
    getCookie(
      req,
      SESSION_COOKIE
    );


  if (
    !verifyStudioSession(token)
  ) {

    return res.redirect(
      "/studio-login.html"
    );

  }


  res.set(
    "Cache-Control",
    "no-store"
  );


  next();

}


/* =========================================================
   BODY PARSERS
========================================================= */

app.use(
  express.json({
    limit: "2mb"
  })
);


app.use(
  express.urlencoded({
    extended: true
  })
);


/* =========================================================
   STUDIO LOGIN PAGE
========================================================= */

app.get(
  "/studio-login.html",
  (req, res) => {

    if (
      ADMIN_USER &&
      ADMIN_PASSWORD &&
      verifyStudioSession(
        getCookie(
          req,
          SESSION_COOKIE
        )
      )
    ) {

      return res.redirect(
        "/admin.html"
      );

    }


    res.set(
      "Cache-Control",
      "no-store"
    );


    res.sendFile(
      path.join(
        __dirname,
        "public",
        "studio-login.html"
      )
    );

  }
);


/* =========================================================
   STUDIO LOGIN API
========================================================= */

app.post(
  "/api/studio/login",
  (req, res) => {

    if (
      !ADMIN_USER ||
      !ADMIN_PASSWORD
    ) {

      return res
        .status(503)
        .json({
          success: false,
          error:
            "Studio is not configured. Please set ADMIN_USER and ADMIN_PASSWORD."
        });

    }


    const username =
      String(
        req.body?.username || ""
      );

    const password =
      String(
        req.body?.password || ""
      );


    const userOk =
      safeCompare(
        username,
        ADMIN_USER
      );


    const passwordOk =
      safeCompare(
        password,
        ADMIN_PASSWORD
      );


    if (
      !userOk ||
      !passwordOk
    ) {

      return res
        .status(401)
        .json({
          success: false,
          error:
            "Invalid username or password."
        });

    }


    const token =
      createStudioSession();


    setStudioCookie(
      res,
      token
    );


    res.set(
      "Cache-Control",
      "no-store"
    );


    res.json({
      success: true
    });

  }
);


/* =========================================================
   STUDIO SESSION CHECK
========================================================= */

app.get(
  "/api/studio/me",
  (req, res) => {

    if (
      !ADMIN_USER ||
      !ADMIN_PASSWORD
    ) {

      return res
        .status(503)
        .json({
          authenticated: false,
          error:
            "Studio is not configured."
        });

    }


    const authenticated =
      verifyStudioSession(
        getCookie(
          req,
          SESSION_COOKIE
        )
      );


    if (!authenticated) {

      return res
        .status(401)
        .json({
          authenticated: false
        });

    }


    res.set(
      "Cache-Control",
      "no-store"
    );


    res.json({
      authenticated: true
    });

  }
);


/* =========================================================
   STUDIO LOGOUT
========================================================= */

app.post(
  "/api/studio/logout",
  (req, res) => {

    clearStudioCookie(
      res
    );


    res.set(
      "Cache-Control",
      "no-store"
    );


    res.json({
      success: true
    });

  }
);


/* =========================================================
   PROTECT STUDIO
========================================================= */

app.get(
  "/admin.html",
  requireStudioPage,
  (req, res) => {

    res.sendFile(
      path.join(
        __dirname,
        "public",
        "admin.html"
      )
    );

  }
);


app.get(
  "/admin.js",
  requireStudioPage,
  (req, res) => {

    res.sendFile(
      path.join(
        __dirname,
        "public",
        "admin.js"
      )
    );

  }
);


/* =========================================================
   SEO — DYNAMIC SONG PAGE
========================================================= */

app.get(
  "/song.html",
  async (req, res, next) => {

    try {

      const id =
        req.query.id;


      if (!id) {
        return next();
      }


      const {
        data: song,
        error
      } = await supabase
        .from("songs")
        .select("*")
        .eq("id", id)
        .single();


      if (
        error ||
        !song
      ) {
        return next();
      }


      const title =
        song.title ||
        "New Song";

      const artist =
        song.artist ||
        "Madushanka";

      const language =
        song.language ||
        "Music";

      const genre =
        song.genre ||
        "Music";

      const mood =
        song.mood ||
        "";

      const description =
        song.description ||
        `Listen to ${title} by ${artist} on HEARTBEAT HEAVEN.`;


      const pageUrl =
        `https://heartbeat-heaven.onrender.com/song.html?id=${song.id}`;


      const coverUrl =
        song.cover_url || "";


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
        `${title} by ${artist}. ` +
        `Listen to this original ${language} ${genre} song on HEARTBEAT HEAVEN. ` +
        description;


      const html =
        `<!doctype html>
<html lang="en">

<head>

<meta charset="utf-8">

<meta name="viewport"
      content="width=device-width,initial-scale=1">

<meta name="theme-color"
      content="#090909">

<meta name="robots"
      content="index, follow">

<meta name="description"
      content="${escapeHtml(
        seoDescription.slice(0, 300)
      )}">

<meta name="author"
      content="${escapeHtml(
        artist
      )}">

<link rel="canonical"
      href="${escapeHtml(
        pageUrl
      )}">

<title>
${escapeHtml(title)} | ${escapeHtml(language)} ${escapeHtml(genre)} Song — ${escapeHtml(artist)}
</title>


<meta property="og:type"
      content="music.song">

<meta property="og:title"
      content="${escapeHtml(
        title
      )} | HEARTBEAT HEAVEN">

<meta property="og:description"
      content="${escapeHtml(
        seoDescription.slice(0, 300)
      )}">

<meta property="og:url"
      content="${escapeHtml(
        pageUrl
      )}">

<meta property="og:site_name"
      content="HEARTBEAT HEAVEN">


${coverUrl ? `
<meta property="og:image"
      content="${escapeHtml(
        coverUrl
      )}">
` : ""}


<meta name="twitter:card"
      content="summary_large_image">

<meta name="twitter:title"
      content="${escapeHtml(
        title
      )} | HEARTBEAT HEAVEN">

<meta name="twitter:description"
      content="${escapeHtml(
        seoDescription.slice(0, 300)
      )}">


${coverUrl ? `
<meta name="twitter:image"
      content="${escapeHtml(
        coverUrl
      )}">
` : ""}


<script type="application/ld+json">

${JSON.stringify({
  "@context": "https://schema.org",
  "@type": "MusicRecording",
  "name": title,
  "url": pageUrl,
  "description": seoDescription,
  "inLanguage": language,
  "genre": genre,

  "byArtist": {
    "@type": "Person",
    "name": artist
  },

  "publisher": {
    "@type": "Organization",
    "name": "HEARTBEAT HEAVEN",
    "url": "https://heartbeat-heaven.onrender.com/"
  },

  ...(song.release_date
    ? {
        "datePublished":
          song.release_date
      }
    : {}),

  ...(coverUrl
    ? {
        "image":
          coverUrl
      }
    : {}),

  ...(song.audio_url
    ? {
        "audio": {
          "@type":
            "AudioObject",

          "contentUrl":
            song.audio_url
        }
      }
    : {})
})}

</script>


<link rel="stylesheet"
      href="/styles.css">

</head>


<body>


<header class="topbar">

<a class="brand"
   href="/">

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
  itemtype="https://schema.org/MusicRecording">


<section class="song-hero">


${coverUrl ? `
<img
  class="song-cover"
  src="${escapeHtml(
    coverUrl
  )}"
  alt="${escapeHtml(
    title
  )} cover"
  itemprop="image">
` : ""}


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
  itemprop="name">

${escapeHtml(
  title
)}

</h1>


<p class="meta">

${escapeHtml(
  artist
)}

${mood
  ? ` • ${escapeHtml(mood)}`
  : ""}

</p>


<p
  class="song-desc"
  itemprop="description">

${escapeHtml(
  description
)}

</p>


${song.audio_url ? `
<audio
  controls
  preload="metadata"
  style="width:100%;margin-top:25px"
  src="${escapeHtml(
    song.audio_url
  )}">
</audio>
` : ""}


</div>

</section>


<section class="lyrics-wrap">


<p class="eyebrow">
LYRICS
</p>


<div
  class="lyrics"
  itemprop="lyrics">

${escapeHtml(
  song.lyrics
) ||
  "Lyrics will be added soon."}

</div>


</section>


</main>


<script src="/song.js">
</script>


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
   HELPERS
========================================================= */

function safeFileName(
  name = "file"
) {

  const ext =
    path
      .extname(name)
      .toLowerCase();


  const base =
    path
      .basename(
        name,
        ext
      )
      .replace(
        /[^a-zA-Z0-9-_]/g,
        "-"
      )
      .replace(
        /-+/g,
        "-"
      )
      .slice(
        0,
        80
      ) ||
    "file";


  return `${Date.now()}-${base}${ext}`;

}


function publicUrl(
  bucket,
  file
) {

  return `${process.env.SUPABASE_URL}/storage/v1/object/public/${bucket}/${encodeURIComponent(file).replace(/%2F/g, "/")}`;

}


function storagePath(
  url,
  bucket
) {

  if (!url) {
    return null;
  }


  const marker =
    `/storage/v1/object/public/${bucket}/`;


  const i =
    url.indexOf(
      marker
    );


  if (i < 0) {
    return null;
  }


  return decodeURIComponent(
    url.slice(
      i + marker.length
    )
  );

}


/* =========================================================
   GOOGLE SEO — SITEMAP
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
          "id, release_date, created_at"
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


      const urls = [

        {
          loc:
            `${baseUrl}/`,

          lastmod:
            new Date()
              .toISOString()
        },

        {
          loc:
            `${baseUrl}/songs.html`,

          lastmod:
            new Date()
              .toISOString()
        }

      ];


      (songs || [])
        .forEach(
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
<urlset
  xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">

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

      return res
        .status(500)
        .json({
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
      .order(
        "release_date",
        {
          ascending: false,
          nullsFirst: false
        }
      )
      .order(
        "created_at",
        {
          ascending: false
        }
      );


    if (error) {

      return res
        .status(500)
        .json({
          error:
            error.message
        });

    }


    res.json(
      data || []
    );

  }
);


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

      return res
        .status(404)
        .json({
          error:
            "Song not found"
        });

    }


    res.json(
      data
    );

  }
);


/* =========================================================
   ADMIN — UPLOAD SONG
========================================================= */

app.post(
  "/api/songs",
  requireStudioAuth,

  upload.fields([

    {
      name: "cover",
      maxCount: 1
    },

    {
      name: "audio",
      maxCount: 1
    }

  ]),

  async (req, res) => {

    try {

      const {
        title,
        artist,
        genre,
        language,
        mood,
        description,
        lyrics,
        release_date
      } = req.body;


      if (!title || !artist) {

        return res
          .status(400)
          .json({
            error:
              "Title and artist are required."
          });

      }


      const cover =
        req.files?.cover?.[0];


      const audio =
        req.files?.audio?.[0];


      let cover_url =
        req.body.cover_url ||
        "";


      let audio_url =
        req.body.audio_url ||
        "";


      let coverPath =
        null;


      let audioPath =
        null;


      if (cover) {

        coverPath =
          safeFileName(
            cover.originalname
          );


        const {
          error
        } = await supabase.storage
          .from("covers")
          .upload(
            coverPath,
            cover.buffer,
            {
              contentType:
                cover.mimetype,

              upsert:
                false
            }
          );


        if (error) {

          throw new Error(
            `Cover upload failed: ${error.message}`
          );

        }


        cover_url =
          publicUrl(
            "covers",
            coverPath
          );

      }


      if (audio) {

        audioPath =
          safeFileName(
            audio.originalname
          );


        const {
          error
        } = await supabase.storage
          .from("audio")
          .upload(
            audioPath,
            audio.buffer,
            {
              contentType:
                audio.mimetype ||
                "audio/mpeg",

              upsert:
                false
            }
          );


        if (error) {

          throw new Error(
            `Audio upload failed: ${error.message}`
          );

        }


        audio_url =
          publicUrl(
            "audio",
            audioPath
          );

      }


      const {
        data,
        error
      } = await supabase
        .from("songs")
        .insert({

          title,

          artist,

          genre:
            genre || "",

          language:
            language || "",

          mood:
            mood || "",

          description:
            description || "",

          lyrics:
            lyrics || "",

          cover_url,

          audio_url,

          release_date:
            release_date || null

        })
        .select()
        .single();


      if (error) {

        if (coverPath) {

          await supabase
            .storage
            .from("covers")
            .remove([
              coverPath
            ]);

        }


        if (audioPath) {

          await supabase
            .storage
            .from("audio")
            .remove([
              audioPath
            ]);

        }


        throw new Error(
          `Database save failed: ${error.message}`
        );

      }


      res
        .status(201)
        .json(data);


    } catch (e) {

      console.error(e);

      res
        .status(500)
        .json({
          error:
            e.message ||
            "Upload failed."
        });

    }

  }
);


/* =========================================================
   ADMIN — EDIT SONG
========================================================= */

app.put(
  "/api/songs/:id",
  requireStudioAuth,

  upload.fields([

    {
      name: "cover",
      maxCount: 1
    },

    {
      name: "audio",
      maxCount: 1
    }

  ]),

  async (req, res) => {

    try {

      const {
        data: oldSong,
        error: findError
      } = await supabase
        .from("songs")
        .select("*")
        .eq(
          "id",
          req.params.id
        )
        .single();


      if (
        findError ||
        !oldSong
      ) {

        return res
          .status(404)
          .json({
            error:
              "Song not found"
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
        release_date
      } = req.body;


      if (!title || !artist) {

        return res
          .status(400)
          .json({
            error:
              "Title and artist are required."
          });

      }


      const newCover =
        req.files?.cover?.[0];


      const newAudio =
        req.files?.audio?.[0];


      let cover_url =
        oldSong.cover_url ||
        "";


      let audio_url =
        oldSong.audio_url ||
        "";


      let newCoverPath =
        null;


      let newAudioPath =
        null;


      if (newCover) {

        newCoverPath =
          safeFileName(
            newCover.originalname
          );


        const {
          error
        } = await supabase.storage
          .from("covers")
          .upload(
            newCoverPath,
            newCover.buffer,
            {
              contentType:
                newCover.mimetype,

              upsert:
                false
            }
          );


        if (error) {

          throw new Error(
            `Cover upload failed: ${error.message}`
          );

        }


        cover_url =
          publicUrl(
            "covers",
            newCoverPath
          );

      }


      if (newAudio) {

        newAudioPath =
          safeFileName(
            newAudio.originalname
          );


        const {
          error
        } = await supabase.storage
          .from("audio")
          .upload(
            newAudioPath,
            newAudio.buffer,
            {
              contentType:
                newAudio.mimetype ||
                "audio/mpeg",

              upsert:
                false
            }
          );


        if (error) {

          throw new Error(
            `Audio upload failed: ${error.message}`
          );

        }


        audio_url =
          publicUrl(
            "audio",
            newAudioPath
          );

      }


      const {
        data,
        error
      } = await supabase
        .from("songs")
        .update({

          title,

          artist,

          genre:
            genre || "",

          language:
            language || "",

          mood:
            mood || "",

          description:
            description || "",

          lyrics:
            lyrics || "",

          cover_url,

          audio_url,

          release_date:
            release_date || null

        })
        .eq(
          "id",
          req.params.id
        )
        .select()
        .single();


      if (error) {

        if (newCoverPath) {

          await supabase
            .storage
            .from("covers")
            .remove([
              newCoverPath
            ]);

        }


        if (newAudioPath) {

          await supabase
            .storage
            .from("audio")
            .remove([
              newAudioPath
            ]);

        }


        throw new Error(
          `Database update failed: ${error.message}`
        );

      }


      if (newCoverPath) {

        const oldCoverPath =
          storagePath(
            oldSong.cover_url,
            "covers"
          );


        if (oldCoverPath) {

          await supabase
            .storage
            .from("covers")
            .remove([
              oldCoverPath
            ]);

        }

      }


      if (newAudioPath) {

        const oldAudioPath =
          storagePath(
            oldSong.audio_url,
            "audio"
          );


        if (oldAudioPath) {

          await supabase
            .storage
            .from("audio")
            .remove([
              oldAudioPath
            ]);

        }

      }


      res.json(
        data
      );


    } catch (e) {

      console.error(e);

      res
        .status(500)
        .json({
          error:
            e.message ||
            "Update failed."
        });

    }

  }
);


/* =========================================================
   ADMIN — DELETE SONG
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
        .eq(
          "id",
          req.params.id
        )
        .single();


      if (
        findError ||
        !song
      ) {

        return res
          .status(404)
          .json({
            error:
              "Song not found"
          });

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

        return res
          .status(500)
          .json({
            error:
              error.message
          });

      }


      if (coverPath) {

        await supabase
          .storage
          .from("covers")
          .remove([
            coverPath
          ]);

      }


      if (audioPath) {

        await supabase
          .storage
          .from("audio")
          .remove([
            audioPath
          ]);

      }


      res.json({
        success: true
      });


    } catch (e) {

      console.error(e);

      res
        .status(500)
        .json({
          error:
            e.message ||
            "Delete failed."
        });

    }

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
