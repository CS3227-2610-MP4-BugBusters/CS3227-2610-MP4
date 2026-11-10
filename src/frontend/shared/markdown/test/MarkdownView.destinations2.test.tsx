import { cleanup, render, waitFor } from "@testing-library/react";
import type { Element, Root } from "hast";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MarkdownView, MarkdownParserProvider, inThreadRunner } from "../src/index";
import type { MarkdownRunner } from "../src/markdownRunner";
import { toHast } from "../src/MarkdownView.pipeline";

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const realRunner: MarkdownRunner = {
  parse: async (source: string, prefix: string) => ({ kind: "ok", hast: toHast(source, prefix) }),
} as MarkdownRunner;

type Path = "in-thread" | "worker";
const paths: Path[] = ["in-thread", "worker"];

function hastRunner(hast: Root): MarkdownRunner {
  return { parse: async () => ({ kind: "ok", hast }) } as MarkdownRunner;
}

async function view(source: string, path: Path, runnerOverride?: MarkdownRunner): Promise<HTMLElement> {
  const runner = runnerOverride ?? (path === "worker" ? realRunner : inThreadRunner);
  const { container } = render(
    <MarkdownParserProvider runner={runner}>
      <MarkdownView source={source} />
    </MarkdownParserProvider>,
  );
  if (path === "worker") {
    await waitFor(() => expect(container.querySelector("pre")).toBeNull(), { timeout: 5000 });
  }
  expect(container.querySelector("a")).toBeNull();
  expect(container.querySelector("[href]")).toBeNull();
  expect(container.querySelector("[style]")).toBeNull();
  for (const b of Array.from(container.querySelectorAll("bdi"))) {
    expect(b.getAttributeNames()).toEqual(["dir"]);
    expect(b.getAttribute("dir")).toBe("ltr");
  }
  return container;
}

function bdiTexts(c: HTMLElement): string[] {
  return Array.from(c.querySelectorAll("p bdi")).map((b) => b.textContent ?? "");
}

function ptexts(c: HTMLElement): string[] {
  return Array.from(c.querySelectorAll("p")).map((p) => p.textContent ?? "");
}

function checkBdi(name: string, source: string, expectedBdi: string[]) {
  for (const path of paths) {
    it(`${name} [${path}]`, async () => {
      const c = await view(source, path);
      expect(bdiTexts(c)).toEqual(expectedBdi);
    });
  }
}

function checkText(name: string, source: string, expected: string[]) {
  for (const path of paths) {
    it(`${name} [${path}]`, async () => {
      expect(ptexts(await view(source, path))).toEqual(expected);
    });
  }
}

const hex = (ch: string) => ch.codePointAt(0)!.toString(16).toUpperCase().padStart(4, "0");

