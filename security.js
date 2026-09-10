const crypto = require("crypto");

function install(app) {
  app.set("trust proxy", 1);

  app.use((req, res, next) => {
    res.set("X-Content-Type-Options", "nosniff");
    res.set("X-Frame-Options", "DENY");
    res.set("Referrer-Policy", "strict-origin-when-cross-origin");
    res.set(
      "Permissions-Policy",
      "geolocation=(), microphone=(), camera=()"
    );
    res.set(
      "Content-Security-Policy",
      "default-src 'self'; " +
        "base-uri 'self'; " +
        "object-src 'none'; " +
        "frame-ancestors 'none'; " +
        "form-action 'self'; " +
        "img-src 'self' data: https:; " +
        "media-src 'self' https: blob:; " +
        "script-src 'self' 'unsafe-inline'; " +
        "style-src 'self' 'unsafe-inline'; " +
        "connect-src 'self' https:; " +
        "font-src 'self' https: data:"
    );

    if (req.secure) {
      res.set(
        "Strict-Transport-Security",
        "max-age=31536000; includeSubDomains"
      );
    }

    next();
  });

  function isSameOriginRequest(req) {
    const origin = String(req.get("origin") || "").trim();
    const referer = String(req.get("referer") || "").trim();
    const forwardedProto = String(
      req.get("x-forwarded-proto") || req.protocol || "https"
    )
      .split(",")[0]
      .trim();
    const host = String(req.get("host") || "").trim();

    if (!host || !forwardedProto) return false;

    const expectedOrigin = forwardedProto + "://" + host;

    if (origin) return origin === expectedOrigin;

    if (referer) {
      try {
        return new URL(referer).origin === expectedOrigin;
      } catch {
        return false;
      }
    }

    return false;
  }

  function csrfProtection(req, res, next) {
    if (!isSameOriginRequest(req)) {
      return res.status(403).json({
        error: "Cross-site request blocked."
      });
    }

    next();
  }

  function isProtectedMutationRoute(route) {
    return (
      route === "/api/studio/login" ||
      route === "/api/studio/logout" ||
      route === "/api/studio/upload-url" ||
      route === "/api/songs" ||
      route === "/api/songs/:id"
    );
  }

  const loginAttempts = new Map();
  const LOGIN_WINDOW_MS = 60 * 60 * 1000;
  const LOGIN_SHORT_WINDOW_MS = 10 * 60 * 1000;
  const LOGIN_SHORT_LIMIT = 5;
  const LOGIN_LONG_LIMIT = 15;
  const LOGIN_SHORT_BLOCK_MS = 10 * 60 * 1000;
  const LOGIN_LONG_BLOCK_MS = 60 * 60 * 1000;

  function getLoginClientKey(req) {
    return String(req.ip || "unknown");
  }

  function getLoginAttemptState(key, now) {
    const existing = loginAttempts.get(key) || {
      failures: [],
      blockedUntil: 0
    };

    existing.failures = existing.failures.filter(
      (timestamp) => now - timestamp < LOGIN_WINDOW_MS
    );

    if (existing.blockedUntil && now >= existing.blockedUntil) {
      existing.blockedUntil = 0;
    }

    if (existing.failures.length === 0 && !existing.blockedUntil) {
      loginAttempts.delete(key);
      return null;
    }

    loginAttempts.set(key, existing);
    return existing;
  }

  function loginRateLimit(req, res, next) {
    const key = getLoginClientKey(req);
    const now = Date.now();
    const state = getLoginAttemptState(key, now);

    if (state?.blockedUntil && now < state.blockedUntil) {
      const retryAfter = Math.max(
        1,
        Math.ceil((state.blockedUntil - now) / 1000)
      );

      res.set("Retry-After", String(retryAfter));

      return res.status(429).json({
        success: false,
        error: "Too many login attempts. Please try again later."
      });
    }

    let recorded = false;

    res.on("finish", () => {
      if (recorded) return;
      recorded = true;

      const finishedAt = Date.now();

      if (res.statusCode === 401) {
        const current = getLoginAttemptState(key, finishedAt) || {
          failures: [],
          blockedUntil: 0
        };

        current.failures.push(finishedAt);

        const recentFailures = current.failures.filter(
          (timestamp) =>
            finishedAt - timestamp < LOGIN_SHORT_WINDOW_MS
        );

        if (recentFailures.length >= LOGIN_SHORT_LIMIT) {
          current.blockedUntil = Math.max(
            current.blockedUntil || 0,
            finishedAt + LOGIN_SHORT_BLOCK_MS
          );
        }

        if (current.failures.length >= LOGIN_LONG_LIMIT) {
          current.blockedUntil = Math.max(
            current.blockedUntil || 0,
            finishedAt + LOGIN_LONG_BLOCK_MS
          );
        }

        loginAttempts.set(key, current);
      } else if (res.statusCode === 200) {
        loginAttempts.delete(key);
      }
    });

    next();
  }

  const originalAppPost = app.post.bind(app);
  app.post = function (route, ...handlers) {
    if (isProtectedMutationRoute(route)) {
      handlers.unshift(csrfProtection);
    }

    if (route === "/api/studio/login") {
      handlers.unshift(loginRateLimit);
    }

    return originalAppPost(route, ...handlers);
  };

  const originalAppPut = app.put.bind(app);
  app.put = function (route, ...handlers) {
    if (isProtectedMutationRoute(route)) {
      handlers.unshift(csrfProtection);
    }

    return originalAppPut(route, ...handlers);
  };

  const originalAppDelete = app.delete.bind(app);
  app.delete = function (route, ...handlers) {
    if (isProtectedMutationRoute(route)) {
      handlers.unshift(csrfProtection);
    }

    return originalAppDelete(route, ...handlers);
  };

  app.use((req, res, next) => {
    const originalJson = res.json.bind(res);

    res.json = function (body) {
      if (
        res.statusCode >= 500 &&
        body &&
        typeof body === "object" &&
        body.error
      ) {
        body = {
          ...body,
          error: "Internal server error."
        };
      }

      return originalJson(body);
    };

    next();
  });
}

function installErrorHandler(app) {
  app.use((error, req, res, next) => {
    console.error("Unhandled server error:", error);

    if (res.headersSent) {
      return next(error);
    }

    if (req.path && req.path.startsWith("/api/")) {
      return res.status(500).json({
        error: "Internal server error."
      });
    }

    return res.status(500).send("Internal server error.");
  });
}

module.exports = {
  install,
  installErrorHandler
};
