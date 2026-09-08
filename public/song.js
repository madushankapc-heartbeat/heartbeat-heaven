const id = new URLSearchParams(location.search).get("id");
const root = document.getElementById("songPage");

const SITE_URL = "https://heartbeat-heaven.onrender.com";

async function load() {
  if (!id) {
    root.innerHTML = "<div class='empty'>Song not found.</div>";
    return;
  }

  try {
    const r = await fetch("/api/songs/" + id);

    if (!r.ok) {
      root.innerHTML = "<div class='empty'>Song not found.</div>";
      return;
    }

    const s = await r.json();

    /* =====================================================
       GOOGLE SEO — DYNAMIC SONG SEO
    ===================================================== */

    const title = s.title || "New Song";
    const artist = s.artist || "Madushanka";
    const genre = s.genre || "Music";
    const language = s.language || "Music";
    const mood = s.mood || "";
    const description = s.description || "";

    const seoDescription =
      `${title} by ${artist}. ` +
      `Listen to this original ${language} ${genre} song on HEARTBEAT HEAVEN. ` +
      `${description}`.trim();

    const canonicalUrl =
      `${SITE_URL}/song.html?id=${encodeURIComponent(id)}`;

    /* Page title */

    document.title =
      `${title} | ${language} ${genre} Song — Madushanka`;

    /* Meta description */

    setMeta(
      "description",
      seoDescription.substring(0, 300)
    );

    /* Keywords */

    setMeta(
      "keywords",
      [
        title,
        `${title} song`,
        `${title} lyrics`,
        `${title} ${language} song`,
        `${language} songs`,
        `new ${language} songs`,
        `new songs`,
        `new music`,
        `new release song`,
        `latest songs`,
        `${genre} songs`,
        `${mood} songs`,
        "love songs",
        "romantic songs",
        "original music",
        "Madushanka",
        "Heartbeat Heaven"
      ].filter(Boolean).join(", ")
    );

    /* Canonical */

    const canonical =
      document.getElementById("canonicalLink");

    if (canonical) {
      canonical.href = canonicalUrl;
    }

    /* =====================================================
       OPEN GRAPH — FACEBOOK / WHATSAPP
    ===================================================== */

    setMetaProperty(
      "og:title",
      `${title} | HEARTBEAT HEAVEN`
    );

    setMetaProperty(
      "og:description",
      seoDescription.substring(0, 300)
    );

    setMetaProperty(
      "og:url",
      canonicalUrl
    );

    if (s.cover_url) {
      setMetaProperty(
        "og:image",
        s.cover_url
      );
    }

    /* =====================================================
       TWITTER / X
    ===================================================== */

    setMetaName(
      "twitter:title",
      `${title} | HEARTBEAT HEAVEN`
    );

    setMetaName(
      "twitter:description",
      seoDescription.substring(0, 300)
    );

    if (s.cover_url) {
      setMetaName(
        "twitter:image",
        s.cover_url
      );
    }

    /* =====================================================
       GOOGLE STRUCTURED DATA
       MusicRecording
    ===================================================== */

    const schema = {
      "@context": "https://schema.org",
      "@type": "MusicRecording",

      "name": title,

      "url": canonicalUrl,

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
        "url": SITE_URL
      }
    };

    if (s.release_date) {
      schema.datePublished = s.release_date;
    }

    if (s.cover_url) {
      schema.image = s.cover_url;
    }

    if (s.audio_url) {
      schema.associatedMedia = {
        "@type": "AudioObject",
        "contentUrl": s.audio_url
      };
    }

    const schemaElement =
      document.getElementById("songSchema");

    if (schemaElement) {
      schemaElement.textContent =
        JSON.stringify(schema);
    }

    /* =====================================================
       SONG PAGE
    ===================================================== */

    root.innerHTML = `
      <section class="song-hero">

        <img
          class="song-cover"
          src="${esc(s.cover_url || "")}"
          alt="${esc(title)} — ${esc(language)} ${esc(genre)} song cover"
          itemprop="image"
        >

        <div>

          <p class="eyebrow">
            ${esc(genre)} • ${esc(language)}
          </p>

          <h1 class="song-title" itemprop="name">
            ${esc(title)}
          </h1>

          <p class="meta">
            ${esc(artist)} • ${esc(mood)}
          </p>

          <p
            class="song-desc"
            itemprop="description"
          >
            ${esc(description)}
          </p>

          <audio
            controls
            preload="metadata"
            style="width:100%;margin-top:25px"
            src="${esc(s.audio_url || "")}"
          ></audio>

        </div>

      </section>

      <section class="lyrics-wrap">

        <p class="eyebrow">LYRICS</p>

        <div
          class="lyrics"
          itemprop="lyrics"
        >
          ${esc(s.lyrics) || "Lyrics will be added soon."}
        </div>

      </section>
    `;

  } catch (error) {

    console.error(error);

    root.innerHTML =
      "<div class='empty'>Unable to load song.</div>";
  }
}


/* =========================================================
   SEO HELPER FUNCTIONS
========================================================= */

function setMeta(name, content) {

  let el =
    document.querySelector(
      `meta[name="${name}"]`
    );

  if (!el) {

    el = document.createElement("meta");

    el.setAttribute("name", name);

    document.head.appendChild(el);
  }

  el.setAttribute(
    "content",
    content || ""
  );
}


function setMetaName(name, content) {

  setMeta(name, content);
}


function setMetaProperty(property, content) {

  let el =
    document.querySelector(
      `meta[property="${property}"]`
    );

  if (!el) {

    el = document.createElement("meta");

    el.setAttribute(
      "property",
      property
    );

    document.head.appendChild(el);
  }

  el.setAttribute(
    "content",
    content || ""
  );
}


/* =========================================================
   HTML ESCAPE
========================================================= */

function esc(x) {

  return String(x || "")
    .replace(
      /[&<>"']/g,
      m => ({
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        '"': "&quot;",
        "'": "&#039;"
      }[m])
    );
}


load();
