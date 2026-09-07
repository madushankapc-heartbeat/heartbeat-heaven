const express = require("express");
const multer = require("multer");
const path = require("path");
const { createClient } = require("@supabase/supabase-js");

const app = express();
const PORT = process.env.PORT || 10000;
const supabase = createClient(process.env.SUPABASE_URL, process.env.SUPABASE_SERVICE_ROLE_KEY, {
  auth: { persistSession: false, autoRefreshToken: false }
});

const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: 50 * 1024 * 1024 }
});

app.use(express.json({ limit: "2mb" }));
app.use(express.urlencoded({ extended: true }));
app.use("/uploads", express.static(path.join(__dirname, "uploads")));
app.use(express.static(path.join(__dirname, "public")));

function safeFileName(name = "file") {
  const ext = path.extname(name).toLowerCase();
  const base = path.basename(name, ext).replace(/[^a-zA-Z0-9-_]/g, "-").replace(/-+/g, "-").slice(0, 80) || "file";
  return `${Date.now()}-${base}${ext}`;
}
function publicUrl(bucket, file) {
  return `${process.env.SUPABASE_URL}/storage/v1/object/public/${bucket}/${encodeURIComponent(file).replace(/%2F/g, "/")}`;
}
function storagePath(url, bucket) {
  if (!url) return null;
  const marker = `/storage/v1/object/public/${bucket}/`;
  const i = url.indexOf(marker);
  return i < 0 ? null : decodeURIComponent(url.slice(i + marker.length));
}

app.get("/api/health", async (req, res) => {
  const { error } = await supabase.from("songs").select("id").limit(1);
  if (error) return res.status(500).json({ ok: false, error: error.message });
  res.json({ ok: true });
});

app.get("/api/songs", async (req, res) => {
  const { data, error } = await supabase.from("songs").select("*")
    .order("release_date", { ascending: false, nullsFirst: false })
    .order("created_at", { ascending: false });
  if (error) return res.status(500).json({ error: error.message });
  res.json(data || []);
});

app.get("/api/songs/:id", async (req, res) => {
  const { data, error } = await supabase.from("songs").select("*").eq("id", req.params.id).single();
  if (error) return res.status(404).json({ error: "Song not found" });
  res.json(data);
});

app.post("/api/songs", upload.fields([
  { name: "cover", maxCount: 1 },
  { name: "audio", maxCount: 1 }
]), async (req, res) => {
  try {
    const { title, artist, genre, language, mood, description, lyrics, release_date } = req.body;
    if (!title || !artist) return res.status(400).json({ error: "Title and artist are required." });

    const cover = req.files?.cover?.[0];
    const audio = req.files?.audio?.[0];
    let cover_url = req.body.cover_url || "";
    let audio_url = req.body.audio_url || "";
    let coverPath = null, audioPath = null;

    if (cover) {
      coverPath = safeFileName(cover.originalname);
      const { error } = await supabase.storage.from("covers").upload(coverPath, cover.buffer, {
        contentType: cover.mimetype, upsert: false
      });
      if (error) throw new Error(`Cover upload failed: ${error.message}`);
      cover_url = publicUrl("covers", coverPath);
    }

    if (audio) {
      audioPath = safeFileName(audio.originalname);
      const { error } = await supabase.storage.from("audio").upload(audioPath, audio.buffer, {
        contentType: audio.mimetype || "audio/mpeg", upsert: false
      });
      if (error) throw new Error(`Audio upload failed: ${error.message}`);
      audio_url = publicUrl("audio", audioPath);
    }

    const { data, error } = await supabase.from("songs").insert({
      title, artist, genre: genre || "", language: language || "", mood: mood || "",
      description: description || "", lyrics: lyrics || "", cover_url, audio_url,
      release_date: release_date || null
    }).select().single();

    if (error) {
      if (coverPath) await supabase.storage.from("covers").remove([coverPath]);
      if (audioPath) await supabase.storage.from("audio").remove([audioPath]);
      throw new Error(`Database save failed: ${error.message}`);
    }
    res.status(201).json(data);
  } catch (e) {
    console.error(e);
    res.status(500).json({ error: e.message || "Upload failed." });
  }
});

app.delete("/api/songs/:id", async (req, res) => {
  try {
    const { data: song, error: findError } = await supabase.from("songs").select("*").eq("id", req.params.id).single();
    if (findError || !song) return res.status(404).json({ error: "Song not found" });

    const coverPath = storagePath(song.cover_url, "covers");
    const audioPath = storagePath(song.audio_url, "audio");
    const { error } = await supabase.from("songs").delete().eq("id", req.params.id);
    if (error) return res.status(500).json({ error: error.message });

    if (coverPath) await supabase.storage.from("covers").remove([coverPath]);
    if (audioPath) await supabase.storage.from("audio").remove([audioPath]);
    res.json({ success: true });
  } catch (e) {
    console.error(e);
    res.status(500).json({ error: e.message || "Delete failed." });
  }
});

app.get("/{*splat}", (req, res) => res.sendFile(path.join(__dirname, "public", "index.html")));
app.listen(PORT, () => console.log(`Heartbeat Heaven running on port ${PORT}`));
