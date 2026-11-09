import { describe, expect, it } from "vitest";
import { createDemoQueries, reports } from "./demo";
import type { Detail } from "./domain";

describe("preview mutation invariants", () => {
  it("rejects reassignment to the current Developer without changing the report", async () => {
    const source = [structuredClone(reports[1])];
    const queries = createDemoQueries(source, true);
    const version = source[0].version;
    await expect(queries.assignReport!({ reportId: source[0].id, expectedVersion: version, developerId: source[0].assignee!.id, reason: "Capacity change" })).rejects.toMatchObject({ code: "validation" });
    expect(source[0].version).toBe(version);
    expect(source[0].assignee?.id).toBe(reports[1].assignee?.id);
  });

  it.each([
    ["returnResolution", 3, "In progress"],
    ["reopenReport", 4, "Submitted"],
  ] as const)("retains proposal identity, version and content when %s clears the current proposal", async (action, index, expectedStatus) => {
    const source: Detail[] = [structuredClone(reports[index])];
    if (action === "reopenReport") {
      source[0].currentProposal = { id: "00000000-0000-4000-8000-000000009999", version: 7, text: "Closed report proposal text", author: { id: reports[index].reporter.id, displayName: "Proposal author" }, createdAt: "2026-11-07T02:00:00Z" };
    }
    const before = source[0].currentProposal!;
    const queries = createDemoQueries(source, true);
    if (action === "returnResolution") {
      await queries.returnResolution!({ reportId: source[0].id, expectedVersion: source[0].version, proposalId: before.id, proposalVersion: before.version, reason: "Needs evidence" });
    } else {
      await queries.reopenReport!({ reportId: source[0].id, expectedVersion: source[0].version, reason: "Regression reported" });
    }
    expect(source[0].status).toBe(expectedStatus);
    expect(source[0].currentProposal).toBeNull();
    const retained = source[0].history.filter((event) => event.type === "Proposal retained").at(-1);
    expect(retained).toBeDefined();
    expect(JSON.parse(retained!.text)).toEqual({ id: before.id, version: before.version, text: before.text, author: before.author, createdAt: before.createdAt });
  });
});
