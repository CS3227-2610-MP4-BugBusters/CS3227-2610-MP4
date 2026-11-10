import { act, cleanup, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { MarkdownView, MarkdownParserProvider, inThreadRunner } from "../src/index";
import { createWorkerRunner } from "../src/markdownRunner";
import type { MarkdownRunner, ParseOutcome, WorkerLike } from "../src/markdownRunner";
import * as limits from "../src/MarkdownView.limits";
import { handleParseRequest } from "../src/markdown.worker";

// Task 3.17 (design D16; MDR-10): rendered-text budget, autolink pre-check, O(1) aborts.

const flush = () => act(async () => void (await Promise.resolve()));
const BUDGET = 100_000;

const repro = (dest: string, refs: number) => "[x]: <" + dest + ">\n\n" + "[x] ".repeat(refs);
const WORKER_REPROS: Array<[string, string]> = [
  ["a-run", repro("a".repeat(9980), 2498)],
  ["ZWSP", repro("​".repeat(2000), 2498)],
  ["paren", repro("(".repeat(6000), 2498)],
];
// <= 4,000 characters, yet over 100,000 text characters once rendered.
const INTHREAD_REPROS: Array<[string, string]> = [
  ["a-run", repro("a".repeat(1000), 746)],
  ["ZWSP", repro("​".repeat(1000), 746)],
  ["paren", repro("(".repeat(1000), 746)],
];

class FakeWorker implements WorkerLike {
  static instances: FakeWorker[] = [];
  posted: Array<{ id: number; source: string; prefix: string }> = [];
  onmessage: ((ev: { data: unknown }) => void) | null = null;
  onerror: ((ev: unknown) => void) | null = null;
  constructor() {
    FakeWorker.instances.push(this);
  }
  postMessage(msg: unknown) {
    this.posted.push(msg as never);
  }
  terminate() {}
}

/** A worker that answers with the real handleParseRequest result. */
class RealWorker extends FakeWorker {
  postMessage(msg: unknown) {
    super.postMessage(msg);
    const reply = handleParseRequest(msg);
    queueMicrotask(() => this.onmessage?.({ data: reply }));
  }
}

const okRunner = (hast: unknown): MarkdownRunner => ({
  parse: () => Promise.resolve({ kind: "ok", hast }) as Promise<ParseOutcome>,
});
const textNode = (n: number) => ({ type: "text", value: "x".repeat(n) });
const para = (...kids: unknown[]) => ({ type: "element", tagName: "p", properties: {}, children: kids });

beforeEach(() => {
  FakeWorker.instances = [];
});
afterEach(cleanup);

describe("MDR-10 rendered text is bounded (3.17)", () => {
  it("expected-Red: limits exports MAX_TEXT_CHARS = 100000", () => {
    expect((limits as Record<string, unknown>).MAX_TEXT_CHARS).toBe(BUDGET);
  });

  it.each(WORKER_REPROS)("expected-Red: worker refuses the %s repro (ok:false)", (_n, source) => {
    expect(source.length).toBeLessThanOrEqual(limits.MAX_SOURCE_LENGTH);
    expect(handleParseRequest({ id: 5, source, prefix: "p-" })).toEqual({ id: 5, ok: false });
  });

  it.each(WORKER_REPROS)(
    "expected-Red: worker-path view shows the %s repro as plain text",
    async (_n, source) => {
      const runner = createWorkerRunner({ createWorker: () => new RealWorker() });
      const { container } = render(
        <MarkdownParserProvider runner={runner}>
          <MarkdownView source={source} />
        </MarkdownParserProvider>,
      );
      await flush();
      await flush();
      expect(container.querySelector("pre.text")?.textContent).toBe(source);
    },
    60000,
  );

  it.each(INTHREAD_REPROS)(
    "expected-Red: in-thread view shows the %s repro (<=4,000 chars) as plain text",
    async (_n, source) => {
      expect(source.length).toBeLessThanOrEqual(4000);
      const { container } = render(
        <MarkdownParserProvider runner={inThreadRunner}>
          <MarkdownView source={source} />
        </MarkdownParserProvider>,
      );
      await flush();
      expect(container.querySelector("pre.text")?.textContent).toBe(source);
    },
    60000,
  );

  it("expected-Red: hostile result with 3 x 40,000 text characters is refused", async () => {
    const source = "# Title\n\nsome text\n";
    const hast = { type: "root", children: [para(textNode(40000), textNode(40000), textNode(40000))] };
    const { container } = render(
      <MarkdownParserProvider runner={okRunner(hast)}>
        <MarkdownView source={source} />
      </MarkdownParserProvider>,
    );
    await flush();
    expect(container.querySelector("pre.text")?.textContent).toBe(source);
    expect(container.querySelector("p")).toBeNull();
  });

  it("guard: hostile-shaped result just under 100,000 characters still renders formatted", async () => {
    const hast = { type: "root", children: [para(textNode(40000), textNode(40000), textNode(19999))] };
    const { container } = render(
      <MarkdownParserProvider runner={okRunner(hast)}>
        <MarkdownView source="whatever" />
      </MarkdownParserProvider>,
    );
    await flush();
    expect(container.querySelector("pre.text")).toBeNull();
    expect(container.querySelector("p")?.textContent?.length).toBe(99999);
  });
});

describe("MDR-10 ordinary link references still format (3.17)", () => {
  const source =
    "# Report\n\nSee [docs] and [spec] for details.\n\n[docs]: https://example.com/docs\n[spec]: https://example.com/spec\n";

  it("guard: worker path renders formatted with the destination suffix", async () => {
    const runner = createWorkerRunner({ createWorker: () => new RealWorker() });
    const { container } = render(
      <MarkdownParserProvider runner={runner}>
        <MarkdownView source={source} />
      </MarkdownParserProvider>,
    );
    await flush();
    await flush();
    expect(container.querySelector("pre.text")).toBeNull();
    expect(container.querySelector("h1")?.textContent).toBe("Report");
    expect(container.textContent).toContain("https://example.com/docs");
  });

  it("guard: in-thread path renders formatted with the destination suffix", async () => {
    const { container } = render(
      <MarkdownParserProvider runner={inThreadRunner}>
        <MarkdownView source={source} />
      </MarkdownParserProvider>,
    );
    await flush();
    expect(container.querySelector("pre.text")).toBeNull();
    expect(container.querySelector("h1")?.textContent).toBe("Report");
    expect(container.textContent).toContain("https://example.com/docs");
  });
});

describe("MDR-10 autolink literal runs (3.17)", () => {
  it("expected-Red: www._ x800 in-thread finishes in under 250 ms", async () => {
    const source = "www._".repeat(800);
    const t0 = performance.now();
    render(
      <MarkdownParserProvider runner={inThreadRunner}>
        <MarkdownView source={source} />
      </MarkdownParserProvider>,
    );
    await flush();
    expect(performance.now() - t0).toBeLessThan(250);
  }, 60000);

  it("guard: a report with a handful of www.example.com mentions still renders formatted", async () => {
    const source = "# Links\n\n" + "Visit www.example.com today.\n\n".repeat(5);
    const { container } = render(
      <MarkdownParserProvider runner={inThreadRunner}>
        <MarkdownView source={source} />
      </MarkdownParserProvider>,
    );
    await flush();
    expect(container.querySelector("pre.text")).toBeNull();
    expect(container.querySelector("h1")?.textContent).toBe("Links");
  });
});

describe("MDR-10 cancellation cost (3.17)", () => {
  // Aborting must be O(1) per request. Absolute times shift on a loaded machine, so the test
  // measures scaling: aborting 8N queued requests must cost near 8x aborting N (about 64x if
  // each abort scans or shifts the queue). Both orders are timed: reverse is worst for a
  // linear search from the front (the decisive order), forward for removing from the front of an array.
  type Parse = (s: string, p: string, sig?: AbortSignal) => Promise<ParseOutcome>;
  // A pre-built reason: a bare abort() makes Node build a DOMException with a stack
  // trace per call, which would measure the platform, not the runner.
  const reason = "cancelled";
  function abortCost(n: number, reverse: boolean): number {
    const runner = createWorkerRunner({ createWorker: () => new FakeWorker(), timeoutMs: 600_000 });
    const parse = runner.parse as Parse;
    void runner.parse("held", "h-"); // never completes
    const ctls: AbortController[] = [];
    for (let i = 0; i < n; i++) {
      const c = new AbortController();
      ctls.push(c);
      void parse(`q${i}`, "q-", c.signal);
    }
    if (reverse) ctls.reverse();
    const t0 = performance.now();
    for (const c of ctls) c.abort(reason);
    return performance.now() - t0;
  }
  const best = (n: number, reverse: boolean) =>
    Math.min(abortCost(n, reverse), abortCost(n, reverse), abortCost(n, reverse));

  it("aborting queued requests scales linearly: 8x the requests cost under 20x, in both orders", () => {
    for (const reverse of [false, true]) {
      abortCost(5000, reverse); // warm up
      const small = Math.max(best(10000, reverse), 4); // floor: timer noise at a few ms
      const large = best(80000, reverse);
      expect(large / small, `reverse=${reverse} small=${small}ms large=${large}ms`).toBeLessThan(20);
    }
  }, 60000);

  it("20,000 queued aborts all fall back, only the held request is posted, and a later request still runs", async () => {
    const runner = createWorkerRunner({ createWorker: () => new FakeWorker(), timeoutMs: 600_000 });
    const parse = runner.parse as Parse;
    void runner.parse("held", "h-"); // never completes
    const ctls: AbortController[] = [];
    const queued: Array<Promise<ParseOutcome>> = [];
    for (let i = 0; i < 20000; i++) {
      const c = new AbortController();
      ctls.push(c);
      queued.push(parse(`q${i}`, "q-", c.signal));
    }
    for (const c of ctls) c.abort(reason);
    const outcomes = await Promise.all(queued);
    expect(outcomes.every((o) => o.kind === "fallback")).toBe(true);
    expect(FakeWorker.instances.flatMap((w) => w.posted).map((m) => m.source)).toEqual(["held"]);

    const later = runner.parse("later", "l-");
    const w = FakeWorker.instances[0];
    w.onmessage?.({ data: { id: w.posted[0].id, ok: true, hast: { type: "root", children: [] } } });
    await flush();
    await flush();
    expect(w.posted.map((m) => m.source)).toContain("later");
    w.onmessage?.({
      data: { id: w.posted[w.posted.length - 1].id, ok: true, hast: { type: "root", children: [] } },
    });
    await expect(later).resolves.toMatchObject({ kind: "ok" });
  }, 60000);
});
