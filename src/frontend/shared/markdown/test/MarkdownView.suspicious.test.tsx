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

// Each case: [name, source, visible text, payload strings that must not appear in any href]
type Blocked = [string, string, string, string[]];

function expectBlocked(src: string, text: string, payloads: string[]) {
  const c = view(src);
  expect(c.querySelectorAll("a").length).toBe(0);
  expect(c.textContent).toContain(text);
  for (const el of Array.from(c.querySelectorAll("[href]"))) {
    const href = el.getAttribute("href") ?? "";
    for (const p of payloads) {
      expect(href.toLowerCase()).not.toContain(p.toLowerCase());
    }
  }
}

function enc(cp: number): string {
  const bytes = new TextEncoder().encode(String.fromCodePoint(cp));
  return Array.from(bytes, (b) => "%" + b.toString(16).toUpperCase().padStart(2, "0")).join("");
}
function raw(cp: number): string {
  return String.fromCodePoint(cp);
}

describe("MDR-12 spec scenario: Invisible or direction-changing characters", () => {
  const cases: Blocked[] = [
    ["a: ZWSP in host", "[a](https://exa%E2%80%8Bmple.com/)", "a", ["%E2%80%8B", raw(0x200b)]],
    ["b: RLO in path", "[b](https://example.com/%E2%80%AEfdp.exe)", "b", ["%E2%80%AE", raw(0x202e)]],
    ["c: ZWSP in mailto local part", "[c](mailto:a%E2%80%8B@b.example)", "c", ["%E2%80%8B", raw(0x200b)]],
    ["d: U+2028 in path", "[d](https://example.com/a%E2%80%A8b)", "d", ["%E2%80%A8", raw(0x2028)]],
  ];
  for (const [name, src, text, pay] of cases) {
    it(`renders text, no link: ${name}`, () => expectBlocked(src, text, pay));
  }
});

describe("MDR-12 spec scenario: Suspicious mailto addresses", () => {
  const cases: Blocked[] = [
    ["a: encoded NBSP", "[a](mailto:a%A0b@c.example)", "a", ["%A0", "%C2%A0", raw(0xa0)]],
    ["b: %25 leftover after decode", "[b](mailto:a@b.example%253Fcc=x)", "b", ["%253F", "cc=x"]],
    ["c: IDN domain encoded", "[c](mailto:a@b%C3%BCcher.example)", "c", ["%C3%BC", "ü"]],
    ["d: parentheses in local part", "[d](mailto:a(b)@c.example)", "d", ["a(b)"]],
  ];
  for (const [name, src, text, pay] of cases) {
    it(`renders text, no link: ${name}`, () => expectBlocked(src, text, pay));
  }
});

describe("MDR-12 spec scenario: Ordinary links are unaffected", () => {
  const cases: Array<[string, string, string]> = [
    ["a", "[a](https://example.com/path?q=1#frag)", "https://example.com/path?q=1#frag"],
    ["b", "[b](https://bücher.example/)", ""],
    ["c", "[c](mailto:Dev.Name+tag@Example.com)", ""],
  ];
  for (const [t, src, href] of cases) {
    it(`renders one link with rel: ${t}`, () => {
      const c = view(src);
      const links = c.querySelectorAll("a");
      expect(links.length).toBe(1);
      expect(links[0].getAttribute("rel")).toBe(REL);
      expect(c.textContent).toContain(t);
      if (href) expect(links[0].getAttribute("href")).toBe(href);
    });
  }
});

describe("MDR-12 guards: legitimate percent-encoding and addresses", () => {
  const cases: Array<[string, string, string]> = [
    ["latin accent path", "[g1](https://example.com/caf%C3%A9)", "https://example.com/caf%C3%A9"],
    ["CJK path", "[g2](https://example.com/%E4%B8%AD%E6%96%87)", "https://example.com/%E4%B8%AD%E6%96%87"],
    ["apostrophe local part", "[g3](mailto:o'brien@example.com)", "mailto:o'brien@example.com"],
    ["dots dash underscore", "[g4](mailto:a.b-c_d@sub.example.co)", "mailto:a.b-c_d@sub.example.co"],
  ];
  for (const [name, src, href] of cases) {
    it(`still one link, exact href: ${name}`, () => {
      const c = view(src);
      const links = c.querySelectorAll("a");
      expect(links.length).toBe(1);
      expect(links[0].getAttribute("rel")).toBe(REL);
      expect(links[0].getAttribute("href")).toBe(href);
    });
  }
});

