const { defineConfig } = require("@playwright/test");

module.exports = defineConfig({
  testDir: ".",
  testMatch: "*.spec.js",
  workers: 1,
  use: { baseURL: process.env.MP4_BROWSER_ORIGIN, browserName: "chromium" },
});
