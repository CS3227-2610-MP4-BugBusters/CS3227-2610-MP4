# Spec Delta

## MODIFIED Requirements

### Requirement: MNT-READ-05 Safe and distinct display states

The frontend SHALL distinguish access checking, list/detail loading, empty results,
safe failure/retry and missing-detail states. The Reporter-authored `description`
SHALL render through the shared `safe-markdown` renderer. Every other value,
including the title and the remaining source fields, SHALL render as escaped text
without interpreted HTML or Markdown. Filters and buttons SHALL have accessible names, and the layout SHALL
support narrow mobile viewports without horizontal overflow.

#### Scenario: Show source markup as text

- **WHEN** a report's title, other source fields, notes, comments, checklist, responses, proposal or history contain markup-like or Markdown text
- **THEN** the literal text is displayed without creating an injected element or formatting

#### Scenario: Render Reporter Markdown safely

- **WHEN** a report's description contains a list, a fenced code block, `<img src=x onerror=alert(1)>` and `![x](https://attacker.example/p.png)`
- **THEN** the list and code block render formatted
- **AND** no img element, event handler or request to the image URL exists, and the HTML markup appears as literal text

#### Scenario: Wide Markdown content on a narrow viewport

- **WHEN** a rendered table or code block is wider than a mobile viewport
- **THEN** that block scrolls horizontally within itself and the page has no horizontal overflow
