import { defineConfig } from "vite";
import { markdownVite } from "../shared/markdown/vite-plugin.mjs";

export default defineConfig({
  base: "/developer/",
  plugins: [markdownVite()],
  server: {
    fs: { allow: [".."] },
    proxy: {
      "/api": "http://127.0.0.1:8080",
      "/account": "http://127.0.0.1:8080",
    },
  },
  resolve: {
    dedupe: ["react", "react-dom"],
  },
});
