import type { Element, ElementContent, Properties, Root } from "hast";
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
  FRAGMENT_PREFIX,
  ID_PREFIX,
  rehypeFootnoteLinks,
  trustedFragmentLinks,
} from "./MarkdownView.pipeline";
import { defaultRunner, inThreadRunner, type MarkdownRunner } from "./markdownRunner";

// Restrictions are enforced through urlTransform and the components map (design D2).
// No rehype-raw: raw HTML stays literal text (MDR-02).

const ALLOWED_PROTOCOLS = new Set(["http:", "https:", "mailto:"]);

// After one percent-decoding pass, a mailto: address must not carry header or
// recipient separators, ASCII whitespace, <, >, ", \, /, or any C0/C1 control
// character or DEL (MDR-09, MDR-11). TAB, LF, FF and CR fall in the C0 range.
const MAILTO_FORBIDDEN = /[?#&,; <>"\\/\u0000-\u001f\u007f-\u009f]/;

// After that decoding pass, the address uses only these ASCII characters
// before and after its "@" (MDR-12), so IDN email addresses render as text.
const MAILTO_LOCAL = /^[A-Za-z0-9.!$'*+\-=^_`{|}~]+$/;
const MAILTO_DOMAIN = /^[A-Za-z0-9.-]+$/;

// Unicode format characters (such as U+200B, U+202E, U+FEFF) and non-ASCII
// whitespace (including U+00A0, U+2028 and U+2029) in any link (MDR-12).
const SUSPICIOUS_CHARACTER = /(?![\t-\r ])[\p{Cf}\p{White_Space}]/u;

// ignoreBOM keeps a decoded U+FEFF in the output instead of dropping it.
const utf8 = new TextDecoder("utf-8", { ignoreBOM: true });

/** Percent-decode once; invalid UTF-8 becomes U+FFFD instead of throwing. */
function decodeOnce(value: string): string {
  return value.replace(/(?:%[0-9A-Fa-f]{2})+/g, (run) =>
    utf8.decode(Uint8Array.from(run.slice(1).split("%"), (hex) => parseInt(hex, 16))),
  );
}

/**
 * One address of the form local@domain: exactly one "@" with a non-empty,
 * allow-listed part on each side (MDR-11, MDR-12). Malformed percent-encoding,
 * or a "%" left after decoding once, counts as invalid.
 */
function isSingleMailAddress(address: string): boolean {
  if (address === "") return false;
  let decoded: string;
  try {
    decoded = decodeURIComponent(address);
  } catch {
    return false;
  }
  if (MAILTO_FORBIDDEN.test(decoded) || decoded.includes("%")) return false;
  const parts = decoded.split("@");
  return parts.length === 2 && MAILTO_LOCAL.test(parts[0]) && MAILTO_DOMAIN.test(parts[1]);
}

/**
 * Accept only absolute http:, https: and mailto: URLs (MDR-04).
 * Anything else, including relative and protocol-relative URLs, becomes "".
 * URLs carrying a username or password become "", and mailto: keeps only the
 * address, without any query or fragment, and becomes "" when that address
 * encodes a query, fragment, extra recipient or control character, or is not
 * a single local@domain address (MDR-09, MDR-11). Any URL that carries a
 * suspicious character after decoding once becomes "" (MDR-12). Both render
 * paths reach this through transformUrl or the components map.
 */
function safeUrl(value: string): string {
  // Checked before trim() and URL parsing, which can drop these characters.
  if (SUSPICIOUS_CHARACTER.test(decodeOnce(value))) return "";
  const trimmed = value.trim();
  if (trimmed === "") return "";
  let parsed: URL;
  try {
    // No base URL: relative and protocol-relative destinations throw.
    parsed = new URL(trimmed);
  } catch {
    return "";
  }
  const protocol = parsed.protocol.toLowerCase();
  if (!ALLOWED_PROTOCOLS.has(protocol)) return "";
  if (parsed.username !== "" || parsed.password !== "") return "";
  if (protocol === "mailto:") {
    const rest = parsed.href.slice("mailto:".length);
    const end = rest.search(/[?#]/);
    const address = end === -1 ? rest : rest.slice(0, end);
    return isSingleMailAddress(address) ? "mailto:" + address : "";
  }
  return parsed.href;
}

function isTrustedFragment(url: string, node: Element | undefined): boolean {
  return (
    node !== undefined &&
    trustedFragmentLinks.has(node) &&
    url.startsWith(FRAGMENT_PREFIX) &&
    node.properties.href === url
  );
}

function transformUrl(url: string, key: string, node: Element): string {
  if (key === "href" && isTrustedFragment(url, node)) return url;
  return safeUrl(url);
}

type HeadingTag = "h1" | "h2" | "h3" | "h4" | "h5" | "h6";

function heading(Tag: HeadingTag) {
  // Render only the children so no id (or other attribute) reaches the DOM.
  return function Heading({ children }: { children?: ReactNode } & ExtraProps) {
    return <Tag>{children}</Tag>;
  };
}

const components: Components = {
  a({ node, href, title, id, children }) {
    if (typeof href === "string" && isTrustedFragment(href, node)) {
      const safeId = typeof id === "string" && id.startsWith(ID_PREFIX) ? id : undefined;
      return (
        <a href={href} id={safeId} title={title}>
          {children}
        </a>
      );
    }
    const safe = typeof href === "string" ? safeUrl(href) : "";
    if (safe === "") return <>{children}</>;
    return (
      <a href={safe} title={title} rel="noopener noreferrer nofollow">
        {children}
      </a>
    );
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
const rehypePlugins = [rehypeFootnoteLinks];

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
// (images as alt text, the link allow-list, credentials, mailto:, heading IDs,
// fragment trust) applies on the main thread.

const SUBSET_TAGS = new Set([
  "a", "blockquote", "br", "code", "del", "em", "h1", "h2", "h3", "h4", "h5", "h6",
  "hr", "img", "input", "li", "ol", "p", "pre", "section", "strong", "sup", "table",
  "tbody", "td", "th", "thead", "tr", "ul",
]);

const TAG_PROPERTIES: Record<string, readonly string[]> = {
  a: ["href", "title"],
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

/** The value to keep for one property, or undefined to drop it. */
function cleanProperty(key: string, value: unknown, idPrefix: string): Properties[string] {
  switch (key) {
    case "id":
      // Generated IDs must carry this view's prefix (MDR-08); others are dropped.
      return typeof value === "string" && value.startsWith(idPrefix) ? value : undefined;
    case "className":
      return Array.isArray(value) && value.every((c) => typeof c === "string")
        ? [...value]
        : undefined;
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
    const value = cleanProperty(key, props[key], idPrefix);
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
  // Fragment trust is recomputed here, against IDs in this rebuilt tree only.
  rehypeFootnoteLinks()(tree);
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

/** Plain text first, then the runner's result for the current source only. */
function RunnerMarkdown(props: { source: string; prefix: string; runner: MarkdownRunner }) {
  const { source, prefix, runner } = props;
  const [result, setResult] = useState<WorkerResult | null>(null);

  useEffect(() => {
    let current = true;
    runner.parse(source, prefix).then(
      (outcome) => {
        if (current && outcome.kind === "ok") setResult({ source, hast: outcome.hast });
      },
      () => {},
    );
    return () => {
      current = false;
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
        remarkRehypeOptions={{ clobberPrefix: prefix }}
        components={components}
        urlTransform={transformUrl}
      >
        {source}
      </Markdown>
    </RenderBoundary>
  );
}
