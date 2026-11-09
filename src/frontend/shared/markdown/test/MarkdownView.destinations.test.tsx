import { act, cleanup, render, waitFor } from "@testing-library/react";
import type { Element, Root } from "hast";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MarkdownView, MarkdownParserProvider, inThreadRunner } from "../src/index";
import type { MarkdownRunner } from "../src/markdownRunner";
import { rehypeNoLinks, toHast } from "../src/MarkdownView.pipeline";

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const realRunner: MarkdownRunner = {
  parse: async (source: string, prefix: string) => ({ kind: "ok", hast: toHast(source, prefix) }),
} as MarkdownRunner;

type Path = "in-thread" | "worker";
const paths: Path[] = ["in-thread", "worker"];

async function paragraphs(source: string, path: Path): Promise<string[]> {
  const runner = path === "worker" ? realRunner : inThreadRunner;
  const { container } = render(
    <MarkdownParserProvider runner={runner}>
      <MarkdownView source={source} />
    </MarkdownParserProvider>,
  );
  if (path === "worker") {
    await waitFor(() => expect(container.querySelector("pre")).toBeNull());
  }
  expect(container.querySelector("a")).toBeNull();
  expect(container.querySelector("[href]")).toBeNull();
  return Array.from(container.querySelectorAll("p")).map((p) => p.textContent ?? "");
}

function check(name: string, source: string, expected: string[]) {
  for (const path of paths) {
    it(`${name} [${path}]`, async () => {
      expect(await paragraphs(source, path)).toEqual(expected);
    });
  }
}

describe("MDR-14 scenarios", () => {
  check(
    "Image alt text counts as link text",
    "[https://evil![.bank.example](i)](https://evil)",
    ["https://evil.bank.example (https://evil)"],
  );
  check(
    "Hidden characters and parentheses are visible",
    "[invoice](https://evil.example/%E2%80%AEfdp.exe)\n\n[x](https://e.x/%E2%80%8Ba)\n\n[a](<b) (https://bank.example>)",
    [
      "invoice (https://evil.example/<U+202E>fdp.exe)",
      "x (https://e.x/<U+200B>a)",
      "a (b%29 %28https://bank.example)",
    ],
  );
  check(
    "Ordinary destinations are unchanged",
    "[docs](https://example.com/a?q=1)\n\n[b](https://bücher.example/)",
    ["docs (https://example.com/a?q=1)", "b (https://bücher.example/)"],
  );
});

describe("MDR-14 alt text variants", () => {
  check("alt at start", "[![A](i)B](https://evil)", ["AB (https://evil)"]);
  check("alt in middle", "[A![M](i)B](https://evil)", ["AMB (https://evil)"]);
  check("alt at end", "[A![B](i)](https://evil)", ["AB (https://evil)"]);
  check("alt-only link equal to destination shows address once", "[![https://e.x/p](i)](https://e.x/p)", [
    "https://e.x/p",
  ]);
});

const hidden: [string, string][] = [
  ["200B", "​"],
  ["200E", "‎"],
  ["202E", "‮"],
  ["2066", "⁦"],
  ["FEFF", "﻿"],
  ["00AD", "­"],
  ["2028", " "],
  ["2029", " "],
  ["00A0", " "],
  ["3000", "　"],
  ["E0041", String.fromCodePoint(0xe0041)],
];

describe("MDR-14 hidden characters", () => {
  for (const [hex, ch] of hidden) {
    check(`raw U+${hex} in angle destination`, `[t](<https://e.x/${ch}a>)`, [`t (https://e.x/<U+${hex}>a)`]);
    check(`percent-encoded U+${hex}`, `[t](https://e.x/${encodeURIComponent(ch)}a)`, [
      `t (https://e.x/<U+${hex}>a)`,
    ]);
  }
});

describe("MDR-14 parentheses", () => {
  check("raw parens in angle destination", "[a](<https://e.x/b(c)>)", ["a (https://e.x/b%28c%29)"]);
  check("percent-encoded parens", "[a](https://e.x/b%28c%29)", ["a (https://e.x/b%28c%29)"]);
  check(
    "equal text with hidden character still shows suffix",
    "[https://e.x/​a](<https://e.x/​a>)",
    ["https://e.x/​a (https://e.x/<U+200B>a)"],
  );
});

