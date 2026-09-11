const id =
  new URLSearchParams(location.search).get("id");

const root =
  document.getElementById("songPage");

const SITE_URL =
  "https://heartbeat-heaven.onrender.com";

async function load() {
  if (!id) {
    root.innerHTML = "<div class='empty'>Song not found.</div>";
    return;
  }

  try {
    const response = await fetch(
      "/api/songs/" + encodeURIComponent(id) + "/hub"
    );

    if (!response.ok) {
      root.innerHTML = "<div class='empty'>Song not found.</div>";
      return;
    }

    const hub = await response.json();
    const s = hub.original;
    const versions = Array.isArray(hub.versions) ? hub.versions : [];

    if (!s) {
      root.innerHTML = "<div class='empty'>Song not found.</div>";
      return;
    }

    const title = s.title || "New Song";
    const artist = s.artist || "Madushanka";
    const genre = s.genre || "Music";
    const language = s.language || "Music";
    const mood = s.mood || "";
    const description = s.description || "";

    const seoDescription =
      `${title} by ${artist}. ` +
      `Listen to the original song and its versions on HEARTBEAT HEAVEN. ` +
      `${description}`.trim();

    const canonicalUrl =
      `${SITE_URL}/song.html?id=${encodeURIComponent(s.id)}`;

    document.title =
      `${title} | ${language} ${genre} Song — ${artist}`;

    setMeta("description", seoDescription.substring(0, 300));

    setMeta(
      "keywords",
      [
        title,
        `${title} song`,
        `${title} lyrics`,
        `${title} versions`,
        `${title} remix`,
        `${title} acoustic`,
        `${title} slow version`,
        `${title} ${language} song`,
        `${language} songs`,
        `new ${language} songs`,
        "new songs",
        "new music",
        "new release song",
        "latest songs",
        `${genre} songs`,
        `${mood} songs`,
        "love songs",
        "romantic songs",
        "original music",
        "Madushanka",
        "Heartbeat Heaven"
      ].filter(Boolean).join(", ")
    );

    const canonical = document.getElementById("canonicalLink");
    if (canonical) canonical.href = canonicalUrl;

    setMetaProperty("og:title", `${title} | HEARTBEAT HEAVEN`);
    setMetaProperty("og:description", seoDescription.substring(0, 300));
    setMetaProperty("og:url", canonicalUrl);

    if (s.cover_url) setMetaProperty("og:image", s.cover_url);

    setMetaName("twitter:title", `${title} | HEARTBEAT HEAVEN`);
    setMetaName("twitter:description", seoDescription.substring(0, 300));
    if (s.cover_url) setMetaName("twitter:image", s.cover_url);

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

    if (s.release_date) schema.datePublished = s.release_date;
    if (s.cover_url) schema.image = s.cover_url;
    if (s.audio_url) {
      schema.associatedMedia = {
        "@type": "AudioObject",
        "contentUrl": s.audio_url
      };
    }

    const schemaElement = document.getElementById("songSchema");
    if (schemaElement) schemaElement.textContent = JSON.stringify(schema);

    let allSongs = [];

    try {
      const songsResponse = await fetch("/api/songs");
      if (songsResponse.ok) allSongs = await songsResponse.json();
    } catch (error) {
      console.error("Unable to load song library:", error);
    }

    allSongs.sort((a, b) => {
      const dateA = a.release_date || a.created_at || "";
      const dateB = b.release_date || b.created_at || "";

      if (dateA && dateB) {
        return new Date(dateB) - new Date(dateA);
      }

      return Number(b.id || 0) - Number(a.id || 0);
    });

    const currentIndex = allSongs.findIndex(
      song => String(song.id) === String(s.id)
    );

    let previousSong = null;
    let nextSong = null;

    if (currentIndex !== -1) {
      previousSong = allSongs[currentIndex + 1] || null;
      nextSong = allSongs[currentIndex - 1] || null;
    }

    const relatedSongs = allSongs
      .filter(song => String(song.id) !== String(s.id))
      .sort((a, b) => {
        const scoreA = getRelatedScore(a, s);
        const scoreB = getRelatedScore(b, s);
        return scoreB - scoreA;
      })
      .slice(0, 3);

    root.innerHTML = `
      <section class="song-hero">
        ${s.cover_url ? `
          <img class="song-cover" src="${esc(s.cover_url)}" alt="${esc(title)} — ${esc(language)} ${esc(genre)} song cover" itemprop="image">
        ` : ""}
        <div>
          <p class="eyebrow">${esc(genre)} • ${esc(language)}</p>
          <h1 class="song-title" itemprop="name">${esc(title)}</h1>
          <p class="meta">${esc(artist)}${mood ? " • " + esc(mood) : ""}</p>
          <p class="song-desc" itemprop="description">${esc(description)}</p>
        </div>
      </section>

      <section class="original-player">
        <div class="original-player-head">
          <p class="eyebrow">ORIGINAL SONG</p>
          <h2>${esc(title)}</h2>
        </div>
        <div class="original-player-audio">
          ${s.audio_url ? `
            <audio class="version-audio" controls preload="metadata" src="${esc(s.audio_url)}"></audio>
          ` : `
            <p class="empty">Audio not available.</p>
          `}
        </div>
      </section>

      <section class="song-hub">
        <div class="related-head">
          <div>
            <p class="eyebrow">SONG HUB</p>
            <h2>Song Versions</h2>
          </div>
        </div>
        <div class="versions-list">
          ${renderVersionCards(versions, s)}
        </div>
      </section>

      <div class="song-share">
        <button class="share-btn" onclick="shareSong()">↗ Share</button>
        <button class="share-btn" onclick="shareWhatsApp()">WhatsApp</button>
        <button class="share-btn" onclick="shareFacebook()">Facebook</button>
        <button class="share-btn" onclick="copySongLink()">Copy Link</button>
      </div>

      ${renderSongNavigation(previousSong, nextSong)}

      <section class="lyrics-wrap">
        <p class="eyebrow">LYRICS</p>
        <div class="lyrics" itemprop="lyrics">
          ${esc(s.lyrics) || "Lyrics will be added soon."}
        </div>
      </section>

      ${renderRelatedSongs(relatedSongs)}
    `;

    setupAudioPlayers();
  } catch (error) {
    console.error("Song Hub error:", error);
    root.innerHTML = "<div class='empty'>Unable to load song.</div>";
  }
}

