let songs = [], current = null;
let currentFilter = "all";

const grid = document.getElementById("songGrid");
const search = document.getElementById("search");
const audio = document.getElementById("audio");
const player = document.getElementById("player");

async function load() {
  try {
    const response = await fetch("/api/songs");
    songs = await response.json();
    applyFilters();
  } catch (error) {
    console.error(error);
    grid.innerHTML = '<div class="empty">Unable to load songs.</div>';
  }
}

function render(list) {
  if (!list.length) {
    grid.innerHTML =
      '<div class="empty">No matching songs found.</div>';
    return;
  }

  grid.innerHTML = list.map(s => `
    <a
      class="card"
      href="/song.html?id=${encodeURIComponent(s.id)}"
      aria-label="Open ${esc(s.title)} by ${esc(s.artist)}"
    >
      <img
        class="cover"
        src="${esc(s.cover_url || "")}" 
        alt="${esc(s.title)} cover"
      >

      <div class="card-body">
        <h3>${esc(s.title)}</h3>

        <div class="meta">
          ${esc(s.artist)} • ${esc(s.language)}
        </div>

        <span class="tag">${esc(s.genre)}</span>
      </div>
    </a>
  `).join("");
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

function openSong(id) {
  location.href = "/song.html?id=" + encodeURIComponent(id);
}

function playSong(s) {
  current = s;

  document.getElementById("playerCover").src = s.cover_url || "";
  document.getElementById("playerTitle").textContent = s.title;
  document.getElementById("playerArtist").textContent = s.artist;

  audio.src = s.audio_url || "";

  player.classList.remove("hidden");

  audio.play().catch(error => {
    console.error("Audio playback error:", error);
  });

  document.getElementById("playBtn").textContent = "❚❚";
}

document.getElementById("playBtn").onclick = () => {
  if (audio.paused) {
    audio.play();
    playBtn.textContent = "❚❚";
  } else {
    audio.pause();
    playBtn.textContent = "▶";
  }
};

document.getElementById("closePlayer").onclick = () => {
  audio.pause();
  audio.currentTime = 0;
  player.classList.add("hidden");
};

audio.ontimeupdate = () => {
  progress.value = audio.duration
    ? (audio.currentTime / audio.duration) * 100
    : 0;

  time.textContent = fmt(audio.currentTime);
};

progress.oninput = () => {
  if (audio.duration) {
    audio.currentTime =
      (progress.value / 100) * audio.duration;
  }
};

function fmt(n) {
  n = Math.floor(n || 0);
  return (
    Math.floor(n / 60) +
    ":" +
    String(n % 60).padStart(2, "0")
  );
}

function applyFilters() {
  const q = (search?.value || "").trim().toLowerCase();
  const f = currentFilter.toLowerCase();

  const filtered = songs.filter(s => {
    const matchesFilter =
      f === "all" ||
      (s.genre || "").toLowerCase().includes(f) ||
      (s.mood || "").toLowerCase().includes(f);

    const searchText = (
      (s.title || "") + " " +
      (s.artist || "") + " " +
      (s.genre || "") + " " +
      (s.language || "") + " " +
      (s.mood || "")
    ).toLowerCase();

    return matchesFilter && searchText.includes(q);
  });

  render(filtered);

  document.querySelectorAll("[data-filter]").forEach(button => {
    button.classList.toggle(
      "active",
      button.dataset.filter.toLowerCase() === f
    );
  });
}

if (search) {
  search.oninput = applyFilters;
}

document.querySelectorAll("[data-filter]").forEach(button => {
  button.onclick = () => {
    currentFilter = button.dataset.filter || "all";
    applyFilters();
  };
});

load();
