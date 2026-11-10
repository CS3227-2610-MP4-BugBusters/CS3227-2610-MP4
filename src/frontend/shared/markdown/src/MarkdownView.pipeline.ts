import type { Element, ElementContent, Root, RootContent } from "hast";
import remarkGfm from "remark-gfm";
import remarkParse from "remark-parse";
import remarkRehype, { defaultHandlers, type Options as RemarkRehypeOptions } from "remark-rehype";
import { unified } from "unified";

// Pure Markdown-to-hast pipeline (design D11). No DOM and no React, so it runs
// unchanged in the parse worker. The main thread treats its output as
// untrusted and re-applies every safety rule at render time (MarkdownView).

// Generated IDs (MDR-08, design D2). Each view adds its own instance segment
// after ID_PREFIX (see MarkdownView).
export const ID_PREFIX = "user-content-";

// No links (MDR-04, MDR-08, design D12): the output never contains an `a`.

type Handler = NonNullable<NonNullable<RemarkRehypeOptions["handlers"]>["link"]>;

/** An image displays only its alt text (MDR-03). */
function imageText(node: Element): string {
  const alt = node.properties.alt;
  return typeof alt === "string" ? alt : "";
}

/** Elements that display no children (MDR-15), so their text is never shown. */
const VOID_TAGS = new Set(["br", "hr", "img", "input"]);

/**
 * The text as displayed (MDR-14): text, raw HTML shown as text, and image alt
 * text. Iterative, so deep nesting cannot overflow the stack.
 */
function textOf(nodes: readonly ElementContent[]): string {
  let text = "";
  const stack: Array<{ items: readonly ElementContent[]; index: number }> = [{ items: nodes, index: 0 }];
  while (stack.length > 0) {
    const top = stack[stack.length - 1];
    if (top.index >= top.items.length) {
      stack.pop();
      continue;
    }
    const node = top.items[top.index++];
    if (node.type === "text" || node.type === "raw") text += node.value;
    else if (node.type === "element") {
      if (node.tagName === "img") text += imageText(node);
      else if (!VOID_TAGS.has(node.tagName)) stack.push({ items: node.children, index: 0 });
    }
  }
  return text;
}

// Shown destinations (MDR-14, MDR-15). Hidden characters appear as `<U+XXXX>`,
// whether raw or percent-encoded as UTF-8: Unicode format characters (Cf),
// controls (Cc), lone surrogates (Cs), combining marks (M), default-ignorable
// code points (which include the Hangul fillers), U+2028, U+2029, U+2800 and
// non-ASCII whitespace. `(` and `)` appear as `%28` and `%29`, and a space and
// any non-ASCII bracket appear percent-encoded. Everything else is as written.
// The suffix sits in `bdi dir="ltr"` so it cannot reorder the text around it.

const HIDDEN_CLASS = String.raw`[\p{Cf}\p{Cc}\p{Cs}\p{M}\p{Default_Ignorable_Code_Point}\p{Zs}\p{Zl}\p{Zp}⠀]`;
const HIDDEN = new RegExp(String.raw`^(?! )${HIDDEN_CLASS}$`, "u");
const HAS_HIDDEN = new RegExp(String.raw`(?! )${HIDDEN_CLASS}`, "u");
const HIDDEN_ALL = new RegExp(String.raw`(?! )${HIDDEN_CLASS}`, "gu");
const DESTINATION_PART = new RegExp(
  String.raw`(?:%[\dA-Fa-f]{2})+|[() ]|(?! )${HIDDEN_CLASS}|(?![\x00-\x7F])[\p{Ps}\p{Pe}]`,
  "gu",
);

function codeOf(char: string): string {
  return `<U+${char.codePointAt(0)!.toString(16).toUpperCase().padStart(4, "0")}>`;
}

function hexByte(run: string, at: number): number {
  return parseInt(run.slice(at + 1, at + 3), 16);
}

/**
 * The character encoded as UTF-8 by the `%XX` escapes at `run[i]`, and how many
 * escapes it uses, or undefined when they are not valid UTF-8. It never throws,
 * so a long invalid run stays linear.
 */
