import { act, cleanup, render, waitFor } from "@testing-library/react";
import type { Root } from "hast";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MarkdownView, MarkdownParserProvider, inThreadRunner } from "../src/index";
import { createWorkerRunner } from "../src/markdownRunner";
import type { MarkdownRunner, ParseOutcome, WorkerLike } from "../src/markdownRunner";
import * as pipeline from "../src/MarkdownView.pipeline";

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

const emptyHast: Root = { type: "root", children: [] };
const flush = () => vi.advanceTimersByTimeAsync(0);

describe("MDR-10 runner", () => {
  beforeEach(() => {
    FakeWorker.instances = [];
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  const make = () => createWorkerRunner({ createWorker: () => new FakeWorker() });

  it("Slow parse is abandoned", async () => {
    const runner = make();
    const p = runner.parse("x", "p-");
    await flush();
    const w = FakeWorker.instances[0];
    expect(w.posted).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1000);
    await expect(p).resolves.toEqual({ kind: "fallback" });
    expect(w.terminated).toBe(true);
    w.reply({ id: w.posted[0].id, ok: true, hast: emptyHast }); // late, ignored
    await flush();
    await expect(p).resolves.toEqual({ kind: "fallback" });
    expect(w.posted).toHaveLength(1);
    expect(w.terminated).toBe(true);
  });

  it("Parse completes within the limit", async () => {
    const runner = make();
    const p = runner.parse("x", "p-");
    await flush();
    const w = FakeWorker.instances[0];
    expect(w.posted[0]).toMatchObject({ source: "x", prefix: "p-" });
    w.reply({ id: w.posted[0].id, ok: true, hast: emptyHast });
    await expect(p).resolves.toEqual({ kind: "ok", hast: emptyHast });
    expect(w.terminated).toBe(false);
  });

  it("Worker failure falls back (ok:false)", async () => {
    const runner = make();
    const p = runner.parse("x", "p-");
    await flush();
    const w = FakeWorker.instances[0];
    w.reply({ id: w.posted[0].id, ok: false });
    await expect(p).resolves.toEqual({ kind: "fallback" });
  });

  it("Worker failure falls back (onerror)", async () => {
    const runner = make();
    const p = runner.parse("x", "p-");
    await flush();
    FakeWorker.instances[0].onerror?.({});
    await expect(p).resolves.toEqual({ kind: "fallback" });
  });

  it("One slow report does not break others", async () => {
    const runner = make();
    const p1 = runner.parse("slow", "a-");
    const p2 = runner.parse("fast", "b-");
    await flush();
    await vi.advanceTimersByTimeAsync(1000);
    await expect(p1).resolves.toEqual({ kind: "fallback" });
    await flush();
    expect(FakeWorker.instances.length).toBeGreaterThanOrEqual(2);
    const w2 = FakeWorker.instances[FakeWorker.instances.length - 1];
    expect(w2).not.toBe(FakeWorker.instances[0]);
    expect(w2.terminated).toBe(false);
    const msg = w2.posted.find((m) => m.source === "fast");
    expect(msg).toBeDefined();
    w2.reply({ id: msg!.id, ok: true, hast: emptyHast });
    await expect(p2).resolves.toEqual({ kind: "ok", hast: emptyHast });
  });

  it("A request that times out twice falls back", async () => {
    const runner = make();
    const p1 = runner.parse("slow1", "a-");
    const p2 = runner.parse("slow2", "b-");
    await flush();
    await vi.advanceTimersByTimeAsync(1000);
    await expect(p1).resolves.toEqual({ kind: "fallback" });
    await vi.advanceTimersByTimeAsync(1000);
    await expect(p2).resolves.toEqual({ kind: "fallback" });
  });

  it("Each request's timer starts when sent", async () => {
    const runner = make();
    const p1 = runner.parse("one", "a-");
    const p2 = runner.parse("two", "b-");
    await flush();
    const w = FakeWorker.instances[0];
    await vi.advanceTimersByTimeAsync(800);
    w.reply({ id: w.posted[0].id, ok: true, hast: emptyHast });
    await expect(p1).resolves.toMatchObject({ kind: "ok" });
    await flush();
    const second = w.posted.find((m) => m.source === "two");
    expect(second).toBeDefined();
    await vi.advanceTimersByTimeAsync(900); // 1700 ms since start, 900 since sent
    w.reply({ id: second!.id, ok: true, hast: emptyHast });
    await expect(p2).resolves.toMatchObject({ kind: "ok" });
    expect(w.terminated).toBe(false);
  });
});

function controlledRunner() {
  const calls: Array<{
    source: string;
    prefix: string;
    resolve: (o: ParseOutcome) => void;
  }> = [];
  const runner: MarkdownRunner = {
    parse(source: string, prefix: string) {
      return new Promise<ParseOutcome>((resolve) => {
        calls.push({ source, prefix, resolve });
      });
    },
  };
  return { runner, calls };
}

const norm = (s: string) => s.replace(/\s+/g, " ").trim();

describe("MDR-10 view", () => {
  afterEach(() => {
    cleanup();
    vi.mocked(pipeline.toHast).mockClear();
  });

  it("Main thread never parses", () => {
    const { runner, calls } = controlledRunner();
    const source = "# Title\n\nsome **bold** text";
    const { container } = render(
      <MarkdownParserProvider runner={runner}>
        <MarkdownView source={source} />
      </MarkdownParserProvider>,
    );
    expect(pipeline.toHast).not.toHaveBeenCalled();
    expect(calls).toHaveLength(1);
    expect(calls[0].source).toBe(source);
    const pre = container.querySelector("pre");
    expect(pre).not.toBeNull();
    expect(pre!.textContent).toBe(source);
  });

  it("Formatted result replaces plain text", async () => {
    const source = "# Title\n\nsome **bold** text and `code`\n\n- a\n- b\n";
    const { runner, calls } = controlledRunner();
    const view = render(
      <MarkdownParserProvider runner={runner}>
        <MarkdownView source={source} />
      </MarkdownParserProvider>,
    );
    const hast = await realToHast(source, calls[0].prefix);
    await act(async () => {
      calls[0].resolve({ kind: "ok", hast });
    });
    await waitFor(() => expect(view.container.querySelector("pre")).toBeNull());
    const actual = norm(view.container.innerHTML);
    cleanup();
    const ref = render(
      <MarkdownParserProvider runner={inThreadRunner}>
        <MarkdownView source={source} />
      </MarkdownParserProvider>,
    );
    expect(actual).toBe(norm(ref.container.innerHTML));
  });

  it("Safety is re-applied to worker output", async () => {
    const { runner, calls } = controlledRunner();
    const el = (tagName: string, properties: Record<string, string>, text?: string) => ({
      type: "element" as const,
      tagName,
      properties,
      children: text ? [{ type: "text" as const, value: text }] : [],
    });
    const hast: Root = {
      type: "root",
      children: [
        el("img", { src: "https://tracker.example/p.png", alt: "pic" }),
        el("a", { href: "javascript:alert(1)" }, "jslink"),
        el("a", { href: "https://u:p@evil.example/" }, "credlink"),
        el("script", {}, "alert(2)"),
        el("p", { id: "evil" }, "idtext"),
        el("a", { href: "#evil" }, "fraglink"),
        el("iframe", { src: "https://evil.example/" }, "frametext"),
      ],
    };
    const view = render(
      <MarkdownParserProvider runner={runner}>
        <MarkdownView source="whatever" />
      </MarkdownParserProvider>,
    );
    await act(async () => {
      calls[0].resolve({ kind: "ok", hast });
    });
    await waitFor(() => expect(view.container.querySelector("pre")).toBeNull());
    const c = view.container;
    expect(c.querySelector("img")).toBeNull();
    expect(c.querySelector("script")).toBeNull();
    expect(c.querySelector("iframe")).toBeNull();
    for (const a of Array.from(c.querySelectorAll("a"))) {
      const href = a.getAttribute("href") ?? "";
      expect(href).not.toMatch(/^javascript:/i);
      expect(href).not.toMatch(/u:p@/);
      expect(href).not.toBe("#evil");
    }
    expect(c.querySelector('[id="evil"]')).toBeNull();
    expect(c.textContent).toContain("jslink");
    expect(c.textContent).toContain("credlink");
    expect(c.textContent).toContain("idtext");
    expect(c.textContent).toContain("fraglink");
  });

  it("Stale result is ignored", async () => {
    const { runner, calls } = controlledRunner();
    const ui = (s: string) => (
      <MarkdownParserProvider runner={runner}>
        <MarkdownView source={s} />
      </MarkdownParserProvider>
    );
    const view = render(ui("old source"));
    view.rerender(ui("new source"));
    await waitFor(() => expect(calls.length).toBeGreaterThanOrEqual(2));
    const oldCall = calls.find((c) => c.source === "old source")!;
    const hast: Root = {
      type: "root",
      children: [
        {
          type: "element",
          tagName: "p",
          properties: {},
          children: [{ type: "text", value: "OLDRESULT" }],
        },
      ],
    };
    await act(async () => {
      oldCall.resolve({ kind: "ok", hast });
    });
    expect(view.container.textContent).not.toContain("OLDRESULT");
    expect(view.container.querySelector("pre")?.textContent).toBe("new source");
  });

  it("Slow parse is abandoned: late worker reply never formats the view", async () => {
    FakeWorker.instances = [];
    const source = "plain **x** text";
    const runner = createWorkerRunner({ createWorker: () => new FakeWorker() });
    vi.useFakeTimers();
    try {
      const view = render(
        <MarkdownParserProvider runner={runner}>
          <MarkdownView source={source} />
        </MarkdownParserProvider>,
      );
      await act(async () => {
        await vi.advanceTimersByTimeAsync(1000);
      });
      const w = FakeWorker.instances[0];
      expect(w.terminated).toBe(true);
      const hast = await realToHast(source, "user-content-");
      await act(async () => {
        w.reply({ id: w.posted[0].id, ok: true, hast });
        await vi.advanceTimersByTimeAsync(0);
      });
      expect(view.container.querySelector("pre")?.textContent).toBe(source);
      expect(view.container.querySelector("strong")).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it("Fallback keeps plain text", async () => {
    const { runner, calls } = controlledRunner();
    const source = "# raw *text*";
    const view = render(
      <MarkdownParserProvider runner={runner}>
        <MarkdownView source={source} />
      </MarkdownParserProvider>,
    );
    await act(async () => {
      calls[0].resolve({ kind: "fallback" });
    });
    expect(view.container.querySelector("pre")?.textContent).toBe(source);
    expect(view.container.querySelector("h1")).toBeNull();
  });

  it("Default is in-thread when Worker is unavailable", () => {
    const g = globalThis as { Worker?: unknown };
    const saved = g.Worker;
    delete g.Worker;
    try {
      const { container } = render(<MarkdownView source={"# Heading"} />);
      expect(container.querySelector("h1")).not.toBeNull();
      expect(container.querySelector("pre")).toBeNull();
    } finally {
      if (saved !== undefined) g.Worker = saved;
    }
  });
});
