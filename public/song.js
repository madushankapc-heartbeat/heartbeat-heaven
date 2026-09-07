const id = new URLSearchParams(location.search).get("id");
const root = document.getElementById("songPage");

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

    document.title = s.title + " — Heartbeat Heaven";

    root.innerHTML = `
      <section class="song-hero">
        <img
          class="song-cover"
          src="${s.cover_url || ""}"
          alt="${esc(s.title)} cover"
        >

        <div>
          <p class="eyebrow">
            ${esc(s.genre)} • ${esc(s.language)}
          </p>

          <h1 class="song-title">${esc(s.title)}</h1>

          <p class="meta">
            ${esc(s.artist)} • ${esc(s.mood)}
          </p>

          <p class="song-desc">
            ${esc(s.description)}
          </p>

          <audio
            controls
            preload="metadata"
            style="width:100%;margin-top:25px"
            src="${s.audio_url || ""}"
          ></audio>
        </div>
      </section>

      <section class="lyrics-wrap">
        <p class="eyebrow">LYRICS</p>
        <div class="lyrics">
          ${esc(s.lyrics) || "Lyrics will be added soon."}
        </div>
      </section>
    `;
  } catch (error) {
    console.error(error);
    root.innerHTML = "<div class='empty'>Unable to load song.</div>";
  }
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

load();
