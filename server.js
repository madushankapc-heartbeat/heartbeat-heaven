const express = require("express");
const multer = require("multer");
const Database = require("better-sqlite3");
const path = require("path");
const fs = require("fs");

const app = express();
const PORT = process.env.PORT || 3000;
const ROOT = __dirname;
const PUBLIC = path.join(ROOT, "public");
const UPLOADS = path.join(ROOT, "uploads");
const DATA = path.join(ROOT, "data");
fs.mkdirSync(UPLOADS, {recursive:true});
fs.mkdirSync(DATA, {recursive:true});

const db = new Database(path.join(DATA, "heartbeat-heaven.db"));
db.pragma("journal_mode = WAL");
db.exec(`CREATE TABLE IF NOT EXISTS songs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  title TEXT NOT NULL,
  artist TEXT NOT NULL DEFAULT 'Madushanka',
  genre TEXT DEFAULT 'Romantic',
  language TEXT DEFAULT 'Sinhala',
  mood TEXT DEFAULT 'Romantic',
  description TEXT DEFAULT '',
  lyrics TEXT DEFAULT '',
  audio TEXT NOT NULL,
  cover TEXT NOT NULL,
  created_at TEXT DEFAULT CURRENT_TIMESTAMP
)`);

const storage = multer.diskStorage({
  destination: (_req, _file, cb) => cb(null, UPLOADS),
  filename: (_req, file, cb) => {
    const ext = path.extname(file.originalname).toLowerCase();
    const safe = path.basename(file.originalname, ext).replace(/[^a-zA-Z0-9_-]/g, "-").slice(0,60);
    cb(null, Date.now() + "-" + safe + ext);
  }
});
const upload = multer({
  storage,
  limits: { fileSize: 50 * 1024 * 1024 },
  fileFilter: (_req, file, cb) => {
    const ok = ["audio/mpeg","audio/wav","audio/x-wav","audio/mp4","audio/aac","image/jpeg","image/png","image/webp"].includes(file.mimetype);
    cb(ok ? null : new Error("Only MP3/WAV/M4A/AAC audio and JPG/PNG/WEBP images are allowed."), ok);
  }
});

app.use(express.json({limit:"2mb"}));
app.use(express.urlencoded({extended:true}));
app.use("/uploads", express.static(UPLOADS));
app.use(express.static(PUBLIC));

app.get("/api/songs", (_req,res) => {
  res.json(db.prepare("SELECT * FROM songs ORDER BY id DESC").all());
});

app.get("/api/songs/:id", (req,res) => {
  const song = db.prepare("SELECT * FROM songs WHERE id=?").get(req.params.id);
  if (!song) return res.status(404).json({error:"Song not found"});
  res.json(song);
});

app.post("/api/songs",
  upload.fields([{name:"audio",maxCount:1},{name:"cover",maxCount:1}]),
  (req,res) => {
    try {
      const {title,artist="Madushanka",genre="Romantic",language="Sinhala",mood="Romantic",description="",lyrics=""}=req.body;
      if (!title || !req.files?.audio?.[0] || !req.files?.cover?.[0]) {
        return res.status(400).json({error:"Title, audio and cover are required."});
      }
      const audio = "/uploads/" + req.files.audio[0].filename;
      const cover = "/uploads/" + req.files.cover[0].filename;
      const info = db.prepare(`INSERT INTO songs
        (title,artist,genre,language,mood,description,lyrics,audio,cover)
        VALUES (?,?,?,?,?,?,?,?,?)`).run(title,artist,genre,language,mood,description,lyrics,audio,cover);
      res.json(db.prepare("SELECT * FROM songs WHERE id=?").get(info.lastInsertRowid));
    } catch(e) { res.status(500).json({error:e.message}); }
  }
);

app.delete("/api/songs/:id", (req,res) => {
  const song = db.prepare("SELECT * FROM songs WHERE id=?").get(req.params.id);
  if (!song) return res.status(404).json({error:"Song not found"});
  [song.audio,song.cover].forEach(u => {
    const p = path.join(ROOT, u.replace(/^\/+/,""));
    if (fs.existsSync(p)) fs.unlinkSync(p);
  });
  db.prepare("DELETE FROM songs WHERE id=?").run(req.params.id);
  res.json({ok:true});
});

app.use((err,_req,res,_next) => res.status(400).json({error:err.message}));

app.listen(PORT, () => console.log(`Heartbeat Heaven running at http://localhost:${PORT}`));
