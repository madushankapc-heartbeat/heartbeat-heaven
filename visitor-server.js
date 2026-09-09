const Module = require("module");
const fs = require("fs");
const path = require("path");

const originalLoader = Module._extensions[".js"];
const serverPath = path.join(__dirname, "server.js");

Module._extensions[".js"] = function (module, filename) {
  if (path.resolve(filename) === path.resolve(serverPath)) {
    let source = fs.readFileSync(filename, "utf8");

    const marker = 'app.use(express.urlencoded({ extended: true }));';

    const injection = `

/* =========================================================
   PUBLIC VISITOR COUNTER
   ========================================================= */

async function getVisitorTotal() {
  const { data, error } = await supabase
    .from("site_stats")
    .select("total_visitors")
    .eq("id", 1)
    .maybeSingle();

  if (error) throw error;
  return Number(data?.total_visitors ?? 0);
}

app.get("/api/visitor-count", async (req, res) => {
  try {
    const totalVisitors = await getVisitorTotal();
    res.set("Cache-Control", "no-store, no-cache, must-revalidate");
    res.json({ total_visitors: totalVisitors });
  } catch (error) {
    console.error("Visitor counter GET error:", error);
    res.status(503).json({ error: "visitor_counter_unavailable" });
  }
});

app.post("/api/visitor-count", async (req, res) => {
  try {
    const userAgent = String(req.headers["user-agent"] || "").toLowerCase();
    const likelyBot = /bot|crawler|spider|slurp|bingpreview|facebookexternalhit|linkedinbot|whatsapp|telegrambot|headless/i.test(userAgent);

    const cookieName = "hh_visitor_id";
    const existingVisitor = getCookie(req, cookieName);

    if (!existingVisitor && !likelyBot) {
      const visitorId = crypto.randomBytes(24).toString("hex");

      const { data, error } = await supabase.rpc("increment_site_visitors");

      if (error) {
        console.error("Visitor counter RPC error:", error);
        return res.status(503).json({ error: "visitor_counter_unavailable" });
      }

      res.set(
        "Set-Cookie",
        cookieName + "=" + visitorId + "; Max-Age=31536000; Path=/; HttpOnly; Secure; SameSite=Lax"
      );

      res.set("Cache-Control", "no-store, no-cache, must-revalidate");
      return res.json({ total_visitors: Number(data ?? 0) });
    }

    const totalVisitors = await getVisitorTotal();
    res.set("Cache-Control", "no-store, no-cache, must-revalidate");
    res.json({ total_visitors: totalVisitors });
  } catch (error) {
    console.error("Visitor counter POST error:", error);
    res.status(503).json({ error: "visitor_counter_unavailable" });
  }
});`;

    if (!source.includes('app.post("/api/visitor-count"')) {
      if (!source.includes(marker)) {
        throw new Error("Visitor counter injection marker not found in server.js");
      }

      source = source.replace(marker, `${marker}${injection}`);
    }

    return module._compile(source, filename);
  }

  return originalLoader(module, filename);
};

require("./server.js");
