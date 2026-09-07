const express = require("express");
const multer = require("multer");
const path = require("path");
const crypto = require("crypto");
const { createClient } = require("@supabase/supabase-js");
const crypto = require("crypto");
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
   ADMIN SECURITY
========================================================= */

const ADMIN_USER = process.env.ADMIN_USER;
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD;

function checkAdminAuth(req, res, next) {

  if (!ADMIN_USER || !ADMIN_PASSWORD) {
    return res.status(503).send(
      "Studio is not configured. Please set ADMIN_USER and ADMIN_PASSWORD."
    );
  }

  const header = req.headers.authorization || "";

  if (!header.startsWith("Basic ")) {
    res.set("WWW-Authenticate", 'Basic realm="Heartbeat Heaven Studio"');
    return res.status(401).send("Authentication required.");
  }

  let decoded;

  try {
    decoded = Buffer.from(
      header.slice(6),
      "base64"
    ).toString("utf8");
  } catch {
    res.set("WWW-Authenticate", 'Basic realm="Heartbeat Heaven Studio"');
    return res.status(401).send("Invalid authentication.");
  }

  const separator = decoded.indexOf(":");

  if (separator < 0) {
    res.set("WWW-Authenticate", 'Basic realm="Heartbeat Heaven Studio"');
    return res.status(401).send("Invalid authentication.");
  }

  const username = decoded.slice(0, separator);
  const password = decoded.slice(separator + 1);

  const userOk =
    username.length === ADMIN_USER.length &&
    crypto.timingSafeEqual(
      Buffer.from(username),
      Buffer.from(ADMIN_USER)
    );

  const passwordOk =
    password.length === ADMIN_PASSWORD.length &&
    crypto.timingSafeEqual(
      Buffer.from(password),
      Buffer.from(ADMIN_PASSWORD)
    );

  if (!userOk || !passwordOk) {
    res.set("WWW-Authenticate", 'Basic realm="Heartbeat Heaven Studio"');
    return res.status(401).send("Invalid username or password.");
  }

  next();
}


/* =========================================================
   BODY / STATIC
========================================================= */

app.use(express.json({ limit: "2mb" }));
app.use(express.urlencoded({ extended: true }));


/*
  Protect Studio page + Studio JavaScript
*/
app.get("/admin.html", checkAdminAuth, (req, res) => {
  res.sendFile(path.join(__dirname, "public", "admin.html"));
});

app.get("/admin.js", checkAdminAuth, (req, res) => {
  res.sendFile(path.join(__dirname, "public", "admin.js"));
});


/*
  Protect upload and delete API.
  Normal GET /api/songs remains public.
*/
app.post("/api/songs", checkAdminAuth);

app.delete("/api/songs/:id", checkAdminAuth);


/*
  Public files
*/
app.use("/uploads", express.static(path.join(__dirname, "uploads")));
app.use(express.static(path.join(__dirname, "public")));


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

  return `${process.env.SUPABASE_URL}/storage/v1/object/public/${bucket}/${encodeURIComponent(file).replace(/%2F/g, "/")}`;
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


/* =========================================================
   HEALTH
========================================================= */

app.get("/api/health", async (req, res) => {

  const { error } =
    await supabase
      .from("songs")
      .select("id")
      .limit(1);

  if (error) {
    return res.status(500).json({
      ok: false,
      error: error.message
    });
  }

  res.json({
    ok: true
  });
});


/* =========================================================
   PUBLIC SONGS
========================================================= */

app.get("/api/songs", async (req, res) => {

  const { data, error } =
    await supabase
      .from("songs")
      .select("*")
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
});


app.get("/api/songs/:id", async (req, res) => {

  const { data, error } =
    await supabase
      .from("songs")
      .select("*")
      .eq("id", req.params.id)
      .single();

  if (error) {
    return res.status(404).json({
      error: "Song not found"
    });
  }

  res.json(data);
});


/* =========================================================
   ADMIN — UPLOAD SONG
========================================================= */

