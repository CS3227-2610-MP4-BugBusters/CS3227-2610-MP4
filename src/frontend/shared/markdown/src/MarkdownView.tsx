import type { ElementContent, Properties, Root } from "hast";
import { toJsxRuntime, type Components as JsxComponents } from "hast-util-to-jsx-runtime";
import {
  Component,
  createContext,
  useContext,
  useEffect,
  useId,
  useState,
  type ReactNode,
} from "react";
import { Fragment, jsx, jsxs } from "react/jsx-runtime";
import Markdown, { type Components, type ExtraProps } from "react-markdown";
import remarkGfm from "remark-gfm";
import { exceedsRenderLimits } from "./MarkdownView.limits";
import {
  countHastNodes,
  ID_PREFIX,
  isHastRoot,
  MAX_HAST_NODES,
  noLinkHandlers,
  rehypeNoLinks,
} from "./MarkdownView.pipeline";
import {
  defaultRunner,
  inThreadRunner,
  type MarkdownRunner,
  type ParseOutcome,
} from "./markdownRunner";

// Restrictions are enforced in the hast pipeline and the components map
// (design D2, D12). No rehype-raw: raw HTML stays literal text (MDR-02).
// No links (MDR-04): links and footnote references become text before
// rendering, so there is no URL left to check.

type HeadingTag = "h1" | "h2" | "h3" | "h4" | "h5" | "h6";

function heading(Tag: HeadingTag) {
  // Render only the children so no id (or other attribute) reaches the DOM.
  // The one exception is the footnotes label (`h2.sr-only`, design D13).
  return function Heading({
    children,
    className,
  }: { children?: ReactNode; className?: string } & ExtraProps) {
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

const remarkPlugins = [remarkGfm];
const rehypePlugins = [rehypeNoLinks];

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

// Worker output (MDR-10, design D11) is untrusted structure. It is rebuilt from
// the Markdown subset only, keeping the few properties remark-rehype emits for
// it, and then rendered with the same components map, so every rule above
// (images as alt text, links as text, heading IDs) applies on the main thread.

const SUBSET_TAGS = new Set([
  "a", "blockquote", "br", "code", "del", "em", "h1", "h2", "h3", "h4", "h5", "h6",
  "hr", "img", "input", "li", "ol", "p", "pre", "section", "strong", "sup", "table",
  "tbody", "td", "th", "thead", "tr", "ul",
]);

const TAG_PROPERTIES: Record<string, readonly string[]> = {
  a: ["href"],
  img: ["alt"],
  input: ["type", "checked", "disabled"],
  ol: ["start"],
  section: ["dataFootnotes"],
  td: ["align"],
  th: ["align"],
};

const ALIGNMENTS = new Set(["left", "center", "right"]);

type UntrustedNode = { type?: unknown; tagName?: unknown; properties?: unknown; children?: unknown; value?: unknown };

function asNode(value: unknown): UntrustedNode | undefined {
  return typeof value === "object" && value !== null ? (value as UntrustedNode) : undefined;
}

/** Whether the renderer ever generates class `name` on `tagName` (design D13). */
function isAllowedClass(tagName: string, name: string): boolean {
  switch (tagName) {
    case "code":
      return name.startsWith("language-");
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
 * The first allowed class only, as the renderer never generates more. One
 * pass that stops at the first match, so a huge class list stays cheap.
 */
function cleanClassName(tagName: string, value: unknown): Properties[string] {
  if (!Array.isArray(value)) return undefined;
  for (const name of value) {
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

function cleanProperties(tagName: string, input: unknown, idPrefix: string): Properties {
  const props = (asNode(input) ?? {}) as Record<string, unknown>;
  const allowed = TAG_PROPERTIES[tagName] ?? [];
  const out: Properties = {};
  // Keep the original order so the DOM matches the in-thread renderer.
  for (const key of Object.keys(props)) {
    if (key !== "id" && key !== "className" && !allowed.includes(key)) continue;
    const value = cleanProperty(tagName, key, props[key], idPrefix);
    if (value !== undefined) out[key] = value;
  }
  return out;
}

function cleanChildren(input: unknown, idPrefix: string): ElementContent[] {
  if (!Array.isArray(input)) return [];
  const out: ElementContent[] = [];
  for (const item of input) {
    const node = asNode(item);
    if (node === undefined) continue;
    if (node.type === "text" || node.type === "raw") {
      // Raw HTML is literal text (MDR-02); React escapes it.
      if (typeof node.value === "string") out.push({ type: "text", value: node.value });
    } else if (
      node.type === "element" &&
      typeof node.tagName === "string" &&
      SUBSET_TAGS.has(node.tagName)
    ) {
      out.push({
        type: "element",
        tagName: node.tagName,
        properties: cleanProperties(node.tagName, node.properties, idPrefix),
        children: cleanChildren(node.children, idPrefix),
      });
    }
    // Every other node (script, iframe, style, comments, MDX, ...) is dropped.
  }
  return out;
}

function sanitizeHast(input: unknown, idPrefix: string): Root {
  const root = asNode(input);
  const children = root?.type === "root" ? cleanChildren(root.children, idPrefix) : [];
  const tree: Root = { type: "root", children };
  // Any `a` becomes text exactly as in-thread links do (MDR-04).
  rehypeNoLinks()(tree);
  return tree;
}

function HastView({ hast, prefix }: { hast: Root; prefix: string }) {
  return toJsxRuntime(sanitizeHast(hast, prefix), {
    Fragment,
    components: components as JsxComponents,
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
 * The hast to render from an untrusted outcome, or undefined to keep plain
 * text: it must be a root with a children array within MAX_HAST_NODES (MDR-10).
 */
function renderableHast(outcome: unknown): Root | undefined {
  try {
    if (typeof outcome !== "object" || outcome === null) return undefined;
    const { kind, hast } = outcome as { kind?: unknown; hast?: unknown };
    if (kind !== "ok" || !isHastRoot(hast)) return undefined;
    return countHastNodes(hast, MAX_HAST_NODES) > MAX_HAST_NODES ? undefined : hast;
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
        const hast = renderableHast(outcome);
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
      <HastView hast={result.hast} prefix={prefix} />
    </RenderBoundary>
  );
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
  // No Web Worker (jsdom, SSR): parse in-thread, synchronously (design D11).
  return (
    // Keyed on source so a new source gets a fresh attempt after a failure.
    <RenderBoundary key={source} source={source}>
      <Markdown
        remarkPlugins={remarkPlugins}
        rehypePlugins={rehypePlugins}
        remarkRehypeOptions={{ clobberPrefix: prefix, handlers: noLinkHandlers }}
        components={components}
      >
        {source}
      </Markdown>
    </RenderBoundary>
  );
}
