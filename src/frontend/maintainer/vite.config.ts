import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import { loadEnv } from "vite";
export default defineConfig(({ mode }) => ({
  plugins: [react()],
  server: {
    proxy: {
      "/api": {
        target: loadEnv(mode, ".", "MP4_").MP4_AUTH_ORIGIN ?? "http://127.0.0.1:8080",
        changeOrigin: false,
      },
    },
  },
  test: { environment: "jsdom", globals: true, restoreMocks: true },
}));
