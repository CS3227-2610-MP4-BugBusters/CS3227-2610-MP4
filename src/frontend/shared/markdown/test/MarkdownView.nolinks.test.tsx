import { cleanup, render, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { MarkdownView, MarkdownParserProvider } from "../src/index";
import type { MarkdownRunner } from "../src/markdownRunner";
import { toHast } from "../src/MarkdownView.pipeline";

afterEach(cleanup);

function view(source: string) {
  return render(<MarkdownView source={source} />).container;
}

function expectNoLinks(c: Element) {
  expect(c.querySelectorAll("a")).toHaveLength(0);
  expect(c.querySelector("[href]")).toBeNull();
  expect(c.querySelector("[rel]")).toBeNull();
}

function count(hay: string, needle: string) {
  return hay.split(needle).length - 1;
}

describe("MDR-04 exact text for links", () => {
  it.each([
    ["[docs](https://example.com/a)", "docs (https://example.com/a)"],
    ["[mail](mailto:dev@example.com)", "mail (mailto:dev@example.com)"],
    ["[ref][r]\n\n[r]: https://example.com/r\n", "ref (https://example.com/r)"],
  ])("%j shows %j", (src, expected) => {
    const c = view(src);
    expectNoLinks(c);
    expect(c.textContent?.trim()).toBe(expected);
  });

  it("title is dropped, never rendered", () => {
    const c = view('[t](https://example.com/a "secret title")');
    expectNoLinks(c);
    expect(c.textContent).not.toContain("secret title");
    expect(c.innerHTML).not.toContain("title=");
  });
});

describe("MDR-04 autolinks show the address exactly once", () => {
  it.each([
    ["angle autolink", "<https://example.com/x>", "https://example.com/x"],
    ["bare https", "https://example.com/x", "https://example.com/x"],
    ["www", "www.example.com", "www.example.com"],
    ["bare email", "dev@example.com", "dev@example.com"],
  ])("%s", (_n, src, addr) => {
    const c = view(src);
    expectNoLinks(c);
    expect(c.textContent?.trim()).toBe(addr);
    expect(count(c.textContent ?? "", addr)).toBe(1);
  });
});

describe("MDR-04 hostile destinations are inert text, destination shown", () => {
  it.each([
    "javascript:alert(1)",
    "JAVASCRIPT:alert(1)",
    "JaVaScRiPt:alert(1)",
    "data:text/html,hi",
    "DATA:text/html,hi",
    "vbscript:msgbox(1)",
    "VBSCRIPT:msgbox(1)",
    "file:///etc/passwd",
    "FILE:///etc/passwd",
    "/relative/path",
    "relative/page.html",
    "//example.com/x",
    "#top",
  ])("destination %s", (dest) => {
    const c = view(`[t](${dest})`);
    expectNoLinks(c);
    expect(c.textContent).toContain(`t (${dest.replace(/\(/g, "%28").replace(/\)/g, "%29")})`);
  });

  const payloads: Array<[string, string]> = [
    ["credentials", "[a](https://user:pass@evil.example/)"],
    ["userinfo lookalike", "[a](https://good.example@evil.example/)"],
    ["entity-encoded @", "[a](https://good.example&#64;evil.example/)"],
    ["credentials autolink", "<https://user:pass@evil.example/>"],
    ["credentials reference", "[a][r]\n\n[r]: https://u:p@evil.example/\n"],
    ["mailto query", "[a](mailto:dev@example.com?cc=boss@example.com&body=hi#x)"],
    ["mailto encoded newline", "[a](mailto:a@b%0Abcc:x@y.example)"],
    ["mailto comma recipients", "[a](mailto:a@b.example,c@d.example)"],
    ["mailto no @", "[a](mailto:nobody)"],
    ["mailto parentheses", "[a](mailto:a(b)@c.example)"],
    ["mailto IDN", "[a](mailto:a@b%C3%BCcher.example)"],
    ["ZWSP in host", "[a](https://exa%E2%80%8Bmple.com/)"],
    ["RLO in path", "[a](https://example.com/%E2%80%AEfdp.exe)"],
    ["raw RLO in angle destination", "[a](<https://example.com/‮fdp.exe>)"],
    ["raw ZWSP in angle destination", "[a](<https://exa​mple.com/>)"],
    ["NBSP in angle destination", "[a](<https://example.com/a b>)"],
    ["punycode IDN", "[a](https://xn--bcher-kva.example/)"],
    ["IDN raw", "[a](https://bücher.example/)"],
    ["plain https with query", "[a](https://example.com/p?q=1#frag)"],
    ["image inside link", "[![alt](https://tracker.example/p.png)](https://example.com/)"],
    ["nested emphasis", "[**bold** text](https://example.com/)"],
  ];
  it.each(payloads)("payload: %s", (_n, src) => {
    const c = view(src);
    expectNoLinks(c);
    expect(c.querySelector("img")).toBeNull();
  });
});

describe("MDR-04 worker path renders no links", () => {
  const realRunner: MarkdownRunner = {
    parse: async (source, prefix) => ({ kind: "ok", hast: toHast(source, prefix) }),
  };

  it.each([
    ["inline", "[docs](https://example.com/a)", "docs (https://example.com/a)"],
    ["autolink", "<https://example.com/x>", "https://example.com/x"],
    ["dangerous", "[t](javascript:alert(1))", "t (javascript:alert%281%29)"],
    ["footnote", "Note[^1].\n\n[^1]: Def.\n", "Def."],
  ])("%s", async (_n, src, expected) => {
    const { container } = render(
      <MarkdownParserProvider runner={realRunner}>
        <MarkdownView source={src} />
      </MarkdownParserProvider>,
    );
    await waitFor(() => expect(container.querySelector("pre")).toBeNull());
    expectNoLinks(container);
    expect(container.textContent).toContain(expected);
  });
});
