import type { Root } from "hast";
import { exceedsRenderLimits, MAX_IN_THREAD_SOURCE } from "./MarkdownView.limits";
import { isHastRoot, toHast } from "./MarkdownView.pipeline";

// Off-main-thread parsing with a per-request time limit (MDR-10, design D11).

export type ParseOutcome = { kind: "ok"; hast: Root } | { kind: "fallback" };

export interface MarkdownRunner {
  /**
   * Parse `source`. Aborting `signal` before the request is sent drops it and
   * resolves "fallback" (design D13); a request already sent runs to the end.
   */
  parse(source: string, prefix: string, signal?: AbortSignal): Promise<ParseOutcome>;
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
  /**
   * "waiting" while queued; "sent" once `pump` takes it; "cancelled" when its
   * signal aborts while it waits, so `pump` skips it (design D16).
   */
  state: "waiting" | "sent" | "cancelled";
  resolve: (outcome: ParseOutcome) => void;
  /** Stops listening for the caller's abort signal. */
  release: () => void;
};

const FALLBACK: ParseOutcome = { kind: "fallback" };

/**
 * One worker, one request in flight, each request timed from when it is sent.
 * A timeout or worker error terminates the worker and falls back for that
 * request only; requests still waiting were never sent, so they stay queued
 * and go to a fresh worker (design D13). An aborted request that has not been
 * sent is marked cancelled in O(1) and never posted; `pump` skips it (D16).
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
  let pumping = false;

  function settle(outcome: ParseOutcome) {
    const current = inFlight;
    inFlight = null;
    clearTimeout(timer);
    timer = undefined;
    current?.resolve(outcome);
  }

  /**
   * Drop the current worker. Only the request in flight used up its attempt
   * and falls back; waiting requests are sent to a fresh worker by `pump`.
   */
  function abandonWorker() {
    const dead = worker;
    worker = null;
    if (dead !== null) {
      dead.onmessage = null;
      dead.onerror = null;
      dead.terminate();
    }
    settle(FALLBACK);
  }

  function onReply(from: WorkerLike, data: unknown) {
    // Late replies from a terminated worker or for another request are ignored.
    if (from !== worker || inFlight === null) return;
    if (typeof data !== "object" || data === null) return;
    const reply = data as Partial<{ id: unknown; ok: unknown; hast: unknown }>;
    if (reply.id !== inFlight.id) return;
    const hast = reply.hast;
    if (reply.ok === true && isHastRoot(hast)) {
      settle({ kind: "ok", hast });
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

  /**
   * Send the next waiting request. A loop, not recursion (design D14), so a
   * long queue that fails to send one request after another cannot overflow
   * the stack. Not re-entrant (design D15): a reply or error delivered
   * synchronously during `postMessage` calls `pump` again, which returns at
   * once; the running loop then sends the next request, so runs never nest
   * and requests go out in order.
   */
  function pump() {
    if (pumping) return;
    pumping = true;
    try {
      while (inFlight === null) {
        const next = queue.shift();
        if (next === undefined) return;
        if (next.state === "cancelled") continue;
        next.state = "sent";
        next.release();
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
          // No worker could be created or the request could not be sent. This
          // request falls back and the loop moves on to the next one.
          abandonWorker();
        }
      }
    } finally {
      pumping = false;
    }
  }

  return {
    parse(source, prefix, signal) {
      return new Promise<ParseOutcome>((resolve) => {
        if (signal?.aborted) {
          resolve(FALLBACK);
          return;
        }
        const pending: Pending = {
          id: nextId++,
          source,
          prefix,
          state: "waiting",
          resolve,
          release: () => signal?.removeEventListener("abort", onAbort),
        };
        function onAbort() {
          // Only a request still waiting is dropped; one in flight runs to the end.
          if (pending.state !== "waiting") return;
          pending.state = "cancelled";
          pending.release();
          resolve(FALLBACK);
        }
        signal?.addEventListener("abort", onAbort);
        queue.push(pending);
        pump();
      });
    },
  };
}

/**
 * Parses on the calling thread, behind the MDR-05 pre-check and the in-thread
 * input cap (design D15). MarkdownView recognises this runner and renders
 * synchronously instead of calling it.
 */
export const inThreadRunner: MarkdownRunner = {
  parse(source, prefix) {
    if (source.length > MAX_IN_THREAD_SOURCE || exceedsRenderLimits(source)) {
      return Promise.resolve(FALLBACK);
    }
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
