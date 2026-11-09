// Pre-parse render limits (MDR-05, design D4 incl. revision 1A). The parser
// itself can overflow the stack, exhaust memory or run for seconds on some
// inputs, so these checks run on the raw source before parsing. Every check is
// a single linear pass and errs towards plain text: a false fallback is
// acceptable, a missed slow construct is not. This is a blocklist of the known
// slow constructs; it is not a general time budget.

/** Longest source that is formatted at all. */
export const MAX_SOURCE_LENGTH = 20_000;
/** Estimated blockquote, list and footnote container depth on one line. */
export const MAX_NESTING_DEPTH = 32;
/**
 * Estimated table cells: delimiter-row columns times the header plus the
 * following non-blank lines. Owner decision "about 10k"; 10k cells measured
 * at roughly 0.3-0.7 s in jsdom, while the 2,500-column attack exhausts memory.
 */
export const MAX_TABLE_CELLS = 10_000;
/**
 * Estimated table cells summed over every table in the source (design D13).
 * Many tables just under the per-table cap still build a huge output tree.
 */
export const MAX_TOTAL_TABLE_CELLS = 10_000;
/** Longest run of one emphasis delimiter character (`*`, `_` or `~`). */
export const MAX_DELIMITER_RUN = 63;
/** Total `*`, `_` and `~` characters in the source (10% of the length cap). */
export const MAX_DELIMITERS = 2_000;
/** Lines that may be setext heading underlines (only `=` or only `-`). */
export const MAX_SETEXT_UNDERLINES = 500;
/** Lines that may be lazy continuations of a blockquote paragraph. */
export const MAX_LAZY_LINES = 500;

const SPACE = 0x20;
const TAB = 0x09;
const GT = 0x3e;
const DASH = 0x2d;
const STAR = 0x2a;
const PLUS = 0x2b;
const DOT = 0x2e;
const PAREN = 0x29;
const COLON = 0x3a;
const RBRACKET = 0x5d;
const PIPE = 0x7c;
const EQUALS = 0x3d;
const UNDERSCORE = 0x5f;
const TILDE = 0x7e;
const LF = 0x0a;
const CR = 0x0d;

function isBlank(code: number): boolean {
  return code === SPACE || code === TAB;
}

function isDigit(code: number): boolean {
  return code >= 0x30 && code <= 0x39;
}

/** End index of a list marker starting at `i`, or -1 when there is none. */
function listMarkerEnd(source: string, i: number, end: number): number {
  const code = source.charCodeAt(i);
  let j = i;
  if (code === DASH || code === STAR || code === PLUS) {
    j = i + 1;
  } else {
    while (j < end && j - i < 9 && isDigit(source.charCodeAt(j))) j++;
    if (j === i || j >= end) return -1;
    const delimiter = source.charCodeAt(j);
    if (delimiter !== DOT && delimiter !== PAREN) return -1;
    j++;
  }
  return j === end || isBlank(source.charCodeAt(j)) ? j : -1;
}

type LineDepth = { depth: number; quoted: boolean };

/**
 * Over-estimate the container depth a line opens. The line prefix counts one
 * level per `>` or list marker plus one per two columns of extra indentation
 * (nested list items). The rest of the line is scanned too, because a
 * footnote definition (`[^label]:`) can open further containers after it:
 * every `]:`, every `>` and every list marker followed by a blank that sits
 * where a container could start (line start, after a blank, `>` or `:`) adds
 * one level. `quoted` reports whether any such `>` was seen.
 */
function lineDepth(source: string, start: number, end: number): LineDepth {
  let depth = 0;
  let indent = 0;
  let quoted = false;
  let separator = false;
  let i = start;
  while (i < end) {
    const code = source.charCodeAt(i);
    if (isBlank(code)) {
      // The first blank after a marker separates it from content; others indent.
      if (separator) separator = false;
      else indent += code === TAB ? 4 : 1;
      i++;
      continue;
    }
    separator = false;
    if (code === GT) {
      depth++;
      quoted = true;
      separator = true;
      i++;
      continue;
    }
    const markerEnd = listMarkerEnd(source, i, end);
    if (markerEnd === -1) break;
    depth++;
    separator = true;
    i = markerEnd;
  }
  // Remainder of the line: each character is visited once (a marker skip only
  // moves forward), so the scan stays linear.
  while (i < end) {
    const code = source.charCodeAt(i);
    if (code === RBRACKET && i + 1 < end && source.charCodeAt(i + 1) === COLON) {
      depth++;
      i += 2;
      continue;
    }
    const prev = i === start ? SPACE : source.charCodeAt(i - 1);
    const boundary = isBlank(prev) || prev === GT || prev === COLON;
    if (boundary && code === GT) {
      depth++;
      quoted = true;
      i++;
      continue;
    }
    if (boundary && (code === DASH || code === STAR || code === PLUS || isDigit(code))) {
      const markerEnd = listMarkerEnd(source, i, end);
      if (markerEnd !== -1) {
        depth++;
        i = markerEnd;
        continue;
      }
    }
    i++;
  }
  return { depth: depth + Math.floor(indent / 2), quoted };
}

