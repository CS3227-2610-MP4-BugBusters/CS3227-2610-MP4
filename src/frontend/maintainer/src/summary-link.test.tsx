import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { App } from "./App";
import type { Queries } from "./domain";
import { createDemoQueries, reports } from "./demo";

afterEach(cleanup);

describe("full-queue summary source links", () => {
  it("loads a validated source report even when it is off the current queue page", async () => {
    const report = reports[3];
    const queries = {
      ...createDemoQueries(),
      summarizeQueue: vi.fn(async ({ filters }: { filters: Record<string, string> }) => ({
        capturedAt: "2026-11-09T08:30:00Z",
        filters,
        included: [{ id: report.id, version: report.version, truncatedFields: [] }],
        excluded: [],
        findings: { overview: [{ text: "Inspect this source.", reportIds: [report.id] }], blockers: [], missingInformation: [] },
        requiresReview: true,
        providerMode: "deterministic",
        promptVersion: "MNT-SUMMARY-1",
      })),
    } as unknown as Queries;
    render(<App queries={queries} />);
    await screen.findByText("63 matching reports");
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    await screen.findByText("Page 2");
    fireEvent.click(screen.getByRole("button", { name: "Generate summary" }));
    const sourceLinks = await screen.findAllByRole("link", { name: report.id });
    const sourceLink = sourceLinks[0];
    expect(sourceLink.getAttribute("href")).toBe(`#report-${report.id}`);
    fireEvent.click(sourceLink);
    await screen.findByRole("heading", { name: report.title });
    expect(screen.getByRole("heading", { name: "Description" })).toBeTruthy();
  });
});