function decodeUtf8(run: string, i: number): { char: string; count: number } | undefined {
  const lead = hexByte(run, i);
  const count =
    lead < 0x80 ? 1 : lead >= 0xc2 && lead < 0xe0 ? 2 : lead >= 0xe0 && lead < 0xf0 ? 3 : lead >= 0xf0 && lead < 0xf5 ? 4 : 0;
  if (count === 0 || i + count * 3 > run.length) return undefined;
  let code = count === 1 ? lead : lead & (0xff >> (count + 1));
  for (let k = 1; k < count; k++) {
    const byte = hexByte(run, i + k * 3);
    if ((byte & 0xc0) !== 0x80) return undefined;
    code = (code << 6) | (byte & 0x3f);
  }
  const min = [0, 0, 0x80, 0x800, 0x10000][count];
  if (code < min || code > 0x10ffff || (code >= 0xd800 && code <= 0xdfff)) return undefined;
  return { char: String.fromCodePoint(code), count };
}

/** A run of `%XX` escapes with each UTF-8 encoded hidden character as its code. */
function showPercentRun(run: string): string {
  let shown = "";
  let i = 0;
  while (i < run.length) {
    const decoded = decodeUtf8(run, i);
    if (decoded !== undefined && HIDDEN.test(decoded.char)) {
      shown += codeOf(decoded.char);
      i += decoded.count * 3;
      continue;
    }
    // Shown as written, one escape at a time, so the run is read once.
    shown += run.slice(i, i + 3);
    i += 3;
  }
  return shown;
}

function showDestination(dest: string): string {
  return dest.replace(DESTINATION_PART, (part) => {
    if (part === "(") return "%28";
    if (part === ")") return "%29";
    if (part === " ") return "%20";
    if (part.startsWith("%")) return showPercentRun(part);
    return HIDDEN.test(part) ? codeOf(part) : encodeURIComponent(part);
  });
}

/** `text` with each raw hidden character as its code; nothing else changes. */
function showHidden(text: string): string {
  return text.replace(HIDDEN_ALL, codeOf);
}

/** `value` without leading and trailing ASCII whitespace; nothing else is removed. */
function trimAscii(value: string): string {
  const isSpace = (code: number) => code === 0x20 || (code >= 0x09 && code <= 0x0d);
  let start = 0;
  let end = value.length;
  while (start < end && isSpace(value.charCodeAt(start))) start++;
  while (end > start && isSpace(value.charCodeAt(end - 1))) end--;
  return value.slice(start, end);
}

/** Destinations already shown during one render, so each is computed once (MDR-15). */
type DestinationCache = Map<string, string>;

/** The cache of the `toHast` run in progress; the link handlers read it. */
let activeCache: DestinationCache | undefined;

/** The source of the `toHast` run in progress, to tell autolinks from `[text](url)` links. */
let activeSource: string | undefined;

function shownDestination(dest: string, cache: DestinationCache | undefined): string {
  if (cache === undefined) return showDestination(dest);
  let shown = cache.get(dest);
  if (shown === undefined) {
    shown = showDestination(dest);
    cache.set(dest, shown);
  }
  return shown;
}

/** How one link's text and destination are shown. */
type ShownLink = { kind: "same" } | { kind: "escaped"; text: string } | { kind: "suffix"; suffix: string };

/**
 * How to show a link whose displayed text is `text`. A destination that does
 * not differ from the text is not repeated. Autolinks, including the `mailto:`
 * and `http://` forms GFM adds to bare emails and `www.` addresses, show the
 * address once with hidden characters escaped (MDR-15). A `[text](url)` link
 * whose own text equals a destination with a hidden character keeps that text
 * and shows the escaped destination after it (MDR-14). Otherwise the
 * destination follows as ` (<destination>)`. Lengths are compared first, so a
 * long text is never flattened.
 */
function showLink(
  text: string,
  destination: unknown,
  cache: DestinationCache | undefined,
  autolink: boolean,
): ShownLink {
  const dest = typeof destination === "string" ? trimAscii(destination) : "";
  if (dest === "") return { kind: "same" };
  const same =
    (text.length === dest.length && text === dest) ||
    (text.length === dest.length - 7 && (dest === `mailto:${text}` || dest === `http://${text}`));
  if (same && !HAS_HIDDEN.test(dest)) return { kind: "same" };
  if (same && autolink) return { kind: "escaped", text: showHidden(text) };
  return { kind: "suffix", suffix: ` (${shownDestination(dest, cache)})` };
}