describe("MDR-12 invisible and bidi characters in https host and path", () => {
  const cps: Array<[string, number]> = [
    ["U+200C", 0x200c],
    ["U+200D", 0x200d],
    ["U+2060", 0x2060],
    ["U+FEFF", 0xfeff],
    ["U+202A", 0x202a],
    ["U+202B", 0x202b],
    ["U+202C", 0x202c],
    ["U+202D", 0x202d],
    ["U+202E", 0x202e],
    ["U+2066", 0x2066],
    ["U+2067", 0x2067],
    ["U+2068", 0x2068],
    ["U+2069", 0x2069],
  ];
  for (const [label, cp] of cps) {
    const e = enc(cp);
    const r = raw(cp);
    it(`${label} percent-encoded in path`, () =>
      expectBlocked(`[t](https://example.com/a${e}b)`, "t", [e, r]));
    it(`${label} percent-encoded in host`, () =>
      expectBlocked(`[t](https://exa${e}mple.com/)`, "t", [e, r]));
    it(`${label} raw in path (angle destination)`, () =>
      expectBlocked(`[t](<https://example.com/a${r}b>)`, "t", [e, r]));
    it(`${label} raw in host (angle destination)`, () =>
      expectBlocked(`[t](<https://exa${r}mple.com/>)`, "t", [e, r]));
  }
});

describe("MDR-12 U+2029 and non-ASCII whitespace", () => {
  it("U+2029 percent-encoded in path", () =>
    expectBlocked("[t](https://example.com/a%E2%80%A9b)", "t", ["%E2%80%A9", raw(0x2029)]));
  it("U+2029 raw in path (angle destination)", () =>
    expectBlocked(`[t](<https://example.com/a${raw(0x2029)}b>)`, "t", ["%E2%80%A9", raw(0x2029)]));

  const ws: Array<[string, number]> = [
    ["NBSP U+00A0", 0xa0],
    ["ideographic space U+3000", 0x3000],
    ["thin space U+2009", 0x2009],
  ];
  for (const [label, cp] of ws) {
    const e = enc(cp);
    const r = raw(cp);
    it(`${label} percent-encoded in https path`, () =>
      expectBlocked(`[t](https://example.com/a${e}b)`, "t", [e, r]));
    it(`${label} raw in https path (angle destination)`, () =>
      expectBlocked(`[t](<https://example.com/a${r}b>)`, "t", [e, r]));
    it(`${label} percent-encoded in mailto local part`, () =>
      expectBlocked(`[t](mailto:a${e}b@c.example)`, "t", [e, r]));
    it(`${label} raw in mailto local part (angle destination)`, () =>
      expectBlocked(`[t](<mailto:a${r}b@c.example>)`, "t", [e, r]));
  }
});

describe("MDR-12 mailto address character allowlist", () => {
  it("%25 leftover in local part (a%2540b@c.example decodes once to a%40b)", () =>
    expectBlocked("[t](mailto:a%2540b@c.example)", "t", ["%2540", "a%40b"]));
  it("%25 leftover in domain", () =>
    expectBlocked("[t](mailto:a@b.example%2541)", "t", ["%2541"]));

  const forbidden = ["(", ")", "[", "]", ":"];
  const payloads = [
    "a%28b", "a%29b", "a%5Bb", "a%5Db", "a%3Ab", "a(b", "a)b", "a[b", "a]b", "a:b",
  ];
  for (const ch of forbidden) {
    const e = "%" + ch.charCodeAt(0).toString(16).toUpperCase();
    it(`local part char ${ch} percent-encoded`, () =>
      expectBlocked(`[t](mailto:a${e}b@c.example)`, "t", payloads));
    it(`local part char ${ch} raw (angle destination)`, () =>
      expectBlocked(`[t](<mailto:a${ch}b@c.example>)`, "t", payloads));
  }

  it("IDN domain raw", () => expectBlocked("[t](mailto:a@bücher.example)", "t", ["ü", "%C3%BC", "xn--"]));
  it("IDN domain encoded", () => expectBlocked("[t](mailto:a@b%C3%BCcher.example)", "t", ["%C3%BC", "ü", "xn--"]));
  it("domain with underscore", () => expectBlocked("[t](mailto:a@b_c.example)", "t", ["b_c"]));
});

