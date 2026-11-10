// Vite worker build smoke test (MDR-10, design D11).
//
// Builds smoke/vite/main.ts with the real Vite and the package's plugin, then
// loads the emitted Markdown worker chunk in plain Node, which has no
// `document`, exactly like a browser worker. Before the plugin existed the chunk
// threw `document is not defined` on load (3.17 security review, F1).
// Passing `--without-plugin` builds without it, to show the check fails then.

import { build } from "vite";
import { mkdtempSync, readdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { markdownVite } from "../vite-plugin.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const withPlugin = !process.argv.includes("--without-plugin");
const outDir = mkdtempSync(path.join(tmpdir(), "mp4-markdown-vite-smoke-"));

function fail(message) {
  console.error(`vite smoke: FAIL - ${message}`);
  process.exitCode = 1;
}

try {
  await build({
    root,
    configFile: false,
    logLevel: "warn",
    plugins: withPlugin ? [markdownVite()] : [],
    worker: { format: "es" },
    build: {
      outDir,
      emptyOutDir: true,
      minify: false,
      lib: { entry: path.join(root, "smoke/vite/main.ts"), formats: ["es"], fileName: "main" },
    },
  });

  const files = readdirSync(outDir, { recursive: true }).map(String);
  const chunk = files.find((name) => /markdown\.worker.*\.js$/.test(name));
  if (!chunk) {
    fail(`no worker chunk emitted (files: ${files.join(", ")})`);
  } else {
    const replies = [];
    globalThis.self = { onmessage: null, postMessage: (reply) => replies.push(reply) };
    let loadError = null;
    try {
      await import(pathToFileURL(path.join(outDir, chunk)).href);
    } catch (error) {
      loadError = error;
    }
    if (loadError) {
      fail(`worker chunk threw on load: ${loadError}`);
    } else if (typeof self.onmessage !== "function") {
      fail("worker chunk did not install onmessage");
    } else {
      self.onmessage({ data: { id: 1, source: "**bold** &copy; &amp; &notanentity;", prefix: "user-content-smoke-" } });
      const reply = replies[0];
      const text = JSON.stringify(reply?.hast ?? null);
      if (!reply || reply.id !== 1 || reply.ok !== true) {
        fail(`unexpected reply ${JSON.stringify(reply)}`);
      } else if (!text.includes('"strong"') || !text.includes("© &") || !text.includes("&notanentity;")) {
        fail(`named references or formatting not decoded as expected: ${text}`);
      } else {
        console.log(`vite smoke: PASS (${chunk} loads without document and parses Markdown)`);
      }
    }
  }
} finally {
  rmSync(outDir, { recursive: true, force: true });
}
