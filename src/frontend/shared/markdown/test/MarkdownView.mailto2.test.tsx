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

// NBSP (%A0) is deliberately not tested: the spec says "whitespace" without
// defining it, and NBSP is not ASCII whitespace, so either outcome is arguable.
describe("MDR-11 Other recipient separators and malformed addresses", () => {
  const blocked: Array<[string, string, string, string]> = [
    ["raw semicolon", "[a](mailto:a@b.example;c@d.example)", "a", "c@d.example"],
    ["encoded %3B", "[b](mailto:a@b.example%3Bc@d.example)", "b", "c@d.example"],
    ["encoded %20", "[c](mailto:a@b.example%20c@d.example)", "c", "c@d.example"],
    ["two @", "[d](mailto:a@b@c.example)", "d", "c.example"],
    ["encoded %3E%3C", "[e](mailto:a@b.example%3E%3Cc@d.example)", "e", "c@d.example"],
    ["no @ at all", "[f](mailto:nobody)", "f", "nobody"],
    ["lowercase %3b", "[g](mailto:a@b.example%3bc@d.example)", "g", "c@d.example"],
    ["raw space in angle destination", "[h](<mailto:a@b.example c@d.example>)", "h", "c@d.example"],
    ["encoded TAB %09", "[i](mailto:a@b.example%09c@d.example)", "i", "c@d.example"],
    ["encoded quote %22", "[j](mailto:a@b.example%22c@d.example)", "j", "c@d.example"],
    ["encoded backslash %5C", "[k](mailto:a@b.example%5Cc@d.example)", "k", "c@d.example"],
    ["encoded backslash %5c", "[k2](mailto:a@b.example%5cc@d.example)", "k2", "c@d.example"],
    ["encoded slash %2F", "[l](mailto:a@b.example%2Fc@d.example)", "l", "c@d.example"],
    ["encoded lone < %3C", "[m](mailto:a@b.example%3Cc@d.example)", "m", "c@d.example"],
    ["encoded lone > %3E", "[m2](mailto:a@b.example%3Ec@d.example)", "m2", "c@d.example"],
    ["@ only", "[n](mailto:@)", "n", "mailto:@"],
    ["trailing @ (a@)", "[o](mailto:a@)", "o", "mailto:a@"],
    ["leading @ (@b.example)", "[p](mailto:@b.example)", "p", "@b.example"],
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
});

describe("MDR-11 mailto valid addresses still link", () => {
  const ok = [
    "mailto:dev@example.com",
    "mailto:Dev.Name+tag@Example.com",
    "mailto:o'brien@example.com",
    "mailto:a-b_c@sub.example.co",
  ];
  for (const href of ok) {
    it(`renders exactly one link: ${href}`, () => {
      const links = view(`[m](${href})`).querySelectorAll("a");
      expect(links.length).toBe(1);
      expect(links[0].getAttribute("href")).toBe(href);
      expect(links[0].getAttribute("rel")).toBe(REL);
    });
  }
});