function skipBlanks(source: string, start: number, end: number): number {
  let i = start;
  while (i < end && isBlank(source.charCodeAt(i))) i++;
  return i;
}

/** Index of the first character after leading blanks and `>` markers. */
function skipQuotePrefix(source: string, start: number, end: number): number {
  let i = start;
  while (i < end) {
    const code = source.charCodeAt(i);
    if (!isBlank(code) && code !== GT) break;
    i++;
  }
  return i;
}

/**
 * Column count when the line could be a table delimiter row (only `|`, `-`,
 * `:` and blanks after a blockquote prefix, with at least one `-`), else 0.
 */
function delimiterColumns(source: string, start: number, end: number): number {
  const first = skipQuotePrefix(source, start, end);
  let pipes = 0;
  let dashes = 0;
  let firstCode = 0;
  let lastCode = 0;
  for (let i = first; i < end; i++) {
    const code = source.charCodeAt(i);
    if (isBlank(code)) continue;
    if (code === PIPE) pipes++;
    else if (code === DASH) dashes++;
    else if (code !== COLON) return 0;
    if (firstCode === 0) firstCode = code;
    lastCode = code;
  }
  if (dashes === 0) return 0;
  let columns = pipes + 1;
  if (firstCode === PIPE) columns--;
  if (lastCode === PIPE && pipes > 1) columns--;
  return Math.max(columns, 1);
}

/** Whether the line could underline a setext heading (only `=` or only `-`). */
function isSetextUnderline(source: string, start: number, end: number): boolean {
  let i = skipQuotePrefix(source, start, end);
  if (i >= end) return false;
  const marker = source.charCodeAt(i);
  if (marker !== EQUALS && marker !== DASH) return false;
  while (i < end && source.charCodeAt(i) === marker) i++;
  while (i < end && isBlank(source.charCodeAt(i))) i++;
  return i === end;
}

function isBlankLine(source: string, start: number, end: number): boolean {
  for (let i = start; i < end; i++) {
    if (!isBlank(source.charCodeAt(i))) return false;
  }
  return true;
}

function exceedsDelimiterLimits(source: string): boolean {
  let total = 0;
  let run = 0;
  let runCode = 0;
  for (let i = 0; i < source.length; i++) {
    const code = source.charCodeAt(i);
    if (code !== STAR && code !== UNDERSCORE && code !== TILDE) {
      run = 0;
      continue;
    }
    if (++total > MAX_DELIMITERS) return true;
    run = code === runCode && run > 0 ? run + 1 : 1;
    runCode = code;
    if (run > MAX_DELIMITER_RUN) return true;
  }
  return false;
}

export function exceedsRenderLimits(source: string): boolean {
  if (source.length > MAX_SOURCE_LENGTH) return true;
  if (exceedsDelimiterLimits(source)) return true;

  let previousBlank = true;
  // Table estimate: columns of the latest delimiter row and lines since it.
  let tableColumns = 0;
  let tableRows = 0;
  // Estimated cells of the tables already closed by a blank line.
  let closedTableCells = 0;
  // Set by a line containing a container `>`; cleared by a blank line.
  let inQuote = false;
  let lazyLines = 0;
  let setextLines = 0;

  let start = 0;
  while (start <= source.length) {
    let end = start;
    while (end < source.length) {
      const code = source.charCodeAt(end);
      if (code === LF || code === CR) break;
      end++;
    }
    // CRLF is one line ending, so it must not look like a blank line between.
    const crlf = source.charCodeAt(end) === CR && source.charCodeAt(end + 1) === LF;
    const next = crlf ? end + 2 : end + 1;

    if (isBlankLine(source, start, end)) {
      previousBlank = true;
      closedTableCells += tableColumns * tableRows;
      tableColumns = 0;
      tableRows = 0;
      inQuote = false;
      start = next;
      continue;
    }

    const { depth, quoted } = lineDepth(source, start, end);
    if (depth > MAX_NESTING_DEPTH) return true;

    const columns = delimiterColumns(source, start, end);
    if (columns > 0) {
      // Header row plus this one; a later delimiter row may widen the estimate.
      if (tableColumns === 0) tableRows = 1;
      tableColumns = Math.max(tableColumns, columns);
    }
    if (tableColumns > 0) {
      tableRows++;
      const cells = tableColumns * tableRows;
      if (cells > MAX_TABLE_CELLS) return true;
      if (closedTableCells + cells > MAX_TOTAL_TABLE_CELLS) return true;
    }

    if (!previousBlank && isSetextUnderline(source, start, end)) {
      if (++setextLines > MAX_SETEXT_UNDERLINES) return true;
    }

    if (inQuote && source.charCodeAt(skipBlanks(source, start, end)) !== GT) {
      if (++lazyLines > MAX_LAZY_LINES) return true;
    }
    if (quoted) inQuote = true;

    previousBlank = false;
    start = next;
  }
  return false;
}
