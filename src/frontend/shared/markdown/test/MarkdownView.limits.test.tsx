import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MarkdownView } from "../src/index";

const flags = vi.hoisted(() => ({ forceThrow: false }));

vi.mock("../src/MarkdownView.pipeline", async () => {
  const actual = await vi.importActual<typeof import("../src/MarkdownView.pipeline")>(
    "../src/MarkdownView.pipeline",
  );
  const wrapped: typeof actual.toHast = (source, prefix) => {
    if (flags.forceThrow) throw new Error("forced render failure");
    return actual.toHast(source, prefix);
  };
  return { ...actual, toHast: wrapped };
});

afterEach(() => {
  flags.forceThrow = false;
  cleanup();
  vi.restoreAllMocks();
});

function renderWithSibling(source: string) {
  return render(
    <div>
      <button type="button">sibling</button>
      <MarkdownView source={source} />
    </div>,
  );
}

// Design D4: input nested deeper than 32 levels falls back to escaped plain text (no parsing).
function expectFullSourceAsPlainText(container: HTMLElement, source: string) {
  expect(container.textContent ?? "").toContain(source);
  expect(container.querySelector("blockquote, li, ul")).toBeNull();
}

describe("MDR-05 failure-safe rendering", () => {
  it("Pathological input: 20,000 chars of nested markers render in under 1s with sibling usable", () => {
    const source = "> - ".repeat(5000).slice(0, 20000);
    expect(source.length).toBe(20000);
    vi.spyOn(console, "error").mockImplementation(() => {});
    const start = performance.now();
    let result: ReturnType<typeof renderWithSibling> | undefined;
    expect(() => {
      result = renderWithSibling(source);
    }).not.toThrow();
    const elapsed = performance.now() - start;
    expect(elapsed).toBeLessThan(1000);
    expect(result!.getByRole("button", { name: "sibling" })).toBeTruthy();
    expect(result!.container.textContent).toBeTruthy();
    expectFullSourceAsPlainText(result!.container, source);
  }, 3000);

  it("Pathological input: 4 KB deeply nested input does not throw and shows source", () => {
    const source = "> - ".repeat(1000) + "x";
    vi.spyOn(console, "error").mockImplementation(() => {});
    let result: ReturnType<typeof renderWithSibling> | undefined;
    expect(() => {
      result = renderWithSibling(source);
    }).not.toThrow();
    expect(result!.getByRole("button", { name: "sibling" })).toBeTruthy();
    expectFullSourceAsPlainText(result!.container, source);
  }, 3000);

  it("Render error shows complete source as escaped text without injecting elements", () => {
    flags.forceThrow = true;
    vi.spyOn(console, "error").mockImplementation(() => {});
    const source = "# Title\n\n<b>x</b> and <script>alert(1)</script>\n";
    let result: ReturnType<typeof renderWithSibling> | undefined;
    expect(() => {
      result = renderWithSibling(source);
    }).not.toThrow();
    const { container } = result!;
    expect(container.querySelector("b")).toBeNull();
    expect(container.querySelector("script")).toBeNull();
    expect(container.textContent).toContain(source.trim());
    expect(container.textContent).toContain("<script>alert(1)</script>");
    expect(result!.getByRole("button", { name: "sibling" })).toBeTruthy();
  }, 3000);

  it("Ordinary input still renders formatted", () => {
    const { container } = render(
      <MarkdownView source={"- one\n- two\n\n```\ncode here\n```\n"} />,
    );
    expect(container.querySelectorAll("li").length).toBe(2);
    expect(container.querySelector("pre code")?.textContent).toContain("code here");
    expect(container.textContent).not.toContain("```");
  }, 3000);
});
