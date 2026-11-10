import { act, cleanup, render, waitFor } from "@testing-library/react";
import type { Root } from "hast";
import { toJsxRuntime } from "hast-util-to-jsx-runtime";
import Markdown from "react-markdown";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MarkdownView, MarkdownParserProvider } from "../src/index";
import { createWorkerRunner } from "../src/markdownRunner";
import type { MarkdownRunner, ParseOutcome, WorkerLike } from "../src/markdownRunner";
import * as limits from "../src/MarkdownView.limits";
import { toHast } from "../src/MarkdownView.pipeline";

// Task 3.14: one render path and a bounded sanitiser (design D14; MDR-05, MDR-10).
// "expected-Red" tests fail on the 3.12 candidate; "guard" tests pass on it and must keep passing.

vi.mock("react-markdown", async () => {
  const actual = await vi.importActual<typeof import("react-markdown")>("react-markdown");
  return { ...actual, default: vi.fn(actual.default) };
});

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

const flush = () => act(async () => void (await Promise.resolve()));

function okRunner(hast: unknown): MarkdownRunner {
  return { parse: () => Promise.resolve({ kind: "ok", hast }) as Promise<ParseOutcome> };
}

const table = (cols: number, bodyLines: number) =>
  "|" + "a|".repeat(cols) + "\n|" + "-|".repeat(cols) + "\n" + "x\n".repeat(bodyLines);

