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
        `Listen to ${title} by ${artist} on HEART
