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

app.post("/api/visitor-count", async (req, res) => {
  try {
    const userAgent = String(req.headers["user-agent"] || "").toLowerCase();
    const likelyBot = /bot|crawler|spider|slurp|bingpreview|facebookexternalhit|linkedinbot|whatsapp|telegrambot|headless/i.test(userAgent);

    const cookieName = "hh_visitor_id";
    const existingVisitor = getCookie(req, cookieName);

    let totalVisitors = 0;

    if (!existingVisitor && !likelyBot) {
      const visitorId = crypto.randomBytes(24).toString("hex");

      res.set(
        "Set-Cookie",
        cookieName + "=" + visitorId + "; Max-Age=31536000; Path=/; HttpOnly; Secure; SameSite=Lax"
      );

      const { data, error } = await supabase.rpc(
        "increment_site_visitors"
      );

      if (error) {
        console.error("Visitor counter increment error:", error);
        return res.status(500).json({
          error: "Could not update visitor count."
        });
      }

      totalVisitors = Number(data || 0);
    } else {
      const { data, error } = await supabase
        .from("site_stats")
        .select("total_visitors")
        .eq("id", 1)
        .single();

      if (error) {
        console.error("Visitor counter read error:", error);
        return res.status(500).json({
          error: "Could not read visitor count."
        });
      }

      totalVisitors = Number(data?.total_visitors || 0);
    }

    res.set("Cache-Control", "no-store");

    res.json({
      total_visitors: totalVisitors
    });
  } catch (error) {
    console.error("Visitor counter error:", error);

    res.status(500).json({
      error: "Could not load visitor count."
    });
  }
});`;

    if (!source.includes('app.post("/api/visitor-count"')) {
      if (!source.includes(marker)) {
        throw new Error("Visitor counter injection marker not found in server.js");
      }

      source = source.replace(
        marker,
        `${marker}${injection}`
      );
    }

    return module._compile(source, filename);
  }

  return originalLoader(module, filename);
};

require("./server.js");
