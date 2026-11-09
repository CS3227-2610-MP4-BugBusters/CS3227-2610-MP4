import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { MarkdownView } from "../src/index";

afterEach(() => {
  cleanup();
});

const REL = "noopener noreferrer nofollow";

function view(source: string) {
  return render(<MarkdownView source={source} />).container;
}

describe("MDR-09 Encoded header characters or several recipients", () => {
  const blocked: Array<[string, string, string, string]> = [
    ["encoded ? then cc", "[a](mailto:a@b.example%3Fcc=c@d.example)", "a", "c@d.example"],
    ["encoded #", "[b](mailto:a@b.example%23x)", "b", "%23"],
    ["encoded newline bcc", "[c](mailto:a@b%0Abcc:x@y.example)", "c", "x@y.example"],
    ["empty address with encoded ?", "[d](mailto:%3Fbody=hi)", "d", "body=hi"],
    ["comma recipients", "[e](mailto:a@b.example,c@d.example)", "e", "c@d.example"],
    ["lowercase %3f", "[f](mailto:a@b.example%3fcc=c@d.example)", "f", "c@d.example"],
    ["CRLF lowercase", "[g](mailto:a@b.example%0d%0abcc:x@y.example)", "g", "x@y.example"],
    ["CRLF uppercase", "[h](mailto:a@b.example%0D%0Abcc:x@y.example)", "h", "x@y.example"],
    ["encoded &", "[i](mailto:a@b.example%26cc=c@d.example)", "i", "c@d.example"],
    ["encoded comma upper", "[j](mailto:a@b.example%2Cc@d.example)", "j", "c@d.example"],
    ["encoded comma lower", "[k](mailto:a@b.example%2cc@d.example)", "k", "c@d.example"],
    ["encoded TAB", "[l](mailto:a@b.example%09x@y.example)", "l", "x@y.example"],
    ["encoded NUL", "[m](mailto:a@b.example%00x@y.example)", "m", "x@y.example"],
  ];
  for (const [name, src, text, payload] of blocked) {
    it(`renders no link and shows text: ${name}`, () => {
      const c = view(src);
      expect(c.querySelectorAll("a").length).toBe(0);
      expect(c.textContent).toContain(text);
      for (const el of Array.from(c.querySelectorAll("[href]"))) {
        expect(el.getAttribute("href")).not.toContain(payload);
      }
    });
  }

  // Double encoding: percent-decoding once yields the literal text "%3F",
  // which is not "?" so the spec does not require blocking. If it renders as
  // a link, the href must still hold exactly one address with no query.
  it("double-encoded %253F never yields a decoded query in an href", () => {
    const c = view("[n](mailto:a@b.example%253Fcc=c@d.example)");
    for (const el of Array.from(c.querySelectorAll("a"))) {
      const href = el.getAttribute("href") ?? "";
      expect(href.startsWith("mailto:")).toBe(true);
      expect(href).not.toMatch(/[?#,]/);
      expect(el.getAttribute("rel")).toBe(REL);
    }
    expect(c.textContent).toContain("n");
  });
});

describe("MDR-09 mailto guards against over-blocking", () => {
  it("plain address renders one link", () => {
    const links = view("[m](mailto:dev@example.com)").querySelectorAll("a");
    expect(links.length).toBe(1);
    expect(links[0].getAttribute("href")).toBe("mailto:dev@example.com");
    expect(links[0].getAttribute("rel")).toBe(REL);
  });

  it("plus-tag address renders one link", () => {
    const links = view("[m2](mailto:Dev.Name+tag@Example.com)").querySelectorAll("a");
    expect(links.length).toBe(1);
    expect(links[0].getAttribute("href")).toBe("mailto:Dev.Name+tag@Example.com");
    expect(links[0].getAttribute("rel")).toBe(REL);
  });

  it("literal query is still stripped", () => {
    const links = view("[m3](mailto:dev@example.com?cc=x)").querySelectorAll("a");
    expect(links.length).toBe(1);
    expect(links[0].getAttribute("href")).toBe("mailto:dev@example.com");
    expect(links[0].getAttribute("rel")).toBe(REL);
  });
});
