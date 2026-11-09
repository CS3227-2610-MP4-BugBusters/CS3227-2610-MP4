import type { Root } from "hast";
import { exceedsRenderLimits } from "./MarkdownView.limits";
import { toHast } from "./MarkdownView.pipeline";

// Off-main-thread parsing with a per-request time limit (MDR-10, design D11).

export type ParseOutcome = { kind: "ok"; hast: Root } | { kind: "fallback" };

export interface MarkdownRunner {
  parse(source: string, prefix: string): Promise<ParseOutcome>;
}

/** The part of `Worker` the runner uses, so tests can inject a fake. */
export interface WorkerLike {
  postMessage(message: unknown): void;
  terminate(): void;
  onmessage: ((ev: { data: unknown }) => void) | null;
  onerror: ((ev: unknown) => void) | null;
}

/** Request posted to the worker. */
export type ParseRequest = { id: number; source: string; prefix: string };
/** Reply posted by the worker. */
export type ParseReply = { id: number; ok: true; hast: Root } | { id: number; ok: false };

export interface WorkerRunnerOptions {
  createWorker: () => WorkerLike;
  /** Time a single request may run once it has been sent. */
  timeoutMs?: number;
}

type Pending = ParseRequest & {
  resolve: (outcome: ParseOutcome) => void;
  /** Set once the request has been moved to a fresh worker. */
  retried: boolean;
};

const FALLBACK: ParseOutcome = { kind: "fallback" };

/**
 * One worker, one request in flight, each request timed from when it is sent.
 * A timeout or worker error terminates the worker and falls back for that
 * request; requests still waiting are retried once on a fresh worker.
 */
export function createWorkerRunner({
  createWorker,
  timeoutMs = 1000,
}: WorkerRunnerOptions): MarkdownRunner {
  const queue: Pending[] = [];
  let worker: WorkerLike | null = null;
  let inFlight: Pending | null = null;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let nextId = 1;

  function settle(outcome: ParseOutcome) {
    const current = inFlight;
    inFlight = null;
    clearTimeout(timer);
    timer = undefined;
    current?.resolve(outcome);
  }

  /** Drop the current worker; waiting requests get one retry on a fresh one. */
  function abandonWorker() {
    const dead = worker;
    worker = null;
    if (dead !== null) {
      dead.onmessage = null;
      dead.onerror = null;
      dead.terminate();
    }
    settle(FALLBACK);
    for (let i = queue.length - 1; i >= 0; i--) {
      if (queue[i].retried) queue.splice(i, 1)[0].resolve(FALLBACK);
      else queue[i].retried = true;
    }
  }

  function onReply(from: WorkerLike, data: unknown) {
    // Late replies from a terminated worker or for another request are ignored.
    if (from !== worker || inFlight === null) return;
    if (typeof data !== "object" || data === null) return;
    const reply = data as Partial<{ id: unknown; ok: unknown; hast: unknown }>;
    if (reply.id !== inFlight.id) return;
    const hast = reply.hast;
    if (reply.ok === true && typeof hast === "object" && hast !== null) {
      settle({ kind: "ok", hast: hast as Root });
    } else {
      settle(FALLBACK);
    }
    pump();
  }

  function startWorker(): WorkerLike {
    const created = createWorker();
    created.onmessage = (ev) => onReply(created, ev.data);
    created.onerror = () => {
      if (created !== worker) return;
      abandonWorker();
      pump();
    };
    return created;
  }

  function pump() {
    if (inFlight !== null) return;
    const next = queue.shift();
    if (next === undefined) return;
    inFlight = next;
    try {
      worker ??= startWorker();
      const request: ParseRequest = { id: next.id, source: next.source, prefix: next.prefix };
      timer = setTimeout(() => {
        abandonWorker();
        pump();
      }, timeoutMs);
      worker.postMessage(request);
    } catch {
      // No worker could be created or the request could not be sent.
      abandonWorker();
      pump();
    }
  }

  return {
    parse(source, prefix) {
      return new Promise<ParseOutcome>((resolve) => {
        queue.push({ id: nextId++, source, prefix, resolve, retried: false });
        pump();
      });
    },
  };
}

/**
 * Parses on the calling thread, behind the MDR-05 pre-check. MarkdownView
 * recognises this runner and renders synchronously instead of calling it.
 */
export const inThreadRunner: MarkdownRunner = {
  parse(source, prefix) {
    if (exceedsRenderLimits(source)) return Promise.resolve(FALLBACK);
    try {
      return Promise.resolve({ kind: "ok", hast: toHast(source, prefix) });
    } catch {
      return Promise.resolve(FALLBACK);
    }
  },
};

let sharedRunner: MarkdownRunner | undefined;

/** The page-wide worker runner, created on first use. */
function sharedWorkerRunner(): MarkdownRunner {
  sharedRunner ??= createWorkerRunner({
    createWorker: () =>
      new Worker(new URL("./markdown.worker.ts", import.meta.url), {
        type: "module",
      }) as WorkerLike,
  });
  return sharedRunner;
}

/** Worker runner where Web Workers exist, else in-thread (jsdom, SSR). */
export function defaultRunner(): MarkdownRunner {
  return typeof Worker !== "undefined" ? sharedWorkerRunner() : inThreadRunner;
}
