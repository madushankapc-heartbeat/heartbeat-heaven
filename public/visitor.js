(function () {
  "use strict";

  function formatVisitors(value) {
    const number = Number(value);
    if (!Number.isFinite(number)) return "0";
    return new Intl.NumberFormat("en-US").format(Math.max(0, Math.floor(number)));
  }

  async function loadVisitorCount() {
    const el = document.getElementById("visitorCount");
    if (!el) return;

    el.textContent = "0";

    try {
      const response = await fetch("/api/visitor-count", {
        method: "POST",
        credentials: "include",
        headers: {
          Accept: "application/json"
        },
        cache: "no-store"
      });

      const raw = await response.text();
      let data = {};

      try {
        data = JSON.parse(raw);
      } catch (_) {
        throw new Error("Visitor API returned a non-JSON response");
      }

      if (!response.ok || typeof data.total_visitors === "undefined") {
        throw new Error(data.error || "Visitor counter request failed");
      }

      el.textContent = formatVisitors(data.total_visitors);
    } catch (error) {
      console.warn("Visitor counter unavailable:", error);
      // Keep a visible numeric value instead of the old dash.
      el.textContent = "0";
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", loadVisitorCount);
  } else {
    loadVisitorCount();
  }
})();
