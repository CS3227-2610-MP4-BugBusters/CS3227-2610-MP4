import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { MarkdownView } from "../src/index";

afterEach(() => {
  cleanup();
});

describe("MDR-08 Footnotes without links", () => {
  it("Footnote reference is a superscript number, not a link", () => {
    const { container } = render(
      <MarkdownView source={"Fails on login[^1].\n\n[^1]: Only on Chrome.\n"} />,
    );
    expect(container.querySelectorAll("a")).toHaveLength(0);
    expect(container.querySelector("[href]")).toBeNull();
    const sup = container.querySelector("p sup");
    expect(sup).not.toBeNull();
    expect(sup!.textContent).toBe("1");
    expect(sup!.querySelector("a")).toBeNull();
  });

  it("Footnotes section is an ordered list in reference order with no back-reference", () => {
    const source = "First[^b] then second[^a].\n\n[^a]: Alpha def.\n[^b]: Beta def.\n";
    const { container } = render(<MarkdownView source={source} />);
    const items = Array.from(container.querySelectorAll("section ol > li"));
    expect(items).toHaveLength(2);
    expect(items[0].textContent).toContain("Beta def.");
    expect(items[1].textContent).toContain("Alpha def.");
    expect(container.textContent).not.toContain("↩");
    expect(container.querySelectorAll("a")).toHaveLength(0);
    expect(container.querySelector("[href]")).toBeNull();
    expect(Array.from(container.querySelectorAll("sup")).map((s) => s.textContent)).toEqual([
      "1",
      "2",
    ]);
  });

  it("Every generated ID is prefixed, including __proto__", () => {
    const source = [
      "a[^1] b[^note] c[^__proto__]",
      "",
      "[^1]: one",
      "[^note]: two",
      "[^__proto__]: three",
    ].join("\n");
    const { container } = render(<MarkdownView source={source} />);
    const withId = Array.from(container.querySelectorAll("[id]"));
    expect(withId.length).toBeGreaterThan(0);
    for (const el of withId) {
      expect(el.getAttribute("id")!.startsWith("user-content-")).toBe(true);
    }
    expect(container.querySelectorAll("a")).toHaveLength(0);
    expect(container.querySelectorAll("section ol > li")).toHaveLength(3);
    expect(container.querySelectorAll("sup")).toHaveLength(3);
  });

  it("Author-written fragment links are inert text", () => {
    const source = "[x](#top) [y](#user-content-fn-missing)\n\nReal[^1].\n\n[^1]: def\n";
    const { container } = render(<MarkdownView source={source} />);
    expect(container.querySelectorAll("a")).toHaveLength(0);
    expect(container.querySelector("[href]")).toBeNull();
    expect(container.textContent).toContain("x (#top)");
    expect(container.textContent).toContain("y (#user-content-fn-missing)");
  });

  it("Two views on one page never share IDs", () => {
    const source = "Note[^1]\n\n[^1]: text";
    const { container } = render(
      <div>
        <div data-testid="view-a">
          <MarkdownView source={source} />
        </div>
        <div data-testid="view-b">
          <MarkdownView source={source} />
        </div>
      </div>,
    );
    const ids = Array.from(container.querySelectorAll("[id]")).map((el) => el.getAttribute("id")!);
    expect(ids.length).toBeGreaterThan(0);
    expect(new Set(ids).size).toBe(ids.length);
    for (const id of ids) expect(id.startsWith("user-content-")).toBe(true);
    expect(container.querySelectorAll("a")).toHaveLength(0);
    for (const testId of ["view-a", "view-b"]) {
      const view = container.querySelector(`[data-testid="${testId}"]`)!;
      expect(view.querySelector("sup")?.textContent).toBe("1");
      expect(view.querySelectorAll("section ol > li")).toHaveLength(1);
    }
  });
});
