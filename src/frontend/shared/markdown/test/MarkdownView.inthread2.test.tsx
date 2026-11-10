import { act, cleanup, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MarkdownView, MarkdownParserProvider } from "../src/index";
import { createWorkerRunner } from "../src/markdownRunner";
import type { MarkdownRunner, ParseOutcome, WorkerLike } from "../src/markdownRunner";
import * as limits from "../src/MarkdownView.limits";
import { MAX_HAST_NODES, toHast } from "../src/MarkdownView.pipeline";

// Task 3.16 (design D15; MDR-10): the in-thread input cap, hostile worker result
// shapes, whitespace in language classes and a non-re-entrant pump.

vi.mock("../src/MarkdownView.pipeline", async () => {
  const actual = await vi.importActual<typeof import("../src/MarkdownView.pipeline")>(
    "../src/MarkdownView.pipeline",
  );
  return { ...actual, toHast: vi.fn(actual.toHast) };
});

const CAP = 4000;
const flush = () => act(async () => void (await Promise.resolve()));

function okRunner(hast: unknown): MarkdownRunner {
  return { parse: () => Promise.resolve({ kind: "ok", hast }) as Promise<ParseOutcome> };
}

beforeEach(() => {
  vi.mocked(toHast).mockClear();
});
afterEach(() => {
  cleanup();
});

const slow: Array<[string, string]> = [
  ["lazy list lines", "- a\n" + "b\n".repeat(9990)],
  ["lazy footnote lines", "[^a]: a\n" + "b\n".repeat(9980)],
  ["processing instruction run", "a <?".repeat(5000)],
  ["unclosed comment run", "a<!--".repeat(4000)],
  ["declaration run", "a <!X".repeat(4000)],
  ["CDATA run", "a <![CDATA[".repeat(1800)],
  ["bracket run", "]".repeat(20000)],
  ["link label run", "[a]".repeat(6000)],
];

describe("MDR-10 in-thread input cap (3.16)", () => {
  it("expected-Red: limits exports MAX_IN_THREAD_SOURCE = 4000", () => {
    expect((limits as Record<string, unknown>).MAX_IN_THREAD_SOURCE).toBe(CAP);
  });

  it.each(slow)(
    "expected-Red: %s over 4,000 chars is plain text in under 1 s and never parsed",
    (_name, source) => {
      expect(source.length).toBeGreaterThan(CAP);
      const t0 = performance.now();
      const { container } = render(<MarkdownView source={source} />);
      const ms = performance.now() - t0;
      expect(toHast).not.toHaveBeenCalled();
      expect(container.querySelector("pre.text")?.textContent).toBe(source);
      expect(ms).toBeLessThan(1000);
    },
    60000,
  );

  it.each(slow)(
    "guard: %s truncated to exactly 4,000 chars renders in under 1 s",
    (_name, source) => {
      const short = source.slice(0, CAP);
      expect(short.length).toBe(CAP);
      const t0 = performance.now();
      render(<MarkdownView source={short} />);
      // Either formatted or plain text is fine; only the time is bounded.
      expect(performance.now() - t0).toBeLessThan(1000);
    },
    60000,
  );

  const ordinary = (length: number) => {
    const head = "# Title\n\n";
    const body = "word ".repeat(Math.ceil(length / 5));
    return (head + body).slice(0, length - 1) + "x";
  };

  it("guard: a 4,000-character ordinary document still renders formatted", () => {
    const source = ordinary(CAP);
    expect(source.length).toBe(CAP);
    const { container } = render(<MarkdownView source={source} />);
    expect(container.querySelector("pre.text")).toBeNull();
    expect(container.querySelector("h1")?.textContent).toBe("Title");
    expect(toHast).toHaveBeenCalled();
  });

  it("expected-Red: a 4,001-character ordinary document is plain text and never parsed", () => {
    const source = ordinary(CAP + 1);
    expect(source.length).toBe(CAP + 1);
    const { container } = render(<MarkdownView source={source} />);
    expect(container.querySelector("pre.text")?.textContent).toBe(source);
    expect(container.querySelector("h1")).toBeNull();
    expect(toHast).not.toHaveBeenCalled();
  });
});

