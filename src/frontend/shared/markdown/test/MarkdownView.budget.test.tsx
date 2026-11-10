import { act, cleanup, render } from "@testing-library/react";
import type { Root } from "hast";
import { toJsxRuntime } from "hast-util-to-jsx-runtime";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MarkdownView, MarkdownParserProvider, inThreadRunner } from "../src/index";
import { createWorkerRunner } from "../src/markdownRunner";
import type { MarkdownRunner, ParseOutcome, WorkerLike } from "../src/markdownRunner";
import * as limits from "../src/MarkdownView.limits";

// Task 3.12: the worker result is bounded and untrusted (MDR-10, MDR-05, design D13).

vi.mock("hast-util-to-jsx-runtime", async () => {
  const actual = await vi.importActual<typeof import("hast-util-to-jsx-runtime")>(
    "hast-util-to-jsx-runtime",
  );
  return { ...actual, toJsxRuntime: vi.fn(actual.toJsxRuntime) };
});

vi.mock("../src/MarkdownView.pipeline", async () => {
  const actual = await vi.importActual<typeof import("../src/MarkdownView.pipeline")>(
    "../src/MarkdownView.pipeline",
  );
  return { ...actual, toHast: vi.fn(actual.toHast) };
});

async function realToHast(source: string, prefix: string): Promise<Root> {
  const actual = await vi.importActual<typeof import("../src/MarkdownView.pipeline")>(
    "../src/MarkdownView.pipeline",
  );
  return actual.toHast(source, prefix);
}

type Posted = { id: number; source: string; prefix: string };

class FakeWorker implements WorkerLike {
  static instances: FakeWorker[] = [];
  posted: Posted[] = [];
  terminated = false;
  onmessage: ((ev: { data: unknown }) => void) | null = null;
  onerror: ((ev: unknown) => void) | null = null;
  constructor() {
    FakeWorker.instances.push(this);
  }
  postMessage(msg: unknown) {
    this.posted.push(msg as Posted);
  }
  terminate() {
    this.terminated = true;
  }
  reply(data: unknown) {
    this.onmessage?.({ data });
  }
}

const allPosted = () => FakeWorker.instances.flatMap((w) => w.posted);
const flush = () => act(async () => void (await vi.advanceTimersByTimeAsync(0)));
const advance = (ms: number) => act(async () => void (await vi.advanceTimersByTimeAsync(ms)));

/** Independent node counter (iterative) so the tests do not depend on countHastNodes. */
function countNodes(root: unknown): number {
  let n = 0;
  const stack: unknown[] = [root];
  while (stack.length > 0) {
    const node = stack.pop() as { children?: unknown[] };
    n++;
    if (Array.isArray(node.children)) for (const c of node.children) stack.push(c);
  }
  return n;
}

function oversizedHast(): Root {
  const children = Array.from({ length: 13_000 }, () => ({
    type: "element" as const,
    tagName: "p",
    properties: {},
    children: [{ type: "text" as const, value: "x" }],
  }));
  return { type: "root", children };
}

function fixedRunner(result: () => unknown): MarkdownRunner {
  return { parse: () => result() as Promise<ParseOutcome> };
}

function okRunner(hast: unknown): MarkdownRunner {
  return fixedRunner(() => Promise.resolve({ kind: "ok", hast }));
}

const table = (cols: number, bodyLines: number) =>
  "|" + "a|".repeat(cols) + "\n|" + "-|".repeat(cols) + "\n" + "x\n".repeat(bodyLines);

const REPRO_35 = Array.from(
  { length: 35 },
  () => "|" + "a|".repeat(71) + "\n|" + "-|".repeat(71) + "\n" + "x\n".repeat(137),
).join("\n\n");

/** Passes the pre-check but yields well over MAX_HAST_NODES nodes (checked below). */
const HEAVY_TABLE = table(1, 8900);

beforeEach(() => {
  FakeWorker.instances = [];
  vi.useFakeTimers();
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.mocked(toJsxRuntime).mockClear();
});