describe("MDR-14 guards", () => {
  check("IDN host unchanged", "[b](https://bücher.example/x)", ["b (https://bücher.example/x)"]);
  check("%20 stays as written", "[f](https://e.x/a%20b)", ["f (https://e.x/a%20b)"]);
  check("CJK in path unchanged", "[j](https://e.x/日本語)", ["j (https://e.x/日本語)"]);
  check("link text containing U+202E is unchanged", "[a‮b](https://e.x/)", ["a‮b (https://e.x/)"]);
  check("parentheses in link text are not escaped", "[a (b)](https://x.example/)", ["a (b) (https://x.example/)"]);
  check("autolinks show the address once", "<https://example.com/x>", ["https://example.com/x"]);
  check("bare url shows the address once", "https://example.com/y", ["https://example.com/y"]);
});

const DEPTH = 5000;

function chain(depth: number, withSiblings: boolean): Root {
  const leaf = { type: "text", value: "DEEPEST" } as const;
  let node: Element = {
    type: "element",
    tagName: "a",
    properties: { href: "h" },
    children: [leaf],
  };
  for (let i = 1; i < depth; i++) {
    node = {
      type: "element",
      tagName: "a",
      properties: { href: "h" },
      children: withSiblings
        ? [{ type: "text", value: "12345678901234567890" }, node, { type: "text", value: "y" }]
        : [node],
    };
  }
  return {
    type: "root",
    children: [{ type: "element", tagName: "p", properties: {}, children: [node] }],
  };
}

function fakeRunner(hast: Root): MarkdownRunner {
  return { parse: async () => ({ kind: "ok", hast }) } as MarkdownRunner;
}

describe("linear unlink of nested a in worker hast", () => {
  it("depth 5000 chain renders without a", async () => {
    const hast = chain(5000, true);
    const { container } = render(
      <MarkdownParserProvider runner={fakeRunner(hast)}>
        <MarkdownView source="whatever" />
      </MarkdownParserProvider>,
    );
    await act(async () => {
      await new Promise((r) => setTimeout(r, 300));
    });
    expect(container.querySelector("a")).toBeNull();
    expect(
      (container.textContent ?? "").includes("DEEPEST") || container.querySelector("pre")?.textContent === "whatever",
    ).toBe(true);
  });

  it("unlink of a nested a chain runs in linear time", () => {
    const tree = chain(DEPTH, true);
    rehypeNoLinks()({ type: "root", children: [{ type: "element", tagName: "p", properties: {}, children: [{ type: "element", tagName: "a", properties: { href: "h" }, children: [{ type: "text", value: "w" }] }] }] });
    const t0 = performance.now();
    rehypeNoLinks()(tree);
    const elapsed = performance.now() - t0;
    console.log(`UNLINK_MS depth=${DEPTH} ${elapsed.toFixed(1)}`);
    let sawA = false;
    let sawDeep = false;
    const stack: unknown[] = [tree];
    while (stack.length) {
      const n = stack.pop() as { type: string; tagName?: string; value?: string; children?: unknown[] };
      if (n.type === "element" && n.tagName === "a") sawA = true;
      if (n.type === "text" && n.value === "DEEPEST") sawDeep = true;
      if (n.children) for (const c of n.children) stack.push(c);
    }
    expect(sawA).toBe(false);
    expect(sawDeep).toBe(true);
    expect(elapsed).toBeLessThan(100);
  });

  it("depth 20000 chain within node cap does not throw and has no a", async () => {
    const hast = chain(20000, false);
    const { container } = render(
      <MarkdownParserProvider runner={fakeRunner(hast)}>
        <MarkdownView source="whatever" />
      </MarkdownParserProvider>,
    );
    await act(async () => {
      await new Promise((r) => setTimeout(r, 300));
    });
    // either rendered without a, or plain-text fallback
    expect(
      (container.textContent ?? "").includes("DEEPEST") || container.querySelector("pre")?.textContent === "whatever",
    ).toBe(true);
    expect(container.querySelector("a")).toBeNull();
  });
});