function renderVersionCards(versions, original) {
  if (!versions.length) {
    return `<div class="empty">No additional versions available yet.</div>`;
  }

  return versions.map(version => {
    const versionName = version.version_name || "Version";

    return `
      <article class="version-card" data-version-id="${esc(version.id)}">
        <div class="version-info">
          <p class="eyebrow">VERSION</p>
          <h3 class="version-title">${esc(versionName)}</h3>
          ${version.audio_url ? `
            <audio class="version-audio" controls preload="metadata" src="${esc(version.audio_url)}"></audio>
          ` : `
            <p class="empty">Audio not available.</p>
          `}
        </div>
      </article>
    `;
  }).join("");
}

function setupAudioPlayers() {
  const players = root.querySelectorAll("audio");
  players.forEach(player => {
    player.addEventListener("play", () => {
      players.forEach(other => {
        if (other !== player) other.pause();
      });
    });
  });
}

function renderSongNavigation(previousSong, nextSong) {
  if (!previousSong && !nextSong) return "";

  return `
    <div class="song-navigation">
      ${previousSong ? `
        <a class="song-nav-btn" href="/song.html?id=${encodeURIComponent(previousSong.id)}">
          <span class="song-nav-label">← PREVIOUS</span>
          <strong>${esc(previousSong.title || "Previous Song")}</strong>
        </a>
      ` : `
        <div class="song-nav-btn disabled">
          <span class="song-nav-label">← PREVIOUS</span>
          <strong>First Song</strong>
        </div>
      `}

      <a class="song-nav-center" href="/songs.html">ALL SONGS</a>

      ${nextSong ? `
        <a class="song-nav-btn next" href="/song.html?id=${encodeURIComponent(nextSong.id)}">
          <span class="song-nav-label">NEXT →</span>
          <strong>${esc(nextSong.title || "Next Song")}</strong>
        </a>
      ` : `
        <div class="song-nav-btn disabled">
          <span class="song-nav-label">NEXT →</span>
          <strong>Latest Song</strong>
        </div>
      `}
    </div>
  `;
}

function renderRelatedSongs(songs) {
  if (!songs.length) return "";

  return `
    <section class="related-section">
      <div class="related-head">
        <div>
          <p class="eyebrow">KEEP LISTENING</p>
          <h2>Related Songs</h2>
        </div>
        <a href="/songs.html" class="related-all">View All →</a>
      </div>

      <div class="related-grid">
        ${songs.map(song => `
          <a class="related-card" href="/song.html?id=${encodeURIComponent(song.id)}">
            <img src="${esc(song.cover_url || "")}" alt="${esc(song.title || "Song")} cover" loading="lazy">
            <div class="related-card-body">
              <p class="related-genre">
                ${esc(song.genre || "Music")}
                ${song.language ? " • " + esc(song.language) : ""}
              </p>
              <h3>${esc(song.title || "Untitled Song")}</h3>
              <p class="related-artist">${esc(song.artist || "Madushanka")}</p>
            </div>
          </a>
        `).join("")}
      </div>
    </section>
  `;
}

