// Vite plugin for apps that bundle @mp4/markdown (MDR-10, design D11).
//
// Vite resolves the `browser` export condition for every bundle, including Web
// Worker bundles. `decode-named-character-reference` (used by micromark) maps
// `browser` to a DOM build that calls `document.createElement` when the module
// loads. Workers have no `document`, so the Markdown worker would throw on load
// and every view would stay plain text. This plugin points that package at its
// DOM-free build (its `worker`/`default` export) for the page and the worker
// alike. Both builds decode the same HTML named character references.
//
// Usage in an app's vite.config:
//   import { markdownVite } from "@mp4/markdown/vite-plugin.mjs";
//   export default defineConfig({ plugins: [react(), markdownVite()] });

import { createRequire } from "node:module";

const require = createRequire(import.meta.url);

/** @returns {import("vite").Plugin} */
export function markdownVite() {
  // Node's require conditions select the package's DOM-free `default` export.
  const domFree = require.resolve("decode-named-character-reference");
  return {
    name: "mp4-markdown",
    config() {
      return {
        resolve: {
          alias: [{ find: /^decode-named-character-reference$/, replacement: domFree }],
        },
      };
    },
  };
}