describe("MDR-05 total table cap", () => {
  it("exports MAX_TOTAL_TABLE_CELLS = 10000", () => {
    expect((limits as Record<string, unknown>).MAX_TOTAL_TABLE_CELLS).toBe(10_000);
  });

  it("Oversized output: 35 tables under the per-table cap exceed the total cap", () => {
    expect(REPRO_35.length).toBeLessThanOrEqual(limits.MAX_SOURCE_LENGTH);
    expect(limits.exceedsRenderLimits(REPRO_35)).toBe(true);
  });

  it("guard: a single 5x5 table is allowed", () => {
    expect(limits.exceedsRenderLimits(table(5, 3))).toBe(false);
  });

  it("guard: three 30x30 tables (about 2,700 cells) are allowed", () => {
    const three = [table(30, 28), table(30, 28), table(30, 28)].join("\n\n");
    expect(limits.exceedsRenderLimits(three)).toBe(false);
  });
});

describe("MDR-10 countHastNodes", () => {
  // The module is mocked above; read the real exports so a missing one does not throw.
  let real: Record<string, unknown> = {};
  beforeEach(async () => {
    real = await vi.importActual<Record<string, unknown>>("../src/MarkdownView.pipeline");
  });
  const count = () =>
    real.countHastNodes as ((root: unknown, limit: number) => number) | undefined;

  it("exports MAX_HAST_NODES = 25000 and countHastNodes", () => {
    expect(real.MAX_HAST_NODES).toBe(25_000);
    expect(typeof count()).toBe("function");
  });

  it("counts every node exactly on a small tree", () => {
    const tree = {
      type: "root",
      children: [
        { type: "element", tagName: "p", properties: {}, children: [{ type: "text", value: "a" }] },
        { type: "text", value: "\n" },
      ],
    };
    expect(count()!(tree, 100)).toBe(4);
  });

  it("stops early on a huge tree", () => {
    const children = Array.from({ length: 2_000_000 }, () => ({ type: "text", value: "x" }));
    const t0 = performance.now();
    const n = count()!({ type: "root", children }, 1000);
    const ms = performance.now() - t0;
    console.log(`countHastNodes early stop: ${ms.toFixed(1)} ms, result ${n}`);
    // Stops once the count passes the limit: first count above it, so exactly limit + 1.
    expect(n).toBeGreaterThan(1000);
    expect(n).toBeLessThanOrEqual(1001);
  });

  it("does not overflow the stack on a 100k-deep chain", () => {
    const root: { type: string; children: unknown[] } = { type: "root", children: [] };
    let tail = root;
    for (let i = 0; i < 100_000; i++) {
      const next = { type: "element", tagName: "div", properties: {}, children: [] };
      tail.children.push(next);
      tail = next;
    }
    expect(count()!(root, 1_000_000)).toBe(100_001);
  });
});

describe("MDR-10 handleParseRequest", () => {
  async function handler() {
    const mod = (await import("../src/markdown.worker")) as Record<string, unknown>;
    expect(typeof mod.handleParseRequest).toBe("function");
    return mod.handleParseRequest as (data: unknown) => unknown;
  }

  it("the heavy fixture passes the pre-check yet exceeds the node budget", async () => {
    expect(limits.exceedsRenderLimits(HEAVY_TABLE)).toBe(false);
    const hast = await realToHast(HEAVY_TABLE, "p-");
    expect(countNodes(hast)).toBeGreaterThan(25_000);
  });

  it("Oversized output is never rendered: over-budget toHast output gives ok:false", async () => {
    const handle = await handler();
    expect(handle({ id: 7, source: HEAVY_TABLE, prefix: "p-" })).toEqual({ id: 7, ok: false });
  });

  it("normal input gives ok:true with a root", async () => {
    const handle = await handler();
    expect(handle({ id: 3, source: "# Hi\n\n- a\n", prefix: "p-" })).toMatchObject({
      id: 3,
      ok: true,
      hast: { type: "root" },
    });
  });

  it("Malformed or hostile requests: bad fields give ok:false, no id gives null", async () => {
    const handle = await handler();
    expect(handle({ id: 1, source: 5, prefix: "p-" })).toEqual({ id: 1, ok: false });
    expect(handle({ id: 2, source: "x", prefix: null })).toEqual({ id: 2, ok: false });
    expect(handle({ id: 4, source: REPRO_35, prefix: "p-" })).toEqual({ id: 4, ok: false });
    expect(handle({ source: "x", prefix: "p-" })).toBeNull();
    expect(handle(null)).toBeNull();
    expect(handle("nope")).toBeNull();
  });
});

