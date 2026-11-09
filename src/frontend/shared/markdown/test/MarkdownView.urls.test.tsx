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

describe("MDR-09 Link destination hardening", () => {
  describe("Credentials in a link", () => {
    const cases: Array<[string, string, string]> = [
      ["user:pass", "[a](https://user:pass@evil.example/)", "a"],
      ["host-lookalike userinfo", "[b](https://good.example@evil.example/)", "b"],
      ["entity-encoded @", "[c](https://good.example&#64;evil.example/)", "c"],
      ["http user only", "[d](http://user@evil.example/)", "d"],
      ["autolink", "<https://user:pass@evil.example/>", "user:pass@evil.example"],
      ["reference-style", "[e][r]\n\n[r]: https://u:p@evil.example/\n", "e"],
    ];
    for (const [name, src, text] of cases) {
      it(`renders no link and shows text: ${name}`, () => {
        const c = view(src);
        expect(c.querySelectorAll("a").length).toBe(0);
        expect(c.textContent).toContain(text);
        for (const el of Array.from(c.querySelectorAll("[href]"))) {
          expect(el.getAttribute("href")).not.toContain("evil.example");
        }
      });
    }
  });

  describe("Prefilled email", () => {
    it("strips query and fragment from mailto", () => {
      const c = view("[mail](mailto:dev@example.com?cc=boss@example.com&body=hi#x)");
      const links = c.querySelectorAll("a");
      expect(links.length).toBe(1);
      expect(links[0].getAttribute("href")).toBe("mailto:dev@example.com");
      expect(links[0].getAttribute("rel")).toBe(REL);
    });

    it("keeps address text as given before the query", () => {
      const c = view("[m](mailto:Dev@Example.com?subject=x)");
      const links = c.querySelectorAll("a");
      expect(links.length).toBe(1);
      expect(links[0].getAttribute("href")).toBe("mailto:Dev@Example.com");
    });
  });

  describe("Guards against over-blocking", () => {
    it("https link keeps query and fragment", () => {
      const c = view("[ok](https://example.com/path?q=1#frag)");
      const links = c.querySelectorAll("a");
      expect(links.length).toBe(1);
      const href = links[0].getAttribute("href")!;
      expect(href).toContain("?q=1");
      expect(href).toContain("#frag");
      expect(links[0].getAttribute("rel")).toBe(REL);
    });

    it("plain mailto is still a link", () => {
      const c = view("[m](mailto:a@b.example)");
      const links = c.querySelectorAll("a");
      expect(links.length).toBe(1);
      expect(links[0].getAttribute("href")).toBe("mailto:a@b.example");
      expect(links[0].getAttribute("rel")).toBe(REL);
    });
  });
});
