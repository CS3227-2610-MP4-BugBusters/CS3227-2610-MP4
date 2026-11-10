import type { Element, ElementContent, Properties, Root } from "hast";
import { toJsxRuntime, type Components } from "hast-util-to-jsx-runtime";
import {
  Component,
  createContext,
  useContext,
  useEffect,
  useId,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { Fragment, jsx, jsxs } from "react/jsx-runtime";
import { exceedsRenderLimits, MAX_IN_THREAD_SOURCE, MAX_TEXT_CHARS } from "./MarkdownView.limits";
import {
  exceedsHastBudget,
  ID_PREFIX,
  MAX_HAST_NODES,
  rehypeNoLinks,
  toHast,
} from "./MarkdownView.pipeline";
import {
  defaultRunner,
  inThreadRunner,
  type MarkdownRunner,
  type ParseOutcome,
} from "./markdownRunner";

// Restrictions are enforced in the hast pipeline, the sanitiser and the
// components map (design D2, D12, D14). Raw HTML stays literal text (MDR-02).
// No links (MDR-04): links and footnote references become text before
// rendering, so there is no URL left to check. Both render paths (worker and
// in-thread) go through the same sanitiser, node budget and renderer.

type HeadingTag = "h1" | "h2" | "h3" | "h4" | "h5" | "h6";

function heading(Tag: HeadingTag) {
  // Render only the children so no id (or other attribute) reaches the DOM.
  // The one exception is the footnotes label (`h2.sr-only`, design D13).
  return function Heading({
    children,
    className,
  }: { children?: ReactNode; className?: string }) {
    const keep = Tag === "h2" && className === "sr-only" ? className : undefined;
    return <Tag className={keep}>{children}</Tag>;
  };
}

const components: Components = {
  a({ children }) {
    // Backstop: no `a` reaches here, and none is ever rendered (MDR-04).
    return <>{children}</>;
  },
  img({ alt }) {
    // Never render an image element; show the alt text only (MDR-03).
    return <>{alt ?? ""}</>;
  },
  input({ type, checked }) {
    // Only GFM task-list checkboxes can reach here; render them inert.
    if (type !== "checkbox") return null;
    return <input type="checkbox" checked={Boolean(checked)} disabled readOnly />;
  },
  h1: heading("h1"),
  h2: heading("h2"),
  h3: heading("h3"),
  h4: heading("h4"),
  h5: heading("h5"),
  h6: heading("h6"),
};

// Failure-safe rendering (MDR-05, design D4). The parser itself can overflow the
// stack, exhaust memory or run for seconds on some input, so oversized, deeply
// nested or known slow source is detected before parsing (MarkdownView.limits)
// and shown as escaped plain text instead.

function PlainText({ source }: { source: string }) {
  // React escapes text children, so the complete source shows as literal text.
  return <pre className="text">{source}</pre>;
}

type BoundaryProps = { source: string; children: ReactNode };

/** Falls back to the plain source if rendering throws (design D4). */
class RenderBoundary extends Component<BoundaryProps, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError(): { failed: boolean } {
    return { failed: true };
  }

  render() {
    if (this.state.failed) return <PlainText source={this.props.source} />;
    return this.props.children;
  }
}

// Parser output (MDR-10, design D11, D14) is untrusted structure, whether it
// comes from the worker or from the in-thread parser. It is rebuilt from the
// Markdown subset only, keeping the few properties remark-rehype emits for it,
// then counted, then rendered with the components map, so every rule above
// (images as alt text, links as text, heading IDs) applies on the main thread.

const SUBSET_TAGS = new Set([
  "a", "blockquote", "br", "code", "del", "em", "h1", "h2", "h3", "h4", "h5", "h6",
  "hr", "img", "input", "li", "ol", "p", "pre", "section", "strong", "sup", "table",
  "tbody", "td", "th", "thead", "tr", "ul",
]);

/**
 * Tag-specific property keys, in the order remark-rehype emits them. Every tag
 * also keeps `id` and `className`, read after these.
 */
