import { createWorkerRunner, MarkdownParserProvider, type WorkerLike } from "@mp4/markdown";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import "./style.css";

// Parse Markdown off the main thread (reporter-markdown-reports D11). The
// relative URL lets Vite emit the shared worker as its own chunk.
const runner = createWorkerRunner({
  createWorker: () =>
    new Worker(new URL("../../shared/markdown/src/markdown.worker.ts", import.meta.url), {
      type: "module",
    }) as unknown as WorkerLike,
});

createRoot(document.getElementById("root")!).render(
  <MarkdownParserProvider runner={runner}>
    <App />
  </MarkdownParserProvider>,
);
