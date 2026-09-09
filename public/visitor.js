(function () {
  "use strict";

  function formatVisitors(value) {
    const number = Number(value || 0);
    return new Intl.NumberFormat("en-US").format(number);
  }

  async function loadVisitorCount() {
    const el = document.getElementById("visitorCount");
    if (!el) return;

    try {
      const response = await fetch("/api/visitor-count", {
        method: "POST",
        credentials: "same-origin",
        headers: {
          Accept: "application/json"
        },
        cache: "no-store"
      });

      if (!response.ok) throw new Error("Visitor counter request failed");

      const data = await response.json();
      el.textContent = formatVisitors(data.total_visitors);
    } catch (error) {
      console.warn("Visitor counter unavailable:", error);
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", loadVisitorCount);
  } else {
    loadVisitorCount();
  }
})();