const TAG_PROPERTIES: Record<string, readonly string[]> = {
  a: ["href"],
  img: ["alt"],
  input: ["type", "checked", "disabled"],
  ol: ["start"],
  section: ["dataFootnotes"],
  td: ["align"],
  th: ["align"],
};

const COMMON_PROPERTIES: readonly string[] = ["id", "className"];

/**
 * The only property keys read per tag. They are read directly and never
 * enumerated (design D14), so a property object with many keys costs nothing.
 */
const PROPERTY_KEYS: Record<string, readonly string[]> = Object.fromEntries(
  [...SUBSET_TAGS].map((tag) => [tag, [...(TAG_PROPERTIES[tag] ?? []), ...COMMON_PROPERTIES]]),
);

/** Most `className` entries read per element (design D14). */
const MAX_CLASS_ENTRIES = 8;

const ALIGNMENTS = new Set(["left", "center", "right"]);

/** `language-` plus at least one character that is not ASCII whitespace (design D15). */
const LANGUAGE_CLASS = /^language-[^\t\n\f\r ]+$/;

const hasOwn = Object.prototype.hasOwnProperty;

type UntrustedNode = { type?: unknown; tagName?: unknown; properties?: unknown; children?: unknown; value?: unknown };

function asNode(value: unknown): UntrustedNode | undefined {
  return typeof value === "object" && value !== null ? (value as UntrustedNode) : undefined;
}

/** Whether the renderer ever generates class `name` on `tagName` (design D13). */
function isAllowedClass(tagName: string, name: string): boolean {
  switch (tagName) {
    case "code":
      return LANGUAGE_CLASS.test(name);
    case "ul":
    case "ol":
      return name === "contains-task-list";
    case "li":
      return name === "task-list-item";
    case "section":
      return name === "footnotes";
    case "h2":
      return name === "sr-only";
    default:
      return false;
  }
}

/**
 * The first allowed class only, as the renderer never generates more. Only the
 * first MAX_CLASS_ENTRIES entries are read, so a huge class list stays cheap
 * (design D14); the renderer never puts more than one class on an element.
 */
function cleanClassName(tagName: string, value: unknown): Properties[string] {
  if (!Array.isArray(value)) return undefined;
  const length = Math.min(value.length, MAX_CLASS_ENTRIES);
  for (let i = 0; i < length; i++) {
    const name: unknown = value[i];
    if (typeof name === "string" && isAllowedClass(tagName, name)) return [name];
  }
  return undefined;
}

/** The value to keep for one property, or undefined to drop it. */
function cleanProperty(
  tagName: string,
  key: string,
  value: unknown,
  idPrefix: string,
): Properties[string] {
  switch (key) {
    case "id":
      // Generated IDs must carry this view's prefix (MDR-08); others are dropped.
      return typeof value === "string" && value.startsWith(idPrefix) ? value : undefined;
    case "className":
      return cleanClassName(tagName, value);
    case "align":
      return typeof value === "string" && ALIGNMENTS.has(value) ? value : undefined;
    case "start":
      return typeof value === "number" && Number.isFinite(value) ? value : undefined;
    case "checked":
    case "disabled":
    case "dataFootnotes":
      return typeof value === "boolean" ? value : undefined;
    default:
      return typeof value === "string" ? value : undefined;
  }
}

/** Only the allowed own keys, read directly; the object is never enumerated. */
function cleanProperties(tagName: string, input: unknown, idPrefix: string): Properties {
  const out: Properties = {};
  const props = asNode(input) as Record<string, unknown> | undefined;
  if (props === undefined) return out;
  for (const key of PROPERTY_KEYS[tagName] ?? COMMON_PROPERTIES) {
    if (!hasOwn.call(props, key)) continue;
    const value = cleanProperty(tagName, key, props[key], idPrefix);
    if (value !== undefined) out[key] = value;
  }
  return out;
}

type CleanFrame = { items: readonly unknown[]; length: number; index: number; out: ElementContent[] };

/**
 * The length of an untrusted `children` value, or undefined to reject the tree
 * (design D15). It must be an array whose `length`, read once, is a whole
 * number within the remaining budget, so a sparse array or a Proxy claiming a
 * huge length is rejected before any entry is read.
 */
