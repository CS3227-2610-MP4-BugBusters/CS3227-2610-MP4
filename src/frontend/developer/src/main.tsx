import { createWorkerRunner, MarkdownParserProvider, type WorkerLike } from "@mp4/markdown";
import { createRoot } from "react-dom/client";
import { DeveloperApp } from "./DeveloperApp";
import "./style.css";

const runner = createWorkerRunner({
  createWorker: () =>
    new Worker(new URL("../../shared/markdown/src/markdown.worker.ts", import.meta.url), {
      type: "module",
    }) as unknown as WorkerLike,
});

createRoot(document.getElementById("root")!).render(
  <MarkdownParserProvider runner={runner}>
    <DeveloperApp />
  </MarkdownParserProvider>,
);