function getRelatedScore(song, current) {
  let score = 0;

  if (song.genre && current.genre && song.genre.toLowerCase() === current.genre.toLowerCase()) score += 5;
  if (song.language && current.language && song.language.toLowerCase() === current.language.toLowerCase()) score += 4;
  if (song.mood && current.mood && song.mood.toLowerCase() === current.mood.toLowerCase()) score += 2;

  return score;
}

function getSongShareUrl() {
  return `${SITE_URL}/song.html?id=` + encodeURIComponent(id);
}

async function shareSong() {
  const shareData = {
    title: document.title,
    text: "Listen to this song and its versions on HEARTBEAT HEAVEN 🎵",
    url: getSongShareUrl()
  };

  if (navigator.share) {
    try {
      await navigator.share(shareData);
    } catch (error) {
      console.log("Share cancelled.");
    }
  } else {
    await copySongLink();
    alert("Song link copied!");
  }
}

function shareWhatsApp() {
  const url = "https://wa.me/?text=" + encodeURIComponent(`${document.title}\n${getSongShareUrl()}`);
  window.open(url, "_blank", "noopener,noreferrer");
}

function shareFacebook() {
  const url = "https://www.facebook.com/sharer/sharer.php?u=" + encodeURIComponent(getSongShareUrl());
  window.open(url, "_blank", "noopener,noreferrer");
}

async function copySongLink() {
  try {
    await navigator.clipboard.writeText(getSongShareUrl());
    alert("Song link copied!");
  } catch (error) {
    const temp = document.createElement("input");
    temp.value = getSongShareUrl();
    document.body.appendChild(temp);
    temp.select();
    document.execCommand("copy");
    temp.remove();
    alert("Song link copied!");
  }
}

function setMeta(name, content) {
  let el = document.querySelector(`meta[name="${name}"]`);

  if (!el) {
    el = document.createElement("meta");
    el.setAttribute("name", name);
    document.head.appendChild(el);
  }

  el.setAttribute("content", content || "");
}

function setMetaName(name, content) {
  setMeta(name, content);
}

function setMetaProperty(property, content) {
  let el = document.querySelector(`meta[property="${property}"]`);

  if (!el) {
    el = document.createElement("meta");
    el.setAttribute("property", property);
    document.head.appendChild(el);
  }

  el.setAttribute("content", content || "");
}

function esc(x) {
  return String(x || "").replace(/[&<>"']/g, m => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#039;"
  }[m]));
}

/* =========================================================
   SONG HUB HEADER — MOBILE + BRAND CONSISTENCY
========================================================= */

function setupSongHubHeader() {
  const header = document.querySelector(".topbar");
  if (!header) return;

  const brand = header.querySelector(".brand");
  if (brand) {
    const oldIcon = brand.querySelector(":scope > span");

    if (oldIcon) {
      const logo = document.createElement("img");
      logo.src = "/heartbeat-heaven-logo.png";
      logo.alt = "HEARTBEAT HEAVEN logo";
      logo.style.cssText = "width:40px;height:40px;object-fit:cover;border-radius:50%;display:block;";
      oldIcon.replaceWith(logo);
    }
  }

  let nav = header.querySelector("#mainNav");
  if (!nav) nav = header.querySelector("nav");
  if (!nav) return;

  let toggle = header.querySelector("#menuToggle");

  if (!toggle) {
    toggle = document.createElement("button");
    toggle.id = "menuToggle";
    toggle.className = "menu-toggle";
    toggle.type = "button";
    toggle.setAttribute("aria-label", "Open menu");
    toggle.setAttribute("aria-expanded", "false");
    toggle.textContent = "☰";
    header.insertBefore(toggle, nav);
  }

  if (toggle.dataset.bound === "true") return;
  toggle.dataset.bound = "true";

  toggle.addEventListener("click", () => {
    const open = nav.classList.toggle("open");
    toggle.setAttribute("aria-expanded", String(open));
    toggle.setAttribute("aria-label", open ? "Close menu" : "Open menu");
  });

  nav.querySelectorAll("a").forEach(link => {
    link.addEventListener("click", () => {
      nav.classList.remove("open");
      toggle.setAttribute("aria-expanded", "false");
      toggle.setAttribute("aria-label", "Open menu");
    });
  });
}

setupSongHubHeader();
load();
