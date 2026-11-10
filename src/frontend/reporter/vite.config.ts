import { defineConfig } from "vite";
// The shared package's plugin points `decode-named-character-reference` at its
// DOM-free build so the Markdown worker loads (reporter-markdown-reports MDR-10).
import { markdownVite } from "../shared/markdown/vite-plugin.mjs";

export default defineConfig({
  base: "/reporter/",
  plugins: [markdownVite()],
  resolve: {
    // @mp4/markdown is a file: dependency with its own node_modules; use one React.
    dedupe: ["react", "react-dom"],
  },
  server: {
    fs: { allow: [".."] },
    proxy: {
      "/api": "http://127.0.0.1:8080",
    },
  },
  test: {
    environment: "jsdom",
  },
});
