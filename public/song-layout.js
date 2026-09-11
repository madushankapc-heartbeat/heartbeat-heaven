(function setupCleanSongLayout() {
  const root = document.getElementById("songPage");
  if (!root) return;

  let applied = false;

  const escapeHtml = value => String(value).replace(/[&<>\"']/g, char => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '\"': "&quot;",
    "'": "&#039;"
  }[char]));

  function apply() {
    if (applied) return;

    const hub = root.querySelector(".song-hub");
    const list = hub && hub.querySelector(".versions-list");
    if (!hub || !list) return;

    const cards = Array.from(list.querySelectorAll(".version-card"));
    if (!cards.length) return;

    const original = cards[0];
    const originalAudio = original.querySelector("audio");
    if (!originalAudio) return;

    const title = root.querySelector(".song-title");
    const songTitle = title ? title.textContent.trim() : "Original Song";

    const originalSection = document.createElement("section");
    originalSection.className = "original-player";
    originalSection.innerHTML = `
      <div class="original-player-head">
        <p class="eyebrow">ORIGINAL SONG</p>
        <h2>${escapeHtml(songTitle)}</h2>
      </div>
    `;

    const playerWrap = document.createElement("div");
    playerWrap.className = "original-player-audio";
    playerWrap.appendChild(originalAudio);
    originalSection.appendChild(playerWrap);

    original.remove();

    if (hub.parentNode) {
      hub.parentNode.insertBefore(originalSection, hub);
    }

    const hubTitle = hub.querySelector(".related-head h2");
    if (hubTitle) hubTitle.textContent = "Song Versions";

    cards.slice(1).forEach(card => {
      card.querySelectorAll(".version-cover").forEach(image => image.remove());
      card.querySelectorAll(".version-meta").forEach(meta => meta.remove());
    });

    if (cards.length === 1) {
      list.innerHTML = `<div class="empty">No additional versions available yet.</div>`;
    }

    applied = true;
    if (observer) observer.disconnect();
  }

  const observer = new MutationObserver(() => apply());
  observer.observe(root, { childList: true, subtree: true });

  apply();

  const retryTimes = [100, 300, 700, 1500, 3000];
  retryTimes.forEach(delay => setTimeout(apply, delay));
})();