describe("MDR-10 view bounds the result", () => {
  it("Oversized output is never rendered: full source as plain text, toJsxRuntime not called", async () => {
    const source = "# Title\n\nsome text\n";
    const hast = oversizedHast();
    expect(countNodes(hast)).toBeGreaterThan(25_000);
    const { container } = render(
      <MarkdownParserProvider runner={okRunner(hast)}>
        <MarkdownView source={source} />
      </MarkdownParserProvider>,
    );
    await flush();
    const pre = container.querySelector("pre.text");
    expect(pre).not.toBeNull();
    expect(pre!.textContent).toBe(source);
    expect(container.querySelector("p")).toBeNull();
    expect(toJsxRuntime).not.toHaveBeenCalled();
  });

  const bad: Array<[string, unknown]> = [
    ["array", []],
    ["empty object", {}],
    ["root without children", { type: "root" }],
    ["null", null],
  ];

  describe.each(bad)("Malformed or hostile worker results: %s", (_name, hast) => {
    const source = "# Title\n\nsome text\n";

    it("fake runner shows plain text, not a blank view", async () => {
      const { container } = render(
        <MarkdownParserProvider runner={okRunner(hast)}>
          <MarkdownView source={source} />
        </MarkdownParserProvider>,
      );
      await flush();
      expect(container.querySelector("pre.text")?.textContent).toBe(source);
      expect(container.querySelector("h1")).toBeNull();
    });

    it("createWorkerRunner with a worker replying ok:true shows plain text", async () => {
      const runner = createWorkerRunner({ createWorker: () => new FakeWorker() });
      const { container } = render(
        <MarkdownParserProvider runner={runner}>
          <MarkdownView source={source} />
        </MarkdownParserProvider>,
      );
      await flush();
      const w = FakeWorker.instances[0];
      w.reply({ id: w.posted[0].id, ok: true, hast });
      await flush();
      expect(container.querySelector("pre.text")?.textContent).toBe(source);
      expect(container.querySelector("h1")).toBeNull();
    });
  });

  it("Malformed or hostile worker results: className is reduced to the allow-list", async () => {
    const text = (value: string) => ({ type: "text" as const, value });
    const el = (tagName: string, className: string[] | undefined, children: unknown[]) => ({
      type: "element" as const,
      tagName,
      properties: className === undefined ? {} : { className },
      children,
    });
    const hast = {
      type: "root",
      children: [
        el("p", ["sr-only", "text", "modal-backdrop"], [text("para")]),
        el("pre", undefined, [el("code", ["language-js", "modal-backdrop"], [text("let a")])]),
        el("pre", undefined, [el("code", ["modal-backdrop"], [text("let b")])]),
        el("ul", ["contains-task-list"], [el("li", ["task-list-item", "text"], [text("item")])]),
        el("section", ["footnotes"], [el("h2", ["sr-only"], [text("Footnotes")])]),
        el("div", ["modal-backdrop"], [text("div")]),
      ],
    };
    const { container } = render(
      <MarkdownParserProvider runner={okRunner(hast)}>
        <MarkdownView source="whatever" />
      </MarkdownParserProvider>,
    );
    await flush();
    const p = container.querySelector("p");
    expect(p).not.toBeNull();
    expect(p!.getAttribute("class") ?? "").toBe("");
    expect(container.querySelector(".modal-backdrop")).toBeNull();
    const codes = container.querySelectorAll("code");
    expect(codes).toHaveLength(2);
    expect(codes[0].getAttribute("class")).toBe("language-js");
    expect(codes[1].getAttribute("class") ?? "").toBe("");
    expect(container.querySelector("ul")?.getAttribute("class")).toBe("contains-task-list");
    expect(container.querySelector("li")?.getAttribute("class")).toBe("task-list-item");
    expect(container.querySelector("section")?.getAttribute("class")).toBe("footnotes");
    expect(container.querySelector("h2")?.getAttribute("class")).toBe("sr-only");
  });

  const oneClassEl = (tagName: string, className: string[], value: string) => ({
    type: "element" as const,
    tagName,
    properties: { className },
    children: [{ type: "text" as const, value }],
  });

  it("Malformed or hostile worker results: each element keeps at most one class", async () => {
    const hast = {
      type: "root",
      children: [
        { ...oneClassEl("pre", [], ""), children: [oneClassEl("code", ["language-a", "language-b"], "a")] },
        oneClassEl("ul", ["contains-task-list"], ""),
        { ...oneClassEl("ol", [], ""), children: [oneClassEl("li", ["task-list-item", "task-list-item"], "b")] },
      ],
    };
    const { container } = render(
      <MarkdownParserProvider runner={okRunner(hast)}>
        <MarkdownView source="whatever" />
      </MarkdownParserProvider>,
    );
    await flush();
    expect(container.querySelector("code")?.getAttribute("class")).toBe("language-a");
    expect(container.querySelector("li")?.getAttribute("class")).toBe("task-list-item");
  });

  it(
    "Malformed or hostile worker results: a 200,000-entry className is capped quickly",
    async () => {
      const className = Array.from({ length: 200_000 }, (_, i) => `language-x${i}`);
      const hast = {
        type: "root",
        children: [
          { ...oneClassEl("pre", [], ""), children: [oneClassEl("code", className, "a")] },
        ],
      };
      vi.useRealTimers(); // fake timers freeze performance.now
      const t0 = performance.now();
      const { container } = render(
        <MarkdownParserProvider runner={okRunner(hast)}>
          <MarkdownView source="whatever" />
        </MarkdownParserProvider>,
      );
      await act(async () => {
        await Promise.resolve();
      });
      const ms = performance.now() - t0;
      console.log(`bulk className render: ${ms.toFixed(1)} ms`);
      expect(container.querySelector("code")?.getAttribute("class")).toBe("language-x0");
      expect(ms).toBeLessThan(1000);
    },
    20000,
  );

  const throwing: Array<[string, () => unknown]> = [
    [
      "throws synchronously",
      () => {
        throw new Error("boom");
      },
    ],
    ["returns a non-promise", () => undefined],
    ["returns a plain object", () => ({ kind: "ok" })],
  ];

  it.each(throwing)(
    "Malformed or hostile worker results: a runner that %s falls back, siblings stay mounted",
    async (_name, behavior) => {
      const source = "# Title\n\nsome text\n";
      const errors = vi.spyOn(console, "error").mockImplementation(() => {});
      const { container } = render(
        <div>
          <span data-testid="sibling">still here</span>
          <MarkdownParserProvider runner={fixedRunner(behavior)}>
            <MarkdownView source={source} />
          </MarkdownParserProvider>
        </div>,
      );
      const sibling = container.querySelector("[data-testid=sibling]");
      await flush();
      errors.mockRestore();
      expect(container.querySelector("pre.text")?.textContent).toBe(source);
      expect(container.querySelector("[data-testid=sibling]")).toBe(sibling);
      expect(sibling).not.toBeNull();
    },
  );
});