describe("MDR-15 repeated references to a costly destination", () => {
  // Spec scenario sources. Their shown text is far above MAX_TEXT_CHARS, so under
  // D16 the view must finish quickly as plain text, not formatted (amended 10 Nov).
  const dos = "[r]: <" + "%E2%80".repeat(800) + ">\n\n" + "[r] ".repeat(3000);
  const valid = "[r]: <" + "%E2%80%8B".repeat(530) + ">\n\n" + "[r] ".repeat(3000);
  // In-thread variants within MAX_IN_THREAD_SOURCE (4,000), so they are really parsed (D15).
  const dosSmall = "[r]: <" + "%E2%80".repeat(300) + ">\n\n" + "[r] ".repeat(500);
  const validSmall = "[r]: <" + "%E2%80%8B".repeat(200) + ">\n\n" + "[r] ".repeat(500);

  it("toHast on the 3000-reference source takes under 1 s", () => {
    const t0 = performance.now();
    toHast(dos, "p-");
    const ms = performance.now() - t0;
    console.log(`TOHAST_MS ${ms.toFixed(0)}`);
    expect(ms).toBeLessThan(1000);
  }, 120_000);

  it("guard: in-thread variants are within the in-thread cap", () => {
    expect(dosSmall.length).toBeLessThanOrEqual(4000);
    expect(validSmall.length).toBeLessThanOrEqual(4000);
  });

  /** Time from render until the parse has settled and the view shows its final state. */
  async function settledMs(source: string, path: Path): Promise<{ ms: number; c: HTMLElement }> {
    let done!: () => void;
    const settled = new Promise<void>((resolve) => (done = resolve));
    const base = path === "worker" ? realRunner : inThreadRunner;
    const runner = {
      parse: async (src: string, prefix: string, signal?: AbortSignal) => {
        try {
          return await base.parse(src, prefix, signal);
        } finally {
          done();
        }
      },
    } as MarkdownRunner;
    const t0 = performance.now();
    const { container } = render(
      <MarkdownParserProvider runner={runner}>
        <MarkdownView source={source} />
      </MarkdownParserProvider>,
    );
    if (path === "worker") await settled;
    await new Promise((resolve) => setTimeout(resolve, 0));
    return { ms: performance.now() - t0, c: container };
  }

  for (const [name, source, path] of [
    ["invalid-escape 3000 references", dos, "worker"],
    ["valid %E2%80%8B x530 with 3000 references", valid, "worker"],
    ["invalid-escape 500 references within the cap", dosSmall, "in-thread"],
    ["valid %E2%80%8B x200 with 500 references within the cap", validSmall, "in-thread"],
  ] as const) {
    it(`${name} finish under 1 s as plain text [${path}]`, async () => {
      const { ms, c } = await settledMs(source, path);
      console.log(`DOS_MS ${path} ${name} ${ms.toFixed(0)}`);
      expect(ms).toBeLessThan(1000);
      expect(c.querySelector("pre")?.textContent).toBe(source);
      expect(c.querySelector("bdi")).toBeNull();
    }, 120_000);
  }
});

describe("MDR-15 reordering and look-alikes are neutralised", () => {
  for (const path of paths) {
    it(`bare URL with raw U+202E shows marker, no raw char [${path}]`, async () => {
      const c = await view("https://evil.example/‮fdp.exe", path);
      const t = ptexts(c).join("");
      expect(t).not.toContain("‮");
      expect(t).toContain("https://evil.example/<U+202E>fdp.exe");
      for (const b of bdiTexts(c)) expect(b).toMatch(/^ \(.*\)$/);
    });
  }
  checkBdi("link text U+202E: destination in bdi", "[‮](moc.knab//:sptth)", [" (moc.knab//:sptth)"]);
  checkBdi("U+3164 in destination shows marker in bdi", "[x](https://e.x/aㅤb)", [" (https://e.x/a<U+3164>b)"]);
  checkBdi(
    "look-alike brackets and space in destination are percent-encoded in bdi",
    "[x](<https://evil.example/⟯ ⟮https://bank.example>)",
    [" (https://evil.example/%E2%9F%AF%20%E2%9F%AEhttps://bank.example)"],
  );
  checkBdi("space in angle destination becomes %20", "[x](<https://e.x/a b>)", [" (https://e.x/a%20b)"]);
});

describe("MDR-15 escaped autolink text", () => {
  for (const [name, src] of [
    ["bare URL", "https://e.x/​a"],
    ["angle autolink", "<https://e.x/​a>"],
  ] as const) {
    for (const path of paths) {
      it(`${name} with hidden char shows escaped text [${path}]`, async () => {
        const c = await view(src, path);
        const t = ptexts(c)[0];
        expect(t).not.toContain("​");
        expect(t.startsWith("https://e.x/<U+200B>a")).toBe(true);
      });
    }
  }
});

const finding3: string[] = [
  "ᅟ", "ᅠ", "ㅤ", "ﾠ", "⠀",
  "️", "︀", String.fromCodePoint(0xe0100), "᠋",
  "͏", "឴", "́", "̸",
  "\u0001", "\u001B", "\u001C", "\u0081",
];

