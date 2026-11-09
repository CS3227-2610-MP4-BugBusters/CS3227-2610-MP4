import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MarkdownView } from "../src/index";

const flags = vi.hoisted(() => ({ blockParser: false, parserCalls: 0 }));

vi.mock("react-markdown", async () => {
  const actual = await vi.importActual<typeof import("react-markdown")>("react-markdown");
  const Actual = actual.default;
  const Wrapped = (props: Parameters<typeof Actual>[0]) => {
    flags.parserCalls += 1;
    if (flags.blockParser) throw new Error("parser must not be invoked for this input");
    return Actual(props);
  };
  return { ...actual, default: Wrapped };
});

afterEach(() => {
  flags.blockParser = false;
  flags.parserCalls = 0;
  cleanup();
  vi.restoreAllMocks();
});

const SENTINEL = "END-SENTINEL";
const START = "START-SENTINEL";
const T = 60000;

function renderWithSibling(source: string) {
  return render(
    <div>
      <button type="button">sibling</button>
      <MarkdownView source={source} />
    </div>,
  );
}

// Either the complete untrimmed source as plain text in a <pre>, or formatted with the sentinel visible.
function expectCompleteContent(container: HTMLElement, source: string) {
  const text = container.textContent ?? "";
  const pre = container.querySelector("pre");
  const asPlain = pre !== null && (pre.textContent ?? "") === source;
  const formatted = text.includes(SENTINEL) && text.includes(START);
  expect(asPlain || formatted).toBe(true);
}

const cases: Array<[string, string]> = [
  ["footnote + '> - ' nesting (20k)", "x[^a]\n\n[^a]: " + "> - ".repeat(4990)],
  ["footnote + '> - ' nesting (4 KB)", "x[^a]\n\n[^a]: " + "> - ".repeat(1000)],
  ["repeated '[^a]: - ' definitions", "x[^a]\n\n" + "[^a]: - ".repeat(2480)],
  ["wide table with many short rows", "|" + "a|".repeat(300) + "\n|" + "-|".repeat(300) + "\n" + "x\n".repeat(600)],
  ["long run of asterisks", "*".repeat(9985) + "a" + "*".repeat(9985)],
  ["10k asterisk run", "*".repeat(4995) + "a" + "*".repeat(4995)],
  ["dense '*_' delimiters", "*_".repeat(9985) + "a"],
  ["setext headings", "a\n=\n".repeat(4990)],
  ["lazy blockquote continuations", ">a\nb\n".repeat(3990)],
];

describe("MDR-05 known slow constructs up to 20,000 characters", () => {
  it.each(cases)("%s renders in <1s with complete content and sibling", (_name, base) => {
    const source = base + "\n" + SENTINEL;
    expect(source.length).toBeLessThanOrEqual(20000);
    vi.spyOn(console, "error").mockImplementation(() => {});
    let result: ReturnType<typeof renderWithSibling> | undefined;
    const start = performance.now();
    expect(() => {
      result = renderWithSibling(source);
    }).not.toThrow();
    const elapsed = performance.now() - start;
    console.log(`TIMING ${_name}: ${elapsed.toFixed(0)} ms`);
    expect(elapsed).toBeLessThan(1000);
    expect(result!.getByRole("button", { name: "sibling" })).toBeTruthy();
    expectCompleteContent(result!.container, source);
  }, T);
});

describe("MDR-05 huge table never reaches the parser", () => {
  it("2500-column table with ~4900 rows falls back to plain text without parsing", () => {
    const source =
      "|" + "a|".repeat(2500) + "\n|" + "-|".repeat(2500) + "\n" + "x\n".repeat(4900);
    expect(source.length).toBeLessThanOrEqual(20000);
    flags.blockParser = true;
    vi.spyOn(console, "error").mockImplementation(() => {});
    const start = performance.now();
    const result = renderWithSibling(source);
    const elapsed = performance.now() - start;
    console.log(`TIMING huge table (spy): ${elapsed.toFixed(0)} ms, parserCalls=${flags.parserCalls}`);
    expect(flags.parserCalls).toBe(0);
    expect(result.container.querySelector("pre")?.textContent).toBe(source);
    expect(result.getByRole("button", { name: "sibling" })).toBeTruthy();
    expect(elapsed).toBeLessThan(1000);
  }, T);
});

describe("MDR-05 ordinary reports still render formatted", () => {
  it("modest table renders as a table", () => {
    const head = "|" + "h|".repeat(5);
    const sep = "|" + "-|".repeat(5);
    const rows = Array.from({ length: 20 }, (_, i) => "|" + `c${i}|`.repeat(5)).join("\n");
    const { container } = renderWithSibling([head, sep, rows].join("\n"));
    expect(container.querySelector("table")).not.toBeNull();
    expect(container.querySelectorAll("tbody tr").length).toBe(20);
  }, T);

  it("mid-size 6x200 table renders as a table", () => {
    const head = "|" + "h|".repeat(6);
    const sep = "|" + "-|".repeat(6);
    const rows = Array.from({ length: 200 }, (_, i) => "|" + `c${i}|`.repeat(6)).join("\n");
    const { container } = renderWithSibling([head, sep, rows].join("\n"));
    expect(container.querySelector("table")).not.toBeNull();
    expect(container.querySelectorAll("tbody tr").length).toBe(200);
  }, T);

  it("normal emphasis renders strong and em", () => {
    const { container } = renderWithSibling("Text *a* **b** _c_ end.");
    expect(container.querySelector("strong")).not.toBeNull();
    expect(container.querySelectorAll("em").length).toBeGreaterThanOrEqual(2);
  }, T);

  it("setext headings render as h1", () => {
    const { container } = renderWithSibling("Title\n=====\n\nbody\n\nOther\n=\n");
    expect(container.querySelectorAll("h1").length).toBe(2);
  }, T);

  it("short blockquote with lazy line renders as blockquote", () => {
    const { container } = renderWithSibling(">quoted\nlazy line\n");
    const bq = container.querySelector("blockquote");
    expect(bq).not.toBeNull();
    expect(bq!.textContent).toContain("lazy line");
  }, T);
});