app.post(
  "/api/songs",
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
        return res.status(400).json({
          error: "Title and artist are required."
        });
      }

      const cover =
        req.files?.cover?.[0];

      const audio =
        req.files?.audio?.[0];

      let cover_url =
        req.body.cover_url || "";

      let audio_url =
        req.body.audio_url || "";

      let coverPath = null;
      let audioPath = null;


      /* COVER */

      if (cover) {

        coverPath =
          safeFileName(
            cover.originalname
          );

        const { error } =
          await supabase.storage
            .from("covers")
            .upload(
              coverPath,
              cover.buffer,
              {
                contentType:
                  cover.mimetype,
                upsert: false
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


      /* AUDIO */

      if (audio) {

        audioPath =
          safeFileName(
            audio.originalname
          );

        const { error } =
          await supabase.storage
            .from("audio")
            .upload(
              audioPath,
              audio.buffer,
              {
                contentType:
                  audio.mimetype ||
                  "audio/mpeg",
                upsert: false
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


      /* DATABASE */

      const { data, error } =
        await supabase
          .from("songs")
          .insert({
            title,
            artist,
            genre: genre || "",
            language: language || "",
            mood: mood || "",
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


      res.status(201).json(data);

    } catch (e) {

      console.error(e);

      res.status(500).json({
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
  checkAdminAuth,
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

      const { data: oldSong, error: findError } =
        await supabase
          .from("songs")
          .select("*")
          .eq("id", req.params.id)
          .single();

      if (findError || !oldSong) {
        return res.status(404).json({
          error: "Song not found"
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
        return res.status(400).json({
          error: "Title and artist are required."
        });
      }

      const newCover = req.files?.cover?.[0];
      const newAudio = req.files?.audio?.[0];

      let cover_url = oldSong.cover_url || "";
      let audio_url = oldSong.audio_url || "";

      let newCoverPath = null;
      let newAudioPath = null;


      /* NEW COVER */

      if (newCover) {

        newCoverPath =
          safeFileName(newCover.originalname);

        const { error } =
          await supabase.storage
            .from("covers")
            .upload(
              newCoverPath,
              newCover.buffer,
              {
                contentType: newCover.mimetype,
                upsert: false
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


      /* NEW AUDIO */

      if (newAudio) {

        newAudioPath =
          safeFileName(newAudio.originalname);

        const { error } =
          await supabase.storage
            .from("audio")
            .upload(
              newAudioPath,
              newAudio.buffer,
              {
                contentType:
                  newAudio.mimetype ||
                  "audio/mpeg",
                upsert: false
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


      /* UPDATE DATABASE */

      const { data, error } =
        await supabase
          .from("songs")
          .update({
            title,
            artist,
            genre: genre || "",
            language: language || "",
            mood: mood || "",
            description: description || "",
            lyrics: lyrics || "",
            cover_url,
            audio_url,
            release_date:
              release_date || null
          })
          .eq("id", req.params.id)
          .select()
          .single();


      /* DATABASE FAILED */

      if (error) {

        if (newCoverPath) {
          await supabase
            .storage
            .from("covers")
            .remove([newCoverPath]);
        }

        if (newAudioPath) {
          await supabase
            .storage
            .from("audio")
            .remove([newAudioPath]);
        }

        throw new Error(
          `Database update failed: ${error.message}`
        );
      }


      /* REMOVE OLD FILES ONLY AFTER DATABASE SUCCESS */

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
            .remove([oldCoverPath]);
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
            .remove([oldAudioPath]);
        }
      }


      res.json(data);

    } catch (e) {

      console.error(e);

      res.status(500).json({
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
  async (req, res) => {

    try {

      const {
        data: song,
        error: findError
      } =
        await supabase
          .from("songs")
          .select("*")
          .eq("id", req.params.id)
          .single();


      if (findError || !song) {
        return res.status(404).json({
          error: "Song not found"
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


      const { error } =
        await supabase
          .from("songs")
          .delete()
          .eq("id", req.params.id);


      if (error) {
        return res.status(500).json({
          error: error.message
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

      res.status(500).json({
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
  (req, res) =>
    res.sendFile(
      path.join(
        __dirname,
        "public",
        "index.html"
      )
    )
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