function childrenLength(value: unknown, remaining: number): number | undefined {
  if (!Array.isArray(value)) return undefined;
  const length: unknown = value.length;
  if (typeof length !== "number" || !Number.isInteger(length) || length < 0) return undefined;
  return length <= remaining ? length : undefined;
}

/**
 * Rebuild the subset from untrusted children, iteratively so a deep chain
 * cannot overflow the stack. Each field of each input node is read once, so a
 * getter cannot return one value when checked and another when rendered.
 * Every entry read counts against `limit` (root included), whether it is kept
 * or dropped, so at most `limit` entries are ever read (design D15). The
 * lengths of the strings kept for display (text, image `alt`, link `href`)
 * count against `textLimit` as they are read (design D16). Returns undefined
 * once either budget is spent or a `children` array claims more entries than
 * the budget has left.
 */
function cleanTree(
  children: unknown,
  idPrefix: string,
  limit: number,
  textLimit: number,
): Root | undefined {
  const out: ElementContent[] = [];
  let used = 1;
  let text = 0;
  const rootLength = childrenLength(children, limit - used);
  if (rootLength === undefined) return undefined;
  const stack: CleanFrame[] = [
    { items: children as readonly unknown[], length: rootLength, index: 0, out },
  ];
  while (stack.length > 0) {
    const top = stack[stack.length - 1];
    if (top.index >= top.length) {
      stack.pop();
      continue;
    }
    if (++used > limit) return undefined;
    const node = asNode(top.items[top.index++]);
    if (node === undefined) continue;
    const type = node.type;
    if (type === "text" || type === "raw") {
      // Raw HTML is literal text (MDR-02); React escapes it.
      const value = node.value;
      if (typeof value !== "string") continue;
      text += value.length;
      if (text > textLimit) return undefined;
      top.out.push({ type: "text", value });
    } else if (type === "element") {
      const tagName = node.tagName;
      if (typeof tagName !== "string" || !SUBSET_TAGS.has(tagName)) continue;
      const properties = cleanProperties(tagName, node.properties, idPrefix);
      // Kept properties are already strings or nothing; `href` becomes text.
      const shown = properties.alt ?? properties.href;
      if (typeof shown === "string") {
        text += shown.length;
        if (text > textLimit) return undefined;
      }
      const nested = node.children;
      const element: Element = { type: "element", tagName, properties, children: [] };
      top.out.push(element);
      // Anything but an array means no children, as before.
      if (!Array.isArray(nested)) continue;
      const length = childrenLength(nested, limit - used);
      if (length === undefined) return undefined;
      if (length > 0) {
        stack.push({ items: nested as readonly unknown[], length, index: 0, out: element.children });
      }
    }
    // Every other node (script, iframe, style, comments, MDX, ...) is dropped.
  }
  return { type: "root", children: out };
}

/**
 * The safe tree to render from untrusted parser output, or undefined to show
 * plain text (design D13, D14, D16). It must be a root with a children array.
 * It is cleaned first, then the cleaned tree is checked against MAX_HAST_NODES
 * and MAX_TEXT_CHARS, so the check is of exactly what will be rendered.
 */
function sanitizeHast(input: unknown, idPrefix: string): Root | undefined {
  const root = asNode(input);
  if (root === undefined || Array.isArray(root) || root.type !== "root") return undefined;
  const tree = cleanTree(root.children, idPrefix, MAX_HAST_NODES, MAX_TEXT_CHARS);
  if (tree === undefined) return undefined;
  // Any `a` becomes text exactly as in-thread links do (MDR-04).
  rehypeNoLinks()(tree);
  return exceedsHastBudget(tree, MAX_HAST_NODES, MAX_TEXT_CHARS) ? undefined : tree;
}

/** Renders a tree already returned by `sanitizeHast`. */
function HastView({ hast }: { hast: Root }) {
  return toJsxRuntime(hast, {
    Fragment,
    components,
    ignoreInvalidStyle: true,
    jsx,
    jsxs,
    passKeys: true,
    passNode: true,
  });
}