describe("MDR-12 autolinks and reference-style links", () => {
  it("autolink with raw ZWSP in host", () => {
    const c = view(`<https://exa${raw(0x200b)}mple.com/>`);
    expect(c.querySelectorAll("a").length).toBe(0);
    expect(c.textContent).toContain("mple.com");
    for (const el of Array.from(c.querySelectorAll("[href]"))) {
      expect(el.getAttribute("href")).not.toContain(raw(0x200b));
    }
  });
  it("reference-style link with encoded RLO", () =>
    expectBlocked("[x][r]\n\n[r]: https://example.com/%E2%80%AEfdp.exe", "x", ["%E2%80%AE", raw(0x202e)]));
  it("reference-style link with raw RLO", () =>
    expectBlocked(`[x][r]\n\n[r]: <https://example.com/${raw(0x202e)}fdp.exe>`, "x", ["%E2%80%AE", raw(0x202e)]));
  it("reference-style mailto with encoded ZWSP", () =>
    expectBlocked("[x][r]\n\n[r]: mailto:a%E2%80%8B@b.example", "x", ["%E2%80%8B", raw(0x200b)]));
  it("reference-style mailto with parentheses", () =>
    expectBlocked("[x][r]\n\n[r]: <mailto:a(b)@c.example>", "x", ["a(b)"]));
});

describe("MDR-12 guards: https links keep their percent sequences", () => {
  const cases: Array<[string, string, string]> = [
    ["%2520 stays", "[a](https://example.com/a%2520b)", "https://example.com/a%2520b"],
    ["%20 stays", "[b](https://example.com/a%20b)", "https://example.com/a%20b"],
    ["%25 in query stays", "[c](https://example.com/?q=%25)", "https://example.com/?q=%25"],
  ];
  for (const [name, src, href] of cases) {
    it(`one link, href keeps percent sequence: ${name}`, () => {
      const c = view(src);
      const links = c.querySelectorAll("a");
      expect(links.length).toBe(1);
      expect(links[0].getAttribute("rel")).toBe(REL);
      expect(links[0].getAttribute("href")).toBe(href);
    });
  }
});

describe("MDR-12 guards: mailto allowlist characters stay links", () => {
  const cases: Array<[string, string, string]> = [
    ["symbols local part", "[a](mailto:a+b!$*=^{|}~@c.example)", "mailto:a+b!$*=^{|}~@c.example"],
    ["backtick and apostrophe", "[b](mailto:a`b'c@c.example)", "mailto:a`b'c@c.example"],
    ["letters digits dot dash domain", "[c](mailto:a@sub-1.example-2.co)", "mailto:a@sub-1.example-2.co"],
  ];
  for (const [name, src, href] of cases) {
    it(`one link, same single mailto address: ${name}`, () => {
      const c = view(src);
      const links = c.querySelectorAll("a");
      expect(links.length).toBe(1);
      expect(links[0].getAttribute("rel")).toBe(REL);
      const h = links[0].getAttribute("href") ?? "";
      expect(decodeURIComponent(h)).toBe(href);
      expect(h).not.toMatch(/[?#]/);
    });
  }
});

describe("MDR-12 invisible and bidi characters in mailto local part", () => {
  const cps: Array<[string, number]> = [
    ["U+2060", 0x2060],
    ["U+FEFF", 0xfeff],
    ["U+202E", 0x202e],
    ["U+2066", 0x2066],
  ];
  for (const [label, cp] of cps) {
    const e = enc(cp);
    const r = raw(cp);
    it(`${label} percent-encoded in mailto local part`, () =>
      expectBlocked(`[t](mailto:a${e}b@c.example)`, "t", [e, r]));
    it(`${label} raw in mailto local part (angle destination)`, () =>
      expectBlocked(`[t](<mailto:a${r}b@c.example>)`, "t", [e, r]));
  }
});

describe("MDR-12 U+2028 and U+2029 in mailto", () => {
  const cps: Array<[string, number]> = [
    ["U+2028", 0x2028],
    ["U+2029", 0x2029],
  ];
  for (const [label, cp] of cps) {
    const e = enc(cp);
    const r = raw(cp);
    it(`${label} percent-encoded in mailto local part`, () =>
      expectBlocked(`[t](mailto:a${e}b@c.example)`, "t", [e, r]));
    it(`${label} raw in mailto local part (angle destination)`, () =>
      expectBlocked(`[t](<mailto:a${r}b@c.example>)`, "t", [e, r]));
    it(`${label} percent-encoded in mailto domain`, () =>
      expectBlocked(`[t](mailto:a@c${e}d.example)`, "t", [e, r]));
    it(`${label} raw in mailto domain (angle destination)`, () =>
      expectBlocked(`[t](<mailto:a@c${r}d.example>)`, "t", [e, r]));
  }
});