/** The suffix as hast: its text inside `bdi dir="ltr"` (MDR-15). */
function suffixNode(suffix: string): Element {
  return { type: "element", tagName: "bdi", properties: { dir: "ltr" }, children: [{ type: "text", value: suffix }] };
}

/** A link's children, then ` (<destination>)` when it differs from them. */
function linkAsText(children: ElementContent[], destination: unknown, autolink: boolean): ElementContent[] {
  const shown = showLink(textOf(children), destination, activeCache, autolink);
  if (shown.kind === "same") return children;
  if (shown.kind === "escaped") return [{ type: "text", value: shown.text }];
  return [...children, suffixNode(shown.suffix)];
}

/** Whether a link node was written as an autolink (`<url>` or a bare URL), not `[text](url)`. */
function isAutolink(node: { position?: { start?: { offset?: number } } }): boolean {
  const offset = node.position?.start?.offset;
  return activeSource !== undefined && typeof offset === "number" && activeSource[offset] !== "[";
}

const link: Handler = (state, node) => linkAsText(state.all(node), node.url, isAutolink(node));

const linkReference: Handler = (state, node) => {
  const definition = state.definitionById.get(String(node.identifier).toUpperCase());
  // Without a definition the default handler shows the source text, no link.
  if (definition === undefined) return defaultHandlers.linkReference(state, node);
  return linkAsText(state.all(node), definition.url, false);
};

/** remark-rehype handlers that turn links into text; titles are dropped. */
export const noLinkHandlers = { link, linkReference };

/**
 * One element being unlinked. An `a` writes its children straight into the
 * output of its nearest non-`a` ancestor, from index `start`, so flattening
 * never copies. `chunks` holds the non-empty displayed text of the frame's
 * output, in order.
 */
type UnlinkFrame = {
  node: Root | Element;
  index: number;
  out: RootContent[];
  start: number;
  chunks: string[];
};

/**
 * Remove every `a` left in a hast tree. A footnote reference keeps only its
 * number, a footnote back-reference is removed, and any other `a` (only an
 * untrusted worker result can carry one) becomes text like a link. One
 * iterative post-order pass, linear in the tree size, so a deep chain of
 * nested `a` elements can neither overflow the stack nor take quadratic time.
 */
function unlink(tree: Root): void {
  const cache: DestinationCache = new Map();
  const stack: UnlinkFrame[] = [{ node: tree, index: 0, out: [], start: 0, chunks: [] }];
  for (;;) {
    const frame = stack[stack.length - 1];
    const { node, out, chunks } = frame;
    if (frame.index < node.children.length) {
      const child = node.children[frame.index++];
      if (child.type === "element") {
        const isLink = child.tagName === "a";
        const start = isLink ? out.length : 0;
        stack.push({ node: child, index: 0, out: isLink ? out : [], start, chunks: [] });
        continue;
      }
      out.push(child);
      if ((child.type === "text" || child.type === "raw") && child.value !== "") chunks.push(child.value);
      continue;
    }
    stack.pop();
    const parent = stack[stack.length - 1];
    if (parent === undefined) {
      tree.children = out;
      return;
    }
    const element = node as Element;
    let text = "";
    if (element.tagName === "img") text = imageText(element);
    else if (!VOID_TAGS.has(element.tagName)) for (const chunk of chunks) text += chunk;
    if (element.tagName !== "a") {
      element.children = out as ElementContent[];
      parent.out.push(element);
      if (text !== "") parent.chunks.push(text);
    } else if (element.properties.dataFootnoteBackref !== undefined) {
      out.length = frame.start;
      // Drop the space remark-rehype put before the back-reference. It is the
      // last character of the parent's text, so trim its last chunk too.
      const prev = out.length > parent.start ? out[out.length - 1] : undefined;
      if (prev?.type === "text" && prev.value.endsWith(" ")) {
        prev.value = prev.value.slice(0, -1);
        const last = parent.chunks.length - 1;
        parent.chunks[last] = parent.chunks[last].slice(0, -1);
        if (parent.chunks[last] === "") parent.chunks.pop();
        if (prev.value === "") out.pop();
      }
    } else {
      if (text !== "") parent.chunks.push(text);
      if (element.properties.dataFootnoteRef === undefined) {
        // Worker output has no source, so an `a` is treated as a `[text](url)` link.
        const shown = showLink(text, element.properties.href, cache, false);
        if (shown.kind === "escaped") {
          // Only the escaped address is shown, in place of the link's own output.
          out.length = frame.start;
          out.push({ type: "text", value: shown.text });
          if (text !== "") parent.chunks.pop();
          parent.chunks.push(shown.text);
        } else if (shown.kind === "suffix") {
          out.push(suffixNode(shown.suffix));
          parent.chunks.push(shown.suffix);
        }
      }
    }
  }
}

