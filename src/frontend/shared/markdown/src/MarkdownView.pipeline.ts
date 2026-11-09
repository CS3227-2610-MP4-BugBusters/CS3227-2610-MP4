import type { Element, Nodes, Root, RootContent } from "hast";
import remarkGfm from "remark-gfm";
import remarkParse from "remark-parse";
import remarkRehype from "remark-rehype";
import { unified } from "unified";

// Pure Markdown-to-hast pipeline (design D11). No DOM and no React, so it runs
// unchanged in the parse worker. The main thread treats its output as
// untrusted and re-applies every safety rule at render time (MarkdownView).

// Footnote links (MDR-08, design D2): a fragment href survives only when it has
// the case-sensitive generated-ID prefix and targets an ID in the same output.
// Each view adds its own instance segment after ID_PREFIX (see MarkdownView).
export const FRAGMENT_PREFIX = "#user-content-";
export const ID_PREFIX = "user-content-";

// Anchor elements approved by rehypeFootnoteLinks for the current render.
export const trustedFragmentLinks = new WeakSet<Element>();

export function walk(node: Nodes, visit: (el: Element) => void): void {
  if (node.type === "element") visit(node);
  if ("children" in node) {
    for (const child of node.children) walk(child, visit);
  }
}

/** Mark anchors whose `#user-content-` href targets an ID present in this tree. */
export function rehypeFootnoteLinks() {
  return (tree: Root) => {
    const ids = new Set<string>();
    walk(tree, (el) => {
      const id = el.properties.id;
      if (typeof id === "string" && id.startsWith(ID_PREFIX)) ids.add(id);
    });
    walk(tree, (el) => {
      const href = el.properties.href;
      if (
        el.tagName === "a" &&
        typeof href === "string" &&
        href.startsWith(FRAGMENT_PREFIX) &&
        ids.has(href.slice(1))
      ) {
        trustedFragmentLinks.add(el);
      }
    });
  };
}

/** Raw HTML stays literal text (MDR-02), exactly as react-markdown does it. */
function rawToText(node: Root | Element): void {
  node.children = node.children.map((child): RootContent => {
    if (child.type === "raw") return { type: "text", value: child.value };
    if (child.type === "element") rawToText(child);
    return child;
  });
}

/**
 * Parse `source` into a serialisable hast tree with GFM and generated IDs
 * prefixed by `prefix` (the view's `user-content-<instance>-`).
 */
export function toHast(source: string, prefix: string): Root {
  const processor = unified()
    .use(remarkParse)
    .use(remarkGfm)
    .use(remarkRehype, { allowDangerousHtml: true, clobberPrefix: prefix })
    .use(rehypeFootnoteLinks);
  const tree = processor.runSync(processor.parse(source));
  rawToText(tree);
  return tree;
}
