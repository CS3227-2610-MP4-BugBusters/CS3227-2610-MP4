import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MarkdownEditor, MarkdownView } from "./index";

afterEach(() => {
  cleanup();
});

const SOURCE = "# Title\n\nSome **bold** text and a [link](https://example.com).\n\n- a\n- b\n";

function Harness({ initial, spy }: { initial: string; spy?: (v: string) => void }) {
  const [value, setValue] = useState(initial);
  return (
    <MarkdownEditor
      label="Description"
      value={value}
      onChange={(n: string) => {
        spy?.(n);
        setValue(n);
      }}
    />
  );
}

const norm = (html: string) => html.replace(/\s+/g, " ").replace(/>\s+</g, "><").trim();

describe("MDR-06 Write and Preview editing", () => {
  it("Write tab shows labelled textarea with value and typing calls onChange", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<MarkdownEditor label="Description" value="abc" onChange={onChange} />);
    const ta = screen.getByLabelText("Description") as HTMLTextAreaElement;
    expect(ta.tagName).toBe("TEXTAREA");
    expect(ta.value).toBe("abc");
    await user.type(ta, "d");
    expect(onChange).toHaveBeenCalledWith("abcd");
  });

  it("Preview matches what other roles see (equals MarkdownView output)", async () => {
    const user = userEvent.setup();
    const ref = render(<MarkdownView source={SOURCE} />);
    const expected = norm(ref.container.innerHTML);
    ref.unmount();

    const { container } = render(<Harness initial={SOURCE} />);
    await user.click(screen.getByRole("tab", { name: "Preview" }));
    const panel = screen.getByRole("tabpanel");
    expect(expected.length).toBeGreaterThan(0);
    expect(norm(panel.innerHTML)).toBe(expected);
    expect(panel.textContent).not.toContain("**bold**");
    expect(panel.textContent).not.toContain("# Title");
    expect(container.querySelector("textarea")).toBeNull();
  });

  it("Switching back to Write shows S unchanged, focused, without onChange", async () => {
    const user = userEvent.setup();
    const spy = vi.fn();
    render(<Harness initial={SOURCE} spy={spy} />);
    await user.click(screen.getByRole("tab", { name: "Preview" }));
    expect(spy).not.toHaveBeenCalled();
    await user.click(screen.getByRole("tab", { name: "Write" }));
    const ta = screen.getByLabelText("Description") as HTMLTextAreaElement;
    expect(ta.value).toBe(SOURCE);
    expect(document.activeElement).toBe(ta);
    expect(spy).not.toHaveBeenCalled();
  });

  it("Empty source previews as Nothing to preview", async () => {
    const user = userEvent.setup();
    render(<Harness initial="" />);
    await user.click(screen.getByRole("tab", { name: "Preview" }));
    expect(screen.getByRole("tabpanel").textContent).toContain("Nothing to preview");
  });

  it("Keyboard and screen-reader access: roles and selected state", async () => {
    const user = userEvent.setup();
    render(<Harness initial="x" />);
    expect(screen.getByRole("tablist")).toBeTruthy();
    const tabs = screen.getAllByRole("tab");
    expect(tabs.map((t) => t.textContent?.trim())).toEqual(["Write", "Preview"]);
    const [write, preview] = tabs;
    expect(write.getAttribute("aria-selected")).toBe("true");
    expect(preview.getAttribute("aria-selected")).toBe("false");
    expect(screen.getByRole("tabpanel").getAttribute("aria-labelledby")).toBe(write.id);
    expect(write.id).not.toBe("");

    await user.click(preview);
    expect(preview.getAttribute("aria-selected")).toBe("true");
    expect(write.getAttribute("aria-selected")).toBe("false");
    expect(screen.getByRole("tabpanel").getAttribute("aria-labelledby")).toBe(preview.id);
  });

  it("Keyboard and screen-reader access: arrow keys move between tabs", async () => {
    const user = userEvent.setup();
    render(<Harness initial="x" />);
    const [write, preview] = screen.getAllByRole("tab");
    write.focus();
    await user.keyboard("{ArrowRight}");
    expect(document.activeElement).toBe(preview);
    await user.keyboard("{ArrowLeft}");
    expect(document.activeElement).toBe(write);
  });

  it("Keyboard and screen-reader access: Enter and Space activate", async () => {
    const user = userEvent.setup();
    render(<Harness initial="x" />);
    const [write, preview] = screen.getAllByRole("tab");
    preview.focus();
    await user.keyboard("{Enter}");
    expect(preview.getAttribute("aria-selected")).toBe("true");
    write.focus();
    await user.keyboard(" ");
    expect(write.getAttribute("aria-selected")).toBe("true");
    expect(preview.getAttribute("aria-selected")).toBe("false");
  });

  it("Safety smoke: previewing an img with onerror yields no img element", async () => {
    const user = userEvent.setup();
    render(<Harness initial="<img src=x onerror=alert(1)>" />);
    await user.click(screen.getByRole("tab", { name: "Preview" }));
    expect(screen.getByRole("tabpanel").querySelector("img")).toBeNull();
  });
});
