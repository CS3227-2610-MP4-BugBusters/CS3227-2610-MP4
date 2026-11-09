import { cleanup, render } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import React from "react";
import { afterEach, describe, expect, it } from "vitest";
import { MarkdownView } from "../src/index";

afterEach(cleanup);

function renderMd(source: string) {
  return render(<MarkdownView source={source} />);
}

describe("MDR-01 Supported Markdown subset", () => {
  it("Common report formatting: headings, paragraphs, emphasis, strikethrough", () => {
    const { container } = renderMd(
      "# Title\n\n## Section\n\nA paragraph with *em*, **strong** and ~~gone~~.\n",
    );
    expect(container.querySelector("h1")?.textContent).toBe("Title");
    expect(container.querySelector("h2")?.textContent).toBe("Section");
    expect(container.querySelector("p em")?.textContent).toBe("em");
    expect(container.querySelector("p strong")?.textContent).toBe("strong");
    expect(container.querySelector("p del")?.textContent).toBe("gone");
  });

  it("Common report formatting: ordered and unordered lists, blockquote, thematic break", () => {
    const { container } = renderMd(
      "- a\n- b\n\n1. one\n2. two\n\n> quoted\n\n---\n",
    );
    expect(container.querySelectorAll("ul > li")).toHaveLength(2);
    expect(container.querySelectorAll("ol > li")).toHaveLength(2);
    expect(container.querySelector("blockquote")?.textContent).toContain("quoted");
    expect(container.querySelector("hr")).not.toBeNull();
  });

  it("Common report formatting: inline code and fenced code block verbatim", () => {
    const code = "# not a heading\n**not bold** - not a list";
    const { container } = renderMd("Use `inline` here.\n\n```\n" + code + "\n```\n");
    expect(container.querySelector("p code")?.textContent).toBe("inline");
    const block = container.querySelector("pre > code");
    expect(block).not.toBeNull();
    expect(block?.textContent?.trimEnd()).toBe(code);
    expect(container.querySelector("pre h1")).toBeNull();
    expect(container.querySelector("pre strong")).toBeNull();
    expect(container.querySelector("pre li")).toBeNull();
  });

  it("Common report formatting: tables", () => {
    const { container } = renderMd("| A | B |\n|---|---|\n| 1 | 2 |\n");
    const ths = [...container.querySelectorAll("table th")].map((e) => e.textContent);
    const tds = [...container.querySelectorAll("table td")].map((e) => e.textContent);
    expect(ths).toEqual(["A", "B"]);
    expect(tds).toEqual(["1", "2"]);
  });

  it("Common report formatting: links and autolinks", () => {
    const { container } = renderMd(
      "[docs](https://example.com/docs) and https://example.com/auto\n",
    );
    const hrefs = [...container.querySelectorAll("a")].map((a) => a.getAttribute("href"));
    expect(hrefs).toContain("https://example.com/docs");
    expect(hrefs).toContain("https://example.com/auto");
  });

  it("Common report formatting: headings receive no generated id", () => {
    const { container } = renderMd("# One\n\n## Two\n\n### Three\n");
    const headings = container.querySelectorAll("h1, h2, h3");
    expect(headings).toHaveLength(3);
    headings.forEach((h) => expect(h.hasAttribute("id")).toBe(false));
  });

  it("Task list is not interactive: items render as disabled checkboxes that cannot be toggled", async () => {
    const source = "- [ ] todo\n- [x] done\n";
    const { container } = renderMd(source);
    const boxes = [
      ...container.querySelectorAll<HTMLInputElement>('input[type="checkbox"]'),
    ];
    expect(boxes).toHaveLength(2);
    boxes.forEach((b) => expect(b.disabled).toBe(true));
    expect(boxes.map((b) => b.checked)).toEqual([false, true]);

    const user = userEvent.setup();
    for (const b of boxes) await user.click(b);
    expect(boxes.map((b) => b.checked)).toEqual([false, true]);
    expect(source).toBe("- [ ] todo\n- [x] done\n");
    expect(container.textContent).toContain("todo");
  });
});
