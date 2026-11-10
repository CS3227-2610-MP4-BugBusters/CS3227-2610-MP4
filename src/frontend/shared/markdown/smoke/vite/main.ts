// Entry for the Vite worker build smoke test (scripts/vite-smoke.mjs). It only
// references the worker the same way an app does, so Vite emits a worker chunk.
const worker = new Worker(new URL("../../src/markdown.worker.ts", import.meta.url), { type: "module" });
export default worker;
