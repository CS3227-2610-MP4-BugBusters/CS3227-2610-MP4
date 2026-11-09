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

function textOf(nodes: readonly ElementContent[]): string {
  let text = "";
  for (const node of nodes) {
    if (node.type === "text") text += node.value;
    else if (node.type === "element") text += textOf(node.children);
  }
  return text;
}

/**
 * A link's children, then ` (<destination>)` as text when the destination
 * differs from that text. Autolinks, including the `mailto:` and `http://`
 * forms GFM adds to bare emails and `www.` addresses, show the address once.
 */
function linkAsText(children: ElementContent[], destination: unknown): ElementContent[] {
  const dest = typeof destination === "string" ? destination.trim() : "";
  const text = textOf(children);
  if (dest === "" || dest === text || dest === `mailto:${text}` || dest === `http://${text}`) {
    return children;
  }
  return [...children, { type: "text", value: ` (${dest})` }];
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
 * Remove every `a` left in a hast tree. A footnote reference keeps only its
 * number, a footnote back-reference is removed, and any other `a` (only an
 * untrusted worker result can carry one) becomes text like a link.
 */
function unlink(parent: Root | Element): void {
  const out: RootContent[] = [];
  for (const child of parent.children) {
    if (child.type !== "element") {
      out.push(child);
      continue;
    }
    unlink(child);
    if (child.tagName !== "a") {
      out.push(child);
    } else if (child.properties.dataFootnoteBackref !== undefined) {
      // Drop the space remark-rehype put before the back-reference.
      const prev = out[out.length - 1];
      if (prev?.type === "text" && prev.value.endsWith(" ")) {
        prev.value = prev.value.slice(0, -1);
        if (prev.value === "") out.pop();
      }
    } else if (child.properties.dataFootnoteRef !== undefined) {
      out.push(...child.children);
    } else {
      out.push(...linkAsText(child.children, child.properties.href));
    }
  }
  parent.children = out as ElementContent[];
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
