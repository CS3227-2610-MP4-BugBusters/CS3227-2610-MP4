import { exceedsRenderLimits } from "./MarkdownView.limits";
import { toHast } from "./MarkdownView.pipeline";
import type { ParseReply } from "./markdownRunner";

// Markdown parse worker (MDR-10, design D11). Created by the runner with
// `new Worker(new URL("./markdown.worker.ts", import.meta.url), { type: "module" })`.
// Receives `{ id, source, prefix }` and replies `{ id, ok: true, hast }`, or
// `{ id, ok: false }` when the source is over the MDR-05 limits or parsing fails.

type WorkerScope = {
  onmessage: ((ev: { data: unknown }) => void) | null;
  postMessage(message: ParseReply): void;
};

const scope = self as unknown as WorkerScope;

scope.onmessage = (ev) => {
  const data = (typeof ev.data === "object" && ev.data !== null ? ev.data : {}) as Partial<
    Record<"id" | "source" | "prefix", unknown>
  >;
  const { id, source, prefix } = data;
  if (typeof id !== "number") return;
  let reply: ParseReply;
  try {
    if (typeof source !== "string" || typeof prefix !== "string" || exceedsRenderLimits(source)) {
      reply = { id, ok: false };
    } else {
      reply = { id, ok: true, hast: toHast(source, prefix) };
    }
  } catch {
    reply = { id, ok: false };
  }
  scope.postMessage(reply);
};
