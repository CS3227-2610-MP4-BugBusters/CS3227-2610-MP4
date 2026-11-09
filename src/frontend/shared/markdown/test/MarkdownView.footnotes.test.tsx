import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { MarkdownView } from "../src/index";

afterEach(() => {
  cleanup();
});

describe("MDR-08 GitHub-style footnotes", () => {
  it("Footnote reference and back-reference work", () => {
    const { container } = render(
      <MarkdownView source={"Fails on login[^1].\n\n[^1]: Only on Chrome.\n"} />,
    );

    const ref = container.querySelector("a[href^='#user-content-'][href$='fn-1']");
    expect(ref).not.toBeNull();
    const refHref = ref!.getAttribute("href")!;
    expect(refHref.startsWith("#user-content-")).toBe(true);
    expect(refHref.endsWith("fn-1")).toBe(true);

    const def = container.querySelector(`[id="${refHref.slice(1)}"]`);
    expect(def).not.toBeNull();
    expect(def?.textContent).toContain("Only on Chrome.");

    // Back-link inside the definition targets an existing, prefixed id.
    const backLinks = Array.from(def!.querySelectorAll("a[href^='#']"));
    expect(backLinks.length).toBeGreaterThan(0);
    for (const a of backLinks) {
      const href = a.getAttribute("href")!;
      expect(href.startsWith("#user-content-")).toBe(true);
      const target = container.querySelector(`[id="${href.slice(1)}"]`);
      expect(target).not.toBeNull();
    }
  });

  it("Every generated ID is prefixed", () => {
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
    // Footnotes actually rendered as linked references.
    expect(container.querySelectorAll("a[href^='#user-content-']").length).toBeGreaterThanOrEqual(3);
  });

  it("Author-written fragment links stay plain text", () => {
    const source = [
      "[x](#top) [y](#user-content-fn-missing) [z](#USER-CONTENT-fn-1)",
      "",
      "Real[^1].",
      "",
      "[^1]: def",
    ].join("\n");
    const { container } = render(<MarkdownView source={source} />);

    const hrefs = Array.from(container.querySelectorAll("a")).map((a) => a.getAttribute("href"));
    expect(hrefs).not.toContain("#top");
    expect(hrefs).not.toContain("#user-content-fn-missing");
    expect(hrefs).not.toContain("#USER-CONTENT-fn-1");

    const text = container.textContent ?? "";
    expect(text).toContain("x");
    expect(text).toContain("y");
    expect(text).toContain("z");

    // The genuine footnote link must still work alongside them.
    expect(container.querySelector("a[href^='#user-content-'][href$='fn-1']")).not.toBeNull();
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

    for (const testId of ["view-a", "view-b"]) {
      const view = container.querySelector(`[data-testid="${testId}"]`)!;
      const links = Array.from(view.querySelectorAll("a[href^='#']"));
      expect(links.length).toBeGreaterThan(0);
      for (const a of links) {
        const target = a.getAttribute("href")!.slice(1);
        expect(view.querySelector(`[id="${target}"]`)).not.toBeNull();
      }
    }
  });
});
