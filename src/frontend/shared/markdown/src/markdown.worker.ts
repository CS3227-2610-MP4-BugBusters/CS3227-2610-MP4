import { exceedsRenderLimits } from "./MarkdownView.limits";
import { countHastNodes, MAX_HAST_NODES, toHast } from "./MarkdownView.pipeline";
import type { ParseReply } from "./markdownRunner";

// Markdown parse worker (MDR-10, design D11). Created by the runner with
// `new Worker(new URL("./markdown.worker.ts", import.meta.url), { type: "module" })`.
// Receives `{ id, source, prefix }` and replies `{ id, ok: true, hast }`, or
// `{ id, ok: false }` when the source is over the MDR-05 limits, parsing fails
// or the output has more than MAX_HAST_NODES nodes (design D13).

type WorkerScope = {
  onmessage: ((ev: { data: unknown }) => void) | null;
  postMessage(message: ParseReply): void;
};

/** The reply for one request, or null when it has no numeric id. */
export function handleParseRequest(data: unknown): ParseReply | null {
  const fields = (typeof data === "object" && data !== null ? data : {}) as Partial<
    Record<"id" | "source" | "prefix", unknown>
  >;
  const { id, source, prefix } = fields;
  if (typeof id !== "number") return null;
  try {
    if (typeof source !== "string" || typeof prefix !== "string" || exceedsRenderLimits(source)) {
      return { id, ok: false };
    }
    const hast = toHast(source, prefix);
    if (countHastNodes(hast, MAX_HAST_NODES) > MAX_HAST_NODES) return { id, ok: false };
    return { id, ok: true, hast };
  } catch {
    return { id, ok: false };
  }
}

const scope = self as unknown as WorkerScope;

scope.onmessage = (ev) => {
  const reply = handleParseRequest(ev.data);
  if (reply !== null) scope.postMessage(reply);
};
