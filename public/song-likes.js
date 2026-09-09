(function () {
  "use strict";

  const songId = new URLSearchParams(location.search).get("id");
  const root = document.getElementById("songPage");
  if (!songId || !root) return;

  function addStyles() {
    if (document.getElementById("songLikeStyles")) return;
    const style = document.createElement("style");
    style.id = "songLikeStyles";
    style.textContent = `
      .song-like-wrap{margin:22px 0 8px;display:flex;align-items:center;gap:12px;flex-wrap:wrap}
      .song-like-btn{border:1px solid rgba(212,175,55,.45);background:rgba(212,175,55,.08);color:#f5d77a;border-radius:999px;padding:10px 18px;min-height:44px;font-weight:700;cursor:pointer;transition:.2s ease}
      .song-like-btn:hover{transform:translateY(-1px);background:rgba(212,175,55,.15)}
      .song-like-btn.liked{background:rgba(212,175,55,.2);border-color:#d4af37;color:#fff}
      .song-like-count{color:#cfcfcf;font-size:.95rem}
    `;
    document.head.appendChild(style);
  }

  function bindButton() {
    const btn = document.getElementById("songLikeBtn");
    const count = document.getElementById("songLikeCount");
    if (!btn || !count || btn.dataset.bound === "true") return;

    btn.dataset.bound = "true";

    btn.addEventListener("click", async function () {
      btn.disabled = true;
      try {
        const method = btn.classList.contains("liked") ? "DELETE" : "POST";
        const response = await fetch("/api/song-likes/" + encodeURIComponent(songId), {
          method,
          credentials: "include",
          headers: { Accept: "application/json" },
          cache: "no-store"
        });

        const result = await response.json();
        if (!response.ok) throw new Error(result.error || "Like request failed");

        const liked = Boolean(result.liked);
        const likes = Number(result.like_count || 0);
        btn.classList.toggle("liked", liked);
        btn.setAttribute("aria-pressed", liked ? "true" : "false");
        btn.textContent = liked ? "♥ Liked" : "♡ Like";
        count.textContent = `${likes} ${likes === 1 ? "Like" : "Likes"}`;
      } catch (error) {
        console.warn("Song like unavailable:", error);
      } finally {
        btn.disabled = false;
      }
    });
  }

  function render(data) {
    const hero = root.querySelector(".song-hero");
    if (!hero) return;

    if (document.getElementById("songLikeWrap")) {
      bindButton();
      return;
    }

    addStyles();

    const wrap = document.createElement("div");
    wrap.id = "songLikeWrap";
    wrap.className = "song-like-wrap";
    wrap.innerHTML = `
      <button type="button" class="song-like-btn${data.liked ? " liked" : ""}" id="songLikeBtn" aria-pressed="${data.liked ? "true" : "false"}">${data.liked ? "♥ Liked" : "♡ Like"}</button>
      <span class="song-like-count" id="songLikeCount">${Number(data.like_count || 0)} ${Number(data.like_count || 0) === 1 ? "Like" : "Likes"}</span>
    `;

    hero.insertAdjacentElement("afterend", wrap);
    bindButton();
  }

  async function loadLikeState() {
    try {
      const response = await fetch("/api/song-likes/" + encodeURIComponent(songId), {
        credentials: "include",
        headers: { Accept: "application/json" },
        cache: "no-store"
      });
      const data = await response.json();
      if (!response.ok) throw new Error(data.error || "Unable to load likes");
      render(data);
    } catch (error) {
      console.warn("Song like state unavailable:", error);
    }
  }

  /*
   * Keep observing because song.js replaces #songPage.innerHTML
   * after its API request. The previous implementation disconnected
   * too early, so the button could appear briefly and then disappear.
   */
  const observer = new MutationObserver(function () {
    if (root.querySelector(".song-hero") && !document.getElementById("songLikeWrap")) {
      loadLikeState();
    }
  });

  observer.observe(root, { childList: true, subtree: true });

  if (root.querySelector(".song-hero")) {
    loadLikeState();
  }
})();