beforeEach(() => {
  vi.mocked(toHast).mockClear();
  vi.mocked(toJsxRuntime).mockClear();
  vi.mocked(Markdown).mockClear();
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("MDR-05 in-thread: the three security-review repros fall back before parsing", () => {
  const repros: Array<[string, string]> = [
    ['"- -\\n" x 5000', "- -\n".repeat(5000)],
    ['(">" x 32 + "a\\n\\n") x 571', (">".repeat(32) + "a\n\n").repeat(571)],
    ['("- " x 16 + "a\\n") x 588', ("- ".repeat(16) + "a\n").repeat(588)],
  ];

  it("expected-Red: limits exports MAX_TOTAL_CONTAINERS as a positive number", () => {
    const value = (limits as Record<string, unknown>).MAX_TOTAL_CONTAINERS;
    expect(typeof value).toBe("number");
    expect(value as number).toBeGreaterThan(0);
  });

  it.each(repros)(
    "expected-Red: %s: pre-check true, under 1 s, full source as text, toHast never called",
    (_name, source) => {
      expect(limits.exceedsRenderLimits(source)).toBe(true);
      const t0 = performance.now();
      const { container } = render(<MarkdownView source={source} />);
      const ms = performance.now() - t0;
      expect(ms).toBeLessThan(1000);
      expect(container.querySelector("pre.text")?.textContent).toBe(source);
      expect(toHast).not.toHaveBeenCalled();
    },
    30000,
  );
});

describe("MDR-10 in-thread has the same output budget", () => {
  const HEAVY_TABLE = table(1, 8900);

  it("guard: the heavy fixture passes the pre-check", () => {
    expect(limits.exceedsRenderLimits(HEAVY_TABLE)).toBe(false);
  });

  it("expected-Red: over-budget output falls back to full source, toJsxRuntime not called", () => {
    const { container } = render(<MarkdownView source={HEAVY_TABLE} />);
    const pre = container.querySelector("pre.text");
    expect(pre).not.toBeNull();
    expect(pre!.textContent).toBe(HEAVY_TABLE);
    expect(container.querySelector("table")).toBeNull();
    expect(toJsxRuntime).not.toHaveBeenCalled();
  }, 60000);
});

describe("MDR-10 in-thread and worker paths render the same DOM", () => {
  const sources: Array<[string, string]> = [
    ["headings", "# Title\n\n## Section\n\nbody text\n"],
    ["nested list depth 3", "- a\n  - b\n    - c\n"],
    ["task list", "- [x] done\n- [ ] todo\n"],
    ["table", "|a|b|\n|-|-|\n|1|2|\n"],
    ["code", "```js\nlet a = 1;\n```\n"],
    ["footnotes", "Text[^1]\n\n[^1]: The note\n"],
    ["link as text", "see [site](https://example.com/page) now\n"],
    ["image as alt text", "![alt text](https://example.com/x.png)\n"],
  ];
  const normalise = (html: string) =>
    html.replace(/user-content-[A-Za-z0-9_]+-/g, "user-content-ID-");

  it.each(sources)("guard: %s", async (_name, source) => {
    const inThread = render(<MarkdownView source={source} />);
    const a = normalise(inThread.container.innerHTML);
    inThread.unmount();

    // The worker path gets the view's own prefix, as the real worker does.
    const workerRunner: MarkdownRunner = {
      parse: async (src, prefix) => ({ kind: "ok", hast: await realToHast(src, prefix) }),
    };
    const worker = render(
      <MarkdownParserProvider runner={workerRunner}>
        <MarkdownView source={source} />
      </MarkdownParserProvider>,
    );
    // The runner awaits a dynamic import (slow when cold or under load), so poll
    // until the plain-text placeholder is replaced instead of sleeping a fixed time.
    await waitFor(() => expect(worker.container.querySelector("pre.text")).toBeNull(), {
      timeout: 10_000,
    });
    const b = normalise(worker.container.innerHTML);
    expect(a).not.toBe("");
    expect(a).not.toContain('class="text"');
    expect(a).toBe(b);
  });
});

describe("MDR-05 in-thread never renders through react-markdown", () => {
  it("expected-Red: react-markdown is not called, and the source is still formatted", () => {
    const { container } = render(<MarkdownView source={"# Title\n\n- one\n- two\n"} />);
    expect(container.querySelector("h1")?.textContent).toBe("Title");
    expect(container.querySelectorAll("li")).toHaveLength(2);
    expect(vi.mocked(Markdown)).not.toHaveBeenCalled();
    expect(toHast).toHaveBeenCalled();
  });
});

describe("MDR-10 sanitiser property bloat is bounded", () => {
  const text = (value: string) => ({ type: "text" as const, value });

  async function timedRender(hast: unknown) {
    const view = (
      <MarkdownParserProvider runner={okRunner(hast)}>
        <MarkdownView source="whatever" />
      </MarkdownParserProvider>
    );
    const t0 = performance.now();
    const result = render(view);
    await flush();
    return { result, ms: performance.now() - t0 };
  }

  async function warmUp() {
    const warm = {
      type: "root",
      children: [
        { type: "element", tagName: "p", properties: { a: "x" }, children: [text("w")] },
        { type: "element", tagName: "ul", properties: { className: ["x"] }, children: [] },
      ],
    };
    const { result } = await timedRender(warm);
    result.unmount();
  }

  it("expected-Red (timing): 20 p elements with 200,000 junk properties render under 300 ms", async () => {
    const junkTarget: Record<string, string> = {};
    for (let i = 0; i < 200_000; i++) junkTarget[`junk${i}`] = "x";
    let enumerations = 0;
    const junk = new Proxy(junkTarget, {
      ownKeys(target) {
        enumerations++;
        return Reflect.ownKeys(target);
      },
    });
    const hast = {
      type: "root",
      children: Array.from({ length: 20 }, () => ({
        type: "element",
        tagName: "p",
        properties: junk,
        children: [text("para")],
      })),
    };
    await warmUp();
    const { result, ms } = await timedRender(hast);
    console.log(`junk props render: ${ms.toFixed(1)} ms`);
    // Structural, machine-independent: the junk keys are never enumerated.
    expect(enumerations).toBe(0);
    const ps = result.container.querySelectorAll("p");
    expect(ps).toHaveLength(20);
    for (const p of ps) expect(p.attributes.length).toBe(0);
    expect(ms).toBeLessThan(300);
  }, 60000);

  it("expected-Red: the sanitiser does not enumerate a property object's keys", async () => {
    let enumerations = 0;
    const props = new Proxy(
      { id: 1 },
      {
        ownKeys(target) {
          enumerations++;
          return Reflect.ownKeys(target);
        },
      },
    );
    const hast = {
      type: "root",
      children: [{ type: "element", tagName: "p", properties: props, children: [text("para")] }],
    };
    const { result } = await timedRender(hast);
    expect(result.container.querySelector("p")?.textContent).toBe("para");
    expect(enumerations).toBe(0);
  });

  it("expected-Red (timing): 50 ul elements with a 2,000,000-entry non-matching class list render under 300 ms", async () => {
    let reads = 0;
    const className = new Proxy(new Array<string>(2_000_000).fill("nomatch"), {
      get(target, key, receiver) {
        if (typeof key === "string" && /^\d+$/.test(key)) reads++;
        return Reflect.get(target, key, receiver);
      },
    });
    const hast = {
      type: "root",
      children: Array.from({ length: 50 }, () => ({
        type: "element",
        tagName: "ul",
        properties: { className },
        children: [],
      })),
    };
    await warmUp();
    const { result, ms } = await timedRender(hast);
    console.log(`bulk class render: ${ms.toFixed(1)} ms`);
    // Structural, machine-independent: at most 8 entries read per element.
    expect(reads).toBeLessThanOrEqual(50 * 8);
    const uls = result.container.querySelectorAll("ul");
    expect(uls).toHaveLength(50);
    for (const ul of uls) {
      expect(ul.getAttribute("class") ?? "").toBe("");
      expect(ul.attributes.length).toBe(0);
    }
    expect(ms).toBeLessThan(300);
  }, 60000);

  it("expected-Red: at most 8 class entries are read per element", async () => {
    let reads = 0;
    const className = new Proxy(new Array<string>(100_000).fill("nomatch"), {
      get(target, key, receiver) {
        if (typeof key === "string" && /^\d+$/.test(key)) reads++;
        return Reflect.get(target, key, receiver);
      },
    });
    const hast = {
      type: "root",
      children: [{ type: "element", tagName: "ul", properties: { className }, children: [] }],
    };
    const { result } = await timedRender(hast);
    expect(result.container.querySelector("ul")).not.toBeNull();
    expect(reads).toBeLessThanOrEqual(8);
  });

  it("guard: junk attributes and classes are dropped, allowed ones are kept", async () => {
    const hast = {
      type: "root",
      children: [
        {
          type: "element",
          tagName: "p",
          properties: { junk: "x", onclick: "alert(1)", className: ["modal-backdrop"] },
          children: [text("para")],
        },
        {
          type: "element",
          tagName: "ul",
          properties: { className: ["junk", "contains-task-list"] },
          children: [],
        },
      ],
    };
    const { result } = await timedRender(hast);
    const p = result.container.querySelector("p")!;
    expect(p.attributes.length).toBe(0);
    expect(result.container.querySelector("ul")?.getAttribute("class")).toBe("contains-task-list");
  });
});

describe("MDR-10 sanitiser cleans before it counts", () => {
  const text = (value: string) => ({ type: "text" as const, value });

  it("expected-Red: a children getter that grows after the first read cannot inflate the output", async () => {
    let reads = 0;
    const p: Record<string, unknown> = { type: "element", tagName: "p", properties: {} };
    Object.defineProperty(p, "children", {
      enumerable: true,
      get() {
        reads++;
        return reads === 1 ? [text("one")] : Array.from({ length: 200_000 }, () => text("x"));
      },
    });
    const { container } = render(
      <MarkdownParserProvider runner={okRunner({ type: "root", children: [p] })}>
        <MarkdownView source="whatever" />
      </MarkdownParserProvider>,
    );
    await flush();
    // Either the plain text fallback or what was counted, never the 200,000 later children.
    expect((container.textContent ?? "").length).toBeLessThan(1000);
  }, 60000);

  it("guard: a properties getter that grows after the first read cannot add attributes", async () => {
    let reads = 0;
    const p: Record<string, unknown> = { type: "element", tagName: "p", children: [text("para")] };
    Object.defineProperty(p, "properties", {
      enumerable: true,
      get() {
        reads++;
        if (reads === 1) return {};
        const junk: Record<string, string> = {};
        for (let i = 0; i < 200_000; i++) junk[`junk${i}`] = "x";
        return junk;
      },
    });
    const { container } = render(
      <MarkdownParserProvider runner={okRunner({ type: "root", children: [p] })}>
        <MarkdownView source="whatever" />
      </MarkdownParserProvider>,
    );
    await flush();
    const el = container.querySelector("p");
    expect(el === null || el.attributes.length === 0).toBe(true);
    expect(reads).toBeLessThanOrEqual(1);
  }, 60000);
});

describe("MDR-10 worker runner pump is a loop", () => {
  class FakeWorker implements WorkerLike {
    static instances: FakeWorker[] = [];
    posted: Array<{ id: number }> = [];
    onmessage: ((ev: { data: unknown }) => void) | null = null;
    onerror: ((ev: unknown) => void) | null = null;
    constructor() {
      FakeWorker.instances.push(this);
    }
    postMessage(msg: unknown) {
      this.posted.push(msg as { id: number });
    }
    terminate() {}
  }

  it("expected-Red: 20,000 queued requests all fall back when no worker can be created, then recovery works", async () => {
    FakeWorker.instances = [];
    vi.useFakeTimers();
    let creations = 0;
    let canCreate = false;
    const runner = createWorkerRunner({
      timeoutMs: 1000,
      createWorker: () => {
        creations++;
        if (creations === 1 || canCreate) return new FakeWorker();
        throw new Error("no worker");
      },
    });
    const pending: Array<Promise<ParseOutcome>> = [];
    for (let i = 0; i < 20_000; i++) pending.push(runner.parse(`source ${i}`, "p-"));

    // The first worker never answers; after the timeout every later creation throws.
    let thrown: unknown;
    try {
      await vi.advanceTimersByTimeAsync(1000);
    } catch (error) {
      thrown = error;
    }
    expect(thrown).toBeUndefined();

    const outcomes = await Promise.all(pending);
    expect(outcomes.every((o) => o.kind === "fallback")).toBe(true);

    // A later request, once workers can be created again, works normally.
    canCreate = true;
    const later = runner.parse("later", "p-");
    const worker = FakeWorker.instances[FakeWorker.instances.length - 1];
    expect(worker.posted.length).toBeGreaterThan(0);
    const id = worker.posted[worker.posted.length - 1].id;
    worker.onmessage?.({ data: { id, ok: true, hast: { type: "root", children: [] } } });
    await expect(later).resolves.toMatchObject({ kind: "ok" });
  }, 60000);
});

describe("MDR-05 ordinary nested lists still render formatted in-thread", () => {
  it.each([8, 9, 10])("guard: a list nested %i deep renders as nested lists", (depth) => {
    const source =
      Array.from({ length: depth }, (_, i) => `${"  ".repeat(i)}- item ${i}`).join("\n") + "\n";
    expect(limits.exceedsRenderLimits(source)).toBe(false);
    const { container } = render(<MarkdownView source={source} />);
    expect(container.querySelector("pre.text")).toBeNull();
    expect(container.querySelectorAll("li")).toHaveLength(depth);
    expect(container.querySelectorAll("ul")).toHaveLength(depth);
  });

  it("guard: 300 alternating top-level and nested bullets render formatted", () => {
    const source =
      Array.from({ length: 300 }, (_, i) => (i % 2 === 0 ? `- a${i}` : `  - b${i}`)).join("\n") +
      "\n";
    expect(source.length).toBeLessThan(20_000);
    expect(limits.exceedsRenderLimits(source)).toBe(false);
    const { container } = render(<MarkdownView source={source} />);
    expect(container.querySelector("pre.text")).toBeNull();
    expect(container.querySelector("ul ul")).not.toBeNull();
    expect(container.querySelectorAll("li").length).toBeGreaterThanOrEqual(300);
  });

  it("guard: a mixed report with 150 bullets over 3 levels, quotes and headings renders formatted", () => {
    const bullets = Array.from(
      { length: 150 },
      (_, i) => `${"  ".repeat(i % 3)}- item ${i}`,
    );
    const quotes = Array.from({ length: 20 }, (_, i) => `> quoted line ${i}`);
    const source = ["# Report", "", ...bullets, "", "## Notes", "", ...quotes, ""].join("\n");
    expect(source.length).toBeLessThan(20_000);
    expect(limits.exceedsRenderLimits(source)).toBe(false);
    const { container } = render(<MarkdownView source={source} />);
    expect(container.querySelector("pre.text")).toBeNull();
    expect(container.querySelectorAll("h1, h2")).toHaveLength(2);
    expect(container.querySelector("ul ul ul")).not.toBeNull();
    expect(container.querySelectorAll("li").length).toBeGreaterThanOrEqual(150);
    expect(container.querySelector("blockquote")).not.toBeNull();
  });

  it("guard: a 200-item list renders as a list", () => {
    const source = Array.from({ length: 200 }, (_, i) => `- item ${i}`).join("\n") + "\n";
    expect(limits.exceedsRenderLimits(source)).toBe(false);
    const { container } = render(<MarkdownView source={source} />);
    expect(container.querySelector("pre.text")).toBeNull();
    expect(container.querySelectorAll("li")).toHaveLength(200);
  });
});
