const fs = require("fs");
const path = require("path");
const Module = require("module");

const visitorPath = path.resolve(__dirname, "visitor-server.js");
const serverPath = path.resolve(__dirname, "server.js");

const originalReadFileSync = fs.readFileSync;
fs.readFileSync = function (file, ...args) {
  let source = originalReadFileSync.call(this, file, ...args);
  const resolved = path.resolve(String(file));

  if (resolved === serverPath) {
    if (Buffer.isBuffer(source)) source = source.toString("utf8");

    source = source.replace(
      "const expectedOrigin = forwardedProto + :// + host;",
      'const expectedOrigin = forwardedProto + "://" + host;'
    );
  }

  return source;
};

let visitorSource = originalReadFileSync(visitorPath, "utf8");

visitorSource = visitorSource.replace(
  "const expectedOrigin = `${forwardedProto}://${host}`;",
  'const expectedOrigin = forwardedProto + "://" + host;'
);

if (visitorSource.includes("const expectedOrigin = `${forwardedProto}://${host}`;")) {
  throw new Error("Could not patch visitor-server.js syntax before startup.");
}

module._compile(visitorSource, visitorPath);
