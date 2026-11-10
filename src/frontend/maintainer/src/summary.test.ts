import { describe, expect, it } from "vitest";
import { QueryError } from "./domain";
import { parseSummaryResult } from "./summary";

const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, "0")}`;
const result = (overrides: Record<string, unknown> = {}) => ({
  capturedAt: "2026-11-09T08:30:00Z",
  filters: {},
  included: [{ id: id(1), version: 4, truncatedFields: ["title", "description", "reproductionSteps", "expectedBehavior", "actualBehavior", "environment", "latestUpdates"] }],
  excluded: [],
  findings: { overview: [{ text: "😀".repeat(600), reportIds: [id(1)] }], blockers: [], missingInformation: [] },
  requiresReview: true,
  providerMode: "deterministic",
  promptVersion: "MNT-SUMMARY-1",
  ...overrides,
});

describe("queue summary response validation", () => {
  it("accepts all seven backend truncation fields and 600 Unicode code points", () => {
    const parsed = parseSummaryResult(result());
    expect(parsed.included[0].truncatedFields).toContain("latestUpdates");
    expect([...parsed.findings.overview[0].text]).toHaveLength(600);
  });

  it.each([
    result({ included: [{ id: "not-a-uuid", version: 0, truncatedFields: [] }] }),
    result({ included: Array.from({ length: 21 }, (_, n) => ({ id: id(n + 1), version: 0, truncatedFields: [] })) }),
    result({ included: Array.from({ length: 20 }, (_, n) => ({ id: id(n + 1), version: 0, truncatedFields: [] })), excluded: Array.from({ length: 981 }, (_, n) => ({ id: id(n + 21), reason: "context limit" })) }),
    result({ findings: { overview: [{ text: "😀".repeat(601), reportIds: [id(1)] }], blockers: [], missingInformation: [] } }),
  ])("rejects malformed identifiers or structures beyond the backend bounds", (value) => {
    expect(() => parseSummaryResult(value)).toThrow(QueryError);
  });
});