describe("MDR-15 markers for controls, combining marks and default-ignorables", () => {
  for (const ch of finding3) {
    checkBdi(`raw U+${hex(ch)} in angle destination`, `[t](<https://e.x/${ch}a>)`, [
      ` (https://e.x/<U+${hex(ch)}>a)`,
    ]);
    checkBdi(`percent-encoded U+${hex(ch)}`, `[t](https://e.x/${encodeURIComponent(ch)}a)`, [
      ` (https://e.x/<U+${hex(ch)}>a)`,
    ]);
  }
  checkBdi("lone high surrogate raw in angle destination", "[t](<https://e.x/\uD800a>)", [
    " (https://e.x/<U+D800>a)",
  ]);
  checkBdi("lone low surrogate raw in angle destination", "[t](<https://e.x/\uDC00a>)", [
    " (https://e.x/<U+DC00>a)",
  ]);
});

describe("MDR-15 look-alike brackets are percent-encoded", () => {
  const brackets = ["）", "（", "﴾", "﴿", "﹙", "﹚", "⁽", "⁾", "⟮", "⟯"];
  for (const ch of brackets) {
    checkBdi(`U+${hex(ch)} in destination`, `[t](<https://e.x/${ch}a>)`, [
      ` (https://e.x/${encodeURIComponent(ch)}a)`,
    ]);
  }
});


// Markdown cannot produce input/br/hr inside link text with text children, so these are worker-only (fake hast).
describe("MDR-15 void elements contribute no displayed text", () => {
  const text = (value: string) => ({ type: "text", value }) as const;
  function hastWith(tag: string, props: Element["properties"]): Root {
    const inner: Element = { type: "element", tagName: tag, properties: props, children: [text("https://evil")] };
    const a: Element = { type: "element", tagName: "a", properties: { href: "https://evil" }, children: [inner] };
    return { type: "root", children: [{ type: "element", tagName: "p", properties: {}, children: [a] }] };
  }
  const cases: [string, Root][] = [
    ["input", hastWith("input", { type: "checkbox" })],
    ["br", hastWith("br", {})],
    ["hr", hastWith("hr", {})],
  ];
  for (const [tag, hast] of cases) {
    it(`a > ${tag} > text still shows the suffix [worker]`, async () => {
      const c = await view("whatever", "worker", hastRunner(hast));
      expect(bdiTexts(c)).toEqual([" (https://evil)"]);
      expect((ptexts(c)[0] ?? "").endsWith(" (https://evil)")).toBe(true);
    });
  }
});

describe("MDR-15 guards", () => {
  checkText(
    "MDR-14 hidden characters and parentheses read as before",
    "[invoice](https://evil.example/%E2%80%AEfdp.exe)\n\n[x](https://e.x/%E2%80%8Ba)\n\n[a](<b) (https://bank.example>)",
    [
      "invoice (https://evil.example/<U+202E>fdp.exe)",
      "x (https://e.x/<U+200B>a)",
      "a (b%29%20%28https://bank.example)", // MDR-15: a space shows as %20 (amended 10 Nov)
    ],
  );
  checkText("MDR-14 image alt text", "[https://evil![.bank.example](i)](https://evil)", [
    "https://evil.bank.example (https://evil)",
  ]);
  checkText(
    "MDR-14 ordinary destinations",
    "[docs](https://example.com/a?q=1)\n\n[b](https://bücher.example/)",
    ["docs (https://example.com/a?q=1)", "b (https://bücher.example/)"],
  );
  checkText("literal %20 stays %20", "[f](https://e.x/a%20b)", ["f (https://e.x/a%20b)"]);
  checkText("CJK in path unchanged", "[j](https://e.x/日本語)", ["j (https://e.x/日本語)"]);
  checkText("link text with U+202E is not altered", "[a‮b](https://e.x/)", ["a‮b (https://e.x/)"]);
  checkBdi("ordinary link suffix sits in a bdi", "[docs](https://example.com/a?q=1)", [" (https://example.com/a?q=1)"]);
  checkText("autolink without hidden chars shows address once", "<https://example.com/x>", ["https://example.com/x"]);
});