describe("MDR-10 queue fairness and cancellation", () => {
  const Views = (props: { runner: MarkdownRunner; sources: string[] }) => (
    <MarkdownParserProvider runner={props.runner}>
      {props.sources.map((s) => (
        <div key={s} data-src={s}>
          <MarkdownView source={s} />
        </div>
      ))}
    </MarkdownParserProvider>
  );

  it("Queue fairness: requests that were never sent are still sent after two timeouts and render", async () => {
    const runner = createWorkerRunner({ createWorker: () => new FakeWorker() });
    const sources = ["slow one", "slow two", "# Bee", "# Sea"];
    const { container } = render(<Views runner={runner} sources={sources} />);
    await flush();
    await advance(1000); // SLOW1 times out
    await advance(1000); // SLOW2 times out
    await flush();
    const answer = async (src: string) => {
      const w = [...FakeWorker.instances]
        .reverse()
        .find((x) => x.posted.some((m) => m.source === src));
      expect(w, `${src} was posted to a worker`).toBeDefined();
      expect(w!.terminated).toBe(false);
      const m = w!.posted.find((x) => x.source === src)!;
      w!.reply({ id: m.id, ok: true, hast: await realToHast(src, m.prefix) });
      await flush();
    };
    await answer("# Bee");
    await answer("# Sea");
    expect(container.querySelector("[data-src='# Bee'] h1")).not.toBeNull();
    expect(container.querySelector("[data-src='# Sea'] h1")).not.toBeNull();
    // The slow ones were each sent exactly once.
    expect(allPosted().filter((m) => m.source === "slow one")).toHaveLength(1);
    expect(allPosted().filter((m) => m.source === "slow two")).toHaveLength(1);
  });

  it("Cancellation: aborting a queued request resolves fallback and never posts it", async () => {
    const runner = createWorkerRunner({ createWorker: () => new FakeWorker() });
    const first = runner.parse("first", "a-");
    const ctl = new AbortController();
    const parse = runner.parse as (
      s: string,
      p: string,
      sig?: AbortSignal,
    ) => Promise<ParseOutcome>;
    const second = parse("second", "b-", ctl.signal);
    await flush();
    ctl.abort();
    const settled = await Promise.race([second, Promise.resolve("pending" as const)]);
    expect(settled).toEqual({ kind: "fallback" });
    const w = FakeWorker.instances[0];
    w.reply({ id: w.posted[0].id, ok: true, hast: { type: "root", children: [] } });
    await expect(first).resolves.toMatchObject({ kind: "ok" });
    await flush();
    expect(allPosted().map((m) => m.source)).toEqual(["first"]);
  });

  it("Cancellation: unmounted views are never sent; only in-flight and the remaining view post", async () => {
    const runner = createWorkerRunner({ createWorker: () => new FakeWorker() });
    const sources = ["# one", "# two", "# three", "# four", "# five"];
    const views = sources.map((s) =>
      render(
        <MarkdownParserProvider runner={runner}>
          <MarkdownView source={s} />
        </MarkdownParserProvider>,
      ),
    );
    await flush();
    for (let i = 0; i < 4; i++) views[i].unmount();
    await flush();
    const w = FakeWorker.instances[0];
    w.reply({ id: w.posted[0].id, ok: true, hast: await realToHast("# one", w.posted[0].prefix) });
    await flush();
    await advance(1000);
    expect(allPosted().map((m) => m.source)).toEqual(["# one", "# five"]);
  });
});

