const fs = require("fs");
const path = require("path");
const Module = require("module");

const visitorPath = path.resolve(__dirname, "visitor-server.js");

const visitorSource = fs.readFileSync(visitorPath, "utf8");

module._compile(visitorSource, visitorPath);