const RunnerContext = createContext<MarkdownRunner | null>(null);

/** Chooses how MarkdownView parses below it (design D11 test seam). */
export function MarkdownParserProvider(props: { runner: MarkdownRunner; children?: ReactNode }) {
  return <RunnerContext.Provider value={props.runner}>{props.children}</RunnerContext.Provider>;
}

type WorkerResult = { source: string; hast: Root };

const FALLBACK: ParseOutcome = { kind: "fallback" };

/**
 * Call the runner defensively (design D13): a synchronous throw or a return
 * value that is not a thenable becomes "fallback" instead of an error.
 */
function safeParse(
  runner: MarkdownRunner,
  source: string,
  prefix: string,
  signal: AbortSignal,
): Promise<unknown> {
  try {
    const result: unknown = runner.parse(source, prefix, signal);
    if (
      typeof result === "object" &&
      result !== null &&
      typeof (result as { then?: unknown }).then === "function"
    ) {
      return Promise.resolve(result);
    }
  } catch {
    // Fall through to plain text.
  }
  return Promise.resolve(FALLBACK);
}

/**
 * The sanitised tree to render from an untrusted outcome, or undefined to keep
 * plain text (MDR-10, design D14).
 */
function renderableHast(outcome: unknown, prefix: string): Root | undefined {
  try {
    if (typeof outcome !== "object" || outcome === null) return undefined;
    const { kind, hast } = outcome as { kind?: unknown; hast?: unknown };
    if (kind !== "ok") return undefined;
    return sanitizeHast(hast, prefix);
  } catch {
    return undefined;
  }
}

/** Plain text first, then the runner's result for the current source only. */
function RunnerMarkdown(props: { source: string; prefix: string; runner: MarkdownRunner }) {
  const { source, prefix, runner } = props;
  const [result, setResult] = useState<WorkerResult | null>(null);

  useEffect(() => {
    let current = true;
    // Aborted on cleanup so an unmounted or stale view's unsent request is dropped.
    const controller = new AbortController();
    safeParse(runner, source, prefix, controller.signal).then(
      (outcome) => {
        if (!current) return;
        const hast = renderableHast(outcome, prefix);
        if (hast !== undefined) setResult({ source, hast });
      },
      () => {},
    );
    return () => {
      current = false;
      controller.abort();
    };
  }, [runner, source, prefix]);

  if (result === null || result.source !== source) return <PlainText source={source} />;
  return (
    <RenderBoundary key={source} source={source}>
      <HastView hast={result.hast} />
    </RenderBoundary>
  );
}

/**
 * Parses on the calling thread, then the same sanitiser, node budget and
 * renderer as the worker path (design D14). Rendered inside the error
 * boundary, so a parser throw shows the plain source.
 */
function InThreadMarkdown({ source, prefix }: { source: string; prefix: string }) {
  const hast = useMemo(() => sanitizeHast(toHast(source, prefix), prefix), [source, prefix]);
  if (hast === undefined) return <PlainText source={source} />;
  return <HastView hast={hast} />;
}

export function MarkdownView(props: { source: string }) {
  // Per-view ID prefix so two views never share footnote IDs (MDR-08, design D2).
  const instance = useId().replace(/[^A-Za-z0-9_-]/g, "");
  const prefix = `${ID_PREFIX}${instance}-`;
  const runner = useContext(RunnerContext) ?? defaultRunner();
  const { source } = props;
  if (exceedsRenderLimits(source)) return <PlainText source={source} />;
  if (runner !== inThreadRunner) {
    return <RunnerMarkdown source={source} prefix={prefix} runner={runner} />;
  }
  // No Web Worker (jsdom, SSR): parse in-thread, synchronously (design D11, D14),
  // only up to MAX_IN_THREAD_SOURCE characters (design D15).
  if (source.length > MAX_IN_THREAD_SOURCE) return <PlainText source={source} />;
  return (
    // Keyed on source so a new source gets a fresh attempt after a failure.
    <RenderBoundary key={source} source={source}>
      <InThreadMarkdown source={source} prefix={prefix} />
    </RenderBoundary>
  );
}