describe("guards: ordinary reports still format", () => {
  const source =
    "# Report\n\nSome **bold** text.\n\n- one\n- two\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n```js\nlet x = 1;\n```\n";

  const expectFormatted = (container: HTMLElement) => {
    expect(container.querySelector("h1")).not.toBeNull();
    expect(container.querySelector("li")).not.toBeNull();
    expect(container.querySelector("table td")).not.toBeNull();
    expect(container.querySelector("pre code")).not.toBeNull();
    expect(container.querySelector("pre.text")).toBeNull();
  };

  it("in-thread path renders formatted", async () => {
    const { container } = render(
      <MarkdownParserProvider runner={inThreadRunner}>
        <MarkdownView source={source} />
      </MarkdownParserProvider>,
    );
    await flush();
    expectFormatted(container);
  });

  it("worker path renders formatted", async () => {
    const runner = createWorkerRunner({ createWorker: () => new FakeWorker() });
    const { container } = render(
      <MarkdownParserProvider runner={runner}>
        <MarkdownView source={source} />
      </MarkdownParserProvider>,
    );
    await flush();
    const w = FakeWorker.instances[0];
    w.reply({ id: w.posted[0].id, ok: true, hast: await realToHast(source, w.posted[0].prefix) });
    await flush();
    expectFormatted(container);
  });
});
