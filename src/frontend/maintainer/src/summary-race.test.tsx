import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { App } from "./App";
import { QueryError, type Queries } from "./domain";
import { createDemoQueries, reports } from "./demo";

afterEach(cleanup);

describe("queue summary session races", () => {
  it("clears protected data for a current-session denial after filters change", async () => {
    let reject!: (error: unknown) => void;
    const pending = new Promise<never>((_resolve, fail) => { reject = fail; });
    const queries = {
      ...createDemoQueries(),
      summarizeQueue: vi.fn(() => pending),
    } as unknown as Queries;
    render(<App queries={queries} />);
    await screen.findByText("63 matching reports");
    fireEvent.click(screen.getByRole("button", { name: "Generate summary" }));
    await waitFor(() => expect(queries.summarizeQueue).toHaveBeenCalledTimes(1));
    fireEvent.change(screen.getByLabelText("Priority"), { target: { value: "HIGH" } });
    await screen.findByText("16 matching reports");
    reject(new QueryError("denied"));
    await screen.findByText("Active Maintainer access is required.");
    expect(screen.queryByRole("region", { name: "Queue summary" })).toBeNull();
    expect(screen.queryByText("Report 1: Login delay")).toBeNull();
  });

  it("invalidates a summary when a cancellation-ignoring mutation completes after selection changed", async () => {
    let finishMutation!: () => void;
    const mutation = new Promise<void>((resolve) => { finishMutation = resolve; });
    const queries = {
      ...createDemoQueries(undefined, true),
      updatePriority: vi.fn(() => mutation),
      summarizeQueue: vi.fn(async () => ({
        capturedAt: "2026-11-09T08:30:00Z",
        filters: {},
        included: [{ id: reports[0].id, version: reports[0].version, truncatedFields: [] }],
        excluded: [],
        findings: { overview: [{ text: "Review this report.", reportIds: [reports[0].id] }], blockers: [], missingInformation: [] },
        requiresReview: true,
        providerMode: "deterministic",
        promptVersion: "MNT-SUMMARY-1",
      })),
    } as unknown as Queries;
    render(<App queries={queries} />);
    await screen.findByText("63 matching reports");
    fireEvent.click(screen.getByRole("button", { name: /Report 1:/ }));
    await screen.findByRole("heading", { name: reports[0].title });
    fireEvent.change(screen.getByLabelText("Report priority"), { target: { value: "HIGH" } });
    fireEvent.click(screen.getByRole("button", { name: "Save priority" }));
    await waitFor(() => expect(queries.updatePriority).toHaveBeenCalledTimes(1));
    expect((screen.getByRole("button", { name: "Generate summary" }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: /Report 2:/ }));
    await screen.findByRole("heading", { name: reports[1].title });
    fireEvent.click(screen.getByRole("button", { name: "Generate summary" }));
    await screen.findByText("Review this report.");
    finishMutation();
    await waitFor(() => expect(screen.queryByText("Review this report.")).toBeNull());
  });
});
