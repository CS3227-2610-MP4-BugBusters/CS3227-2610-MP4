import { createRoot } from "react-dom/client";
import {
  createWorkerRunner,
  MarkdownParserProvider,
  type WorkerLike,
} from "@mp4/markdown";
import { App } from "./App";
import { createHttpQueries } from "./http";
import { createHttpAuthActions } from "./auth-client";
import { withHttpActions } from "./action-http";
import "./style.css";
const markdownRunner = createWorkerRunner({
  createWorker: () =>
    new Worker(
      new URL("../../shared/markdown/src/markdown.worker.ts", import.meta.url),
      { type: "module" },
    ) as unknown as WorkerLike,
});

async function start() {
  const synthetic = import.meta.env.DEV && import.meta.env.MODE === "demo";
  const queries = synthetic
    ? (await import("./demo")).createDemoQueries(undefined, true, true)
    : withHttpActions(createHttpQueries());
  createRoot(document.getElementById("root")!).render(
    <MarkdownParserProvider runner={markdownRunner}>
      <App
        queries={queries}
        synthetic={synthetic}
        authActions={synthetic ? undefined : createHttpAuthActions()}
      />
    </MarkdownParserProvider>,
  );
}
void start();