describe("MDR-10 hostile worker result shapes stay cheap (3.16)", () => {
  const source = "# Title\n\nsome text\n";

  function sparse(length: number) {
    const children: unknown[] = [];
    children.length = length;
    return children;
  }

  function countingProxy(length: number, counter: { reads: number }) {
    const target: unknown[] = [];
    target.length = length;
    return new Proxy(target, {
      get(t, key, receiver) {
        if (typeof key === "string" && /^\d+$/.test(key)) {
          // Abort a runaway loop early; the assertion below then fails clearly.
          if (++counter.reads > 200_000) throw new Error("too many reads");
        }
        return Reflect.get(t, key, receiver);
      },
    });
  }

  async function renderHostile(hast: unknown) {
    const t0 = performance.now();
    const view = render(
      <MarkdownParserProvider runner={okRunner(hast)}>
        <MarkdownView source={source} />
      </MarkdownParserProvider>,
    );
    await flush();
    return { container: view.container, ms: performance.now() - t0 };
  }

  const expectBounded = (container: HTMLElement) => {
    // Plain text or a bounded result, never the claimed number of nodes.
    const pre = container.querySelector("pre.text");
    if (pre !== null) expect(pre.textContent).toBe(source);
    expect((container.textContent ?? "").length).toBeLessThan(1000);
  };

  it.each([1e8, 2 ** 32 - 1])(
    "expected-Red: children as a sparse array of length %i finishes in under 100 ms",
    async (length) => {
      const { container, ms } = await renderHostile({ type: "root", children: sparse(length) });
      expectBounded(container);
      expect(ms).toBeLessThan(100);
    },
    60000,
  );

  it.each([1e8, 2 ** 32 - 1])(
    "expected-Red: a structuredClone'd sparse array of length %i finishes in under 100 ms",
    async (length) => {
      const cloned = structuredClone({ type: "root", children: sparse(length) });
      const { container, ms } = await renderHostile(cloned);
      expectBounded(container);
      expect(ms).toBeLessThan(100);
    },
    60000,
  );

  it("expected-Red: a sparse array nested inside an element is also bounded", async () => {
    const hast = {
      type: "root",
      children: [{ type: "element", tagName: "p", properties: {}, children: sparse(1e8) }],
    };
    const { container, ms } = await renderHostile(hast);
    expectBounded(container);
    expect(ms).toBeLessThan(100);
  }, 60000);

  it("expected-Red: a Proxy array with a huge length is never read past the node budget", async () => {
    const counter = { reads: 0 };
    const { container, ms } = await renderHostile({
      type: "root",
      children: countingProxy(1e8, counter),
    });
    expectBounded(container);
    expect(counter.reads).toBeLessThanOrEqual(MAX_HAST_NODES);
    expect(ms).toBeLessThan(100);
  }, 60000);

  it("expected-Red: a nested Proxy array with a huge length is never read past the node budget", async () => {
    const counter = { reads: 0 };
    const hast = {
      type: "root",
      children: [
        {
          type: "element",
          tagName: "p",
          properties: {},
          children: countingProxy(2 ** 32 - 1, counter),
        },
      ],
    };
    const { container, ms } = await renderHostile(hast);
    expectBounded(container);
    expect(counter.reads).toBeLessThanOrEqual(MAX_HAST_NODES);
    expect(ms).toBeLessThan(100);
  }, 60000);
});

describe("MDR-10 language classes never carry whitespace (3.16)", () => {
  async function renderCode(className: string[]) {
    const hast = {
      type: "root",
      children: [
        {
          type: "element",
          tagName: "pre",
          properties: {},
          children: [
            {
              type: "element",
              tagName: "code",
              properties: { className },
              children: [{ type: "text", value: "let a" }],
            },
          ],
        },
      ],
    };
    const view = render(
      <MarkdownParserProvider runner={okRunner(hast)}>
        <MarkdownView source="whatever" />
      </MarkdownParserProvider>,
    );
    await flush();
    return view.container;
  }

  it.each([
    ["form feed", "language-y\fsr-only"],
    ["space", "language-y sr-only"],
    ["tab", "language-y\tsr-only"],
    ["newline", "language-y\nsr-only"],
    ["carriage return", "language-y\rsr-only"],
    ["empty suffix", "language-"],
  ])("expected-Red: %s in the class name is dropped", async (_name, cls) => {
    const container = await renderCode([cls]);
    expect(container.querySelector("code")).not.toBeNull();
    expect(container.querySelector(".sr-only")).toBeNull();
    const code = container.querySelector("code")!;
    expect(code.classList.contains("sr-only")).toBe(false);
    expect(code.getAttribute("class") ?? "").not.toMatch(/[\s]/);
    expect(code.getAttribute("class") ?? "").not.toBe("language-");
  });

  it("guard: a normal language-js class is kept", async () => {
    const container = await renderCode(["language-js"]);
    expect(container.querySelector("code")?.getAttribute("class")).toBe("language-js");
  });
});

