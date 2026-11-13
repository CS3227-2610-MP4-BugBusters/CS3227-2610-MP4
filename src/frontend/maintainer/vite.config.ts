import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import { loadEnv } from "vite";
// @ts-expect-error The shared Vite plugin is a runtime-only JavaScript module.
import { markdownVite } from "../shared/markdown/vite-plugin.mjs";
export default defineConfig(({ mode }) => ({
  plugins: [react(), markdownVite()],
  server: {
    fs: { allow: [".."] },
    proxy: {
      "/account": {
        target: loadEnv(mode, ".", "MP4_").MP4_AUTH_ORIGIN ?? "http://127.0.0.1:8080",
        changeOrigin: false,
      },
      "/developer": { target: "http://127.0.0.1:5174", ws: true },
      "/reporter": { target: "http://127.0.0.1:5175", ws: true },
      "/api": {
        target: loadEnv(mode, ".", "MP4_").MP4_AUTH_ORIGIN ?? "http://127.0.0.1:8080",
        changeOrigin: false,
      },
    },
  },
  resolve: { dedupe: ["react", "react-dom"] },
  test: { environment: "jsdom", globals: true, restoreMocks: true },
}));
