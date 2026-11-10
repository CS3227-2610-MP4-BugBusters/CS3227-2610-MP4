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
      else stack.push({ items: node.children, index: 0 });
    }
  }
  return text;
}

// Shown destinations (MDR-14). Unicode format characters (Cf), U+2028, U+2029
// and non-ASCII whitespace appear as `<U+XXXX>`, whether raw or percent-encoded
// as UTF-8, and `(` and `)` as `%28` and `%29`. Everything else is as written.

const HIDDEN = /^(?! )[\p{Cf}\p{Zs}\p{Zl}\p{Zp}\u0085]$/u;
const HAS_HIDDEN = /(?! )[\p{Cf}\p{Zs}\p{Zl}\p{Zp}\u0085]/u;
const DESTINATION_PART = /(?:%[\dA-Fa-f]{2})+|[()]|(?! )[\p{Cf}\p{Zs}\p{Zl}\p{Zp}\u0085]/gu;

function codeOf(char: string): string {
  return `<U+${char.codePointAt(0)!.toString(16).toUpperCase().padStart(4, "0")}>`;
}

/** A run of `%XX` escapes with each UTF-8 encoded hidden character as its code. */
function showPercentRun(run: string): string {
  let shown = "";
  let i = 0;
  while (i < run.length) {
    const lead = parseInt(run.slice(i + 1, i + 3), 16);
    const bytes = lead >= 0xf0 ? 4 : lead >= 0xe0 ? 3 : lead >= 0xc0 ? 2 : 1;
    const end = i + bytes * 3;
    if (bytes > 1 && end <= run.length) {
      let char = "";
      try {
        char = decodeURIComponent(run.slice(i, end));
      } catch {
        // Not valid UTF-8: shown as written.
      }
      if (HIDDEN.test(char)) {
        shown += codeOf(char);
        i = end;
        continue;
      }
    }
    shown += run.slice(i, i + 3);
    i += 3;
  }
  return shown;
}

function showDestination(dest: string): string {
  return dest.replace(DESTINATION_PART, (part) => {
    if (part === "(") return "%28";
    if (part === ")") return "%29";
    return part.startsWith("%") ? showPercentRun(part) : codeOf(part);
  });
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

/**
 * The ` (<destination>)` to show after a link whose displayed text is `text`,
 * or undefined when the destination does not differ from that text.
 * Autolinks, including the `mailto:` and `http://` forms GFM adds to bare
 * emails and `www.` addresses, show the address once unless it holds a hidden
 * character. Lengths are compared first, so a long text is never flattened.
 */
function destinationSuffix(text: string, destination: unknown): string | undefined {
  const dest = typeof destination === "string" ? trimAscii(destination) : "";
  if (dest === "") return undefined;
  const same =
    (text.length === dest.length && text === dest) ||
    (text.length === dest.length - 7 && (dest === `mailto:${text}` || dest === `http://${text}`));
  if (same && !HAS_HIDDEN.test(dest)) return undefined;
  return ` (${showDestination(dest)})`;
}

/** A link's children, then ` (<destination>)` as text when it differs from them. */
function linkAsText(children: ElementContent[], destination: unknown): ElementContent[] {
  const suffix = destinationSuffix(textOf(children), destination);
  return suffix === undefined ? children : [...children, { type: "text", value: suffix }];
}

const link: Handler = (state, node) => linkAsText(state.all(node), node.url);

const linkReference: Handler = (state, node) => {
  const definition = state.definitionById.get(String(node.identifier).toUpperCase());
  // Without a definition the default handler shows the source text, no link.
  if (definition === undefined) return defaultHandlers.linkReference(state, node);
  return linkAsText(state.all(node), definition.url);
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
    else for (const chunk of chunks) text += chunk;
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
        const suffix = destinationSuffix(text, element.properties.href);
        if (suffix !== undefined) {
          out.push({ type: "text", value: suffix });
          parent.chunks.push(suffix);
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
  const tree = processor.runSync(processor.parse(source));
  rawToText(tree);
  return tree;
}