describe("MDR-10 worker runner pump is not re-entrant (3.16)", () => {
  type Posted = { id: number };
  const emptyRoot = { type: "root", children: [] };

  /** Tracks how deeply postMessage calls nest, whatever the cause. */
  const probe = { depth: 0, maxDepth: 0 };

  class FakeWorker implements WorkerLike {
    static instances: FakeWorker[] = [];
    static syncReply = false;
    static errorOnId = 0;
    static errored = false;
    posted: Posted[] = [];
    onmessage: ((ev: { data: unknown }) => void) | null = null;
    onerror: ((ev: unknown) => void) | null = null;
    constructor() {
      FakeWorker.instances.push(this);
    }
    postMessage(msg: unknown) {
      const { id } = msg as Posted;
      this.posted.push({ id });
      probe.depth++;
      probe.maxDepth = Math.max(probe.maxDepth, probe.depth);
      try {
        if (id === FakeWorker.errorOnId && !FakeWorker.errored) {
          FakeWorker.errored = true;
          this.onerror?.({});
        } else if (FakeWorker.syncReply && id !== 1) {
          this.onmessage?.({ data: { id, ok: true, hast: emptyRoot } });
        }
      } finally {
        probe.depth--;
      }
    }
    terminate() {}
  }

  beforeEach(() => {
    FakeWorker.instances = [];
    FakeWorker.syncReply = false;
    FakeWorker.errorOnId = 0;
    FakeWorker.errored = false;
    probe.depth = 0;
    probe.maxDepth = 0;
  });

  const postedIds = () => FakeWorker.instances.flatMap((w) => w.posted.map((p) => p.id));

  it("expected-Red: synchronous replies never nest pump runs, and order is kept", async () => {
    FakeWorker.syncReply = true;
    const runner = createWorkerRunner({ createWorker: () => new FakeWorker(), timeoutMs: 60_000 });
    // Request 1 is held (the fake does not reply), so the others queue behind it.
    const first = runner.parse("held", "p-");
    const queued: Array<Promise<ParseOutcome>> = [];
    const N = 2000;
    for (let i = 0; i < N; i++) queued.push(runner.parse(`q${i}`, "p-"));
    expect(postedIds()).toEqual([1]);

    const worker = FakeWorker.instances[0];
    worker.onmessage?.({ data: { id: 1, ok: true, hast: emptyRoot } });

    const outcomes = await Promise.all([first, ...queued]);
    expect(outcomes.every((o) => o.kind === "ok")).toBe(true);
    // Each request posted exactly once, in order.
    const ids = postedIds();
    expect(ids).toEqual(Array.from({ length: N + 1 }, (_, i) => i + 1));
    // A re-entrant pump would nest postMessage calls (and grow the stack).
    expect(probe.maxDepth).toBe(1);
  }, 60000);

  it("expected-Red: a postMessage that synchronously fires onerror does not nest pump runs", async () => {
    FakeWorker.errorOnId = 2;
    FakeWorker.syncReply = true;
    const runner = createWorkerRunner({ createWorker: () => new FakeWorker(), timeoutMs: 60_000 });
    // Request 1 is held, so 2, 3 and 4 queue behind it.
    const first = runner.parse("held", "p-");
    const rest = [2, 3, 4].map((n) => runner.parse(`r${n}`, "p-"));
    FakeWorker.instances[0].onmessage?.({ data: { id: 1, ok: true, hast: emptyRoot } });
    const [o1, o2, o3, o4] = await Promise.all([first, ...rest]);
    expect(o1.kind).toBe("ok");
    // The request whose send failed falls back; the others are untouched.
    expect(o2.kind).toBe("fallback");
    expect(o3.kind).toBe("ok");
    expect(o4.kind).toBe("ok");
    const ids = postedIds();
    expect(new Set(ids).size).toBe(ids.length);
    expect(ids).toEqual([1, 2, 3, 4]);
    expect(probe.maxDepth).toBe(1);
  }, 60000);
});