/** Rehype plugin form of `unlink`, shared by both render paths. */
export function rehypeNoLinks() {
  return (tree: Root) => unlink(tree);
}

/** Raw HTML stays literal text (MDR-02), exactly as react-markdown does it. */
function rawToText(node: Root | Element): void {
  node.children = node.children.map((child): RootContent => {
    if (child.type === "raw") return { type: "text", value: child.value };
    if (child.type === "element") rawToText(child);
    return child;
  });
}

// Output budget (MDR-10, design D13). The worker and the main thread both count
// the tree before it is sent or rendered; above the budget the view shows text.

/** Most hast nodes (root included) a formatted result may have. */
export const MAX_HAST_NODES = 25_000;

/**
 * Count every node in a hast tree without recursion, so a deep chain cannot
 * overflow the stack. Stops at the first count above `limit` and returns
 * `limit + 1`. Malformed input is tolerated: any value counts as one node and
 * only an array `children` property is descended into.
 */
export function countHastNodes(root: unknown, limit: number): number {
  let count = 0;
  const stack: Array<{ items: readonly unknown[]; index: number }> = [{ items: [root], index: 0 }];
  while (stack.length > 0) {
    const top = stack[stack.length - 1];
    if (top.index >= top.items.length) {
      stack.pop();
      continue;
    }
    const node = top.items[top.index++];
    if (++count > limit) return limit + 1;
    const children =
      typeof node === "object" && node !== null ? (node as { children?: unknown }).children : undefined;
    if (Array.isArray(children) && children.length > 0) stack.push({ items: children, index: 0 });
  }
  return count;
}

/**
 * Whether a hast tree has more than `maxNodes` nodes or more than `maxText`
 * characters of displayed text (design D16): string `value`s and image `alt`
 * text. One iterative pass, like `countHastNodes`, that stops as soon as either
 * budget is exceeded. Malformed input is tolerated in the same way.
 */
export function exceedsHastBudget(root: unknown, maxNodes: number, maxText: number): boolean {
  let count = 0;
  let text = 0;
  const stack: Array<{ items: readonly unknown[]; index: number }> = [{ items: [root], index: 0 }];
  while (stack.length > 0) {
    const top = stack[stack.length - 1];
    if (top.index >= top.items.length) {
      stack.pop();
      continue;
    }
    const node = top.items[top.index++];
    if (++count > maxNodes) return true;
    if (typeof node !== "object" || node === null) continue;
    const { value, tagName, properties, children } = node as {
      value?: unknown;
      tagName?: unknown;
      properties?: unknown;
      children?: unknown;
    };
    if (typeof value === "string") text += value.length;
    if (tagName === "img" && typeof properties === "object" && properties !== null) {
      const alt = (properties as { alt?: unknown }).alt;
      if (typeof alt === "string") text += alt.length;
    }
    if (text > maxText) return true;
    if (Array.isArray(children) && children.length > 0) stack.push({ items: children, index: 0 });
  }
  return false;
}

/** Whether `value` has the shape of a hast root: `type: "root"` and a children array. */
export function isHastRoot(value: unknown): value is Root {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const node = value as { type?: unknown; children?: unknown };
  return node.type === "root" && Array.isArray(node.children);
}

/**
 * Parse `source` into a serialisable hast tree with GFM and generated IDs
 * prefixed by `prefix` (the view's `user-content-<instance>-`).
 */
export function toHast(source: string, prefix: string): Root {
  const processor = unified()
    .use(remarkParse)
    .use(remarkGfm)
    .use(remarkRehype, { allowDangerousHtml: true, clobberPrefix: prefix, handlers: noLinkHandlers })
    .use(rehypeNoLinks);
  activeCache = new Map();
  activeSource = source;
  try {
    const tree = processor.runSync(processor.parse(source));
    rawToText(tree);
    return tree;
  } finally {
    activeCache = undefined;
    activeSource = undefined;
  }
}
