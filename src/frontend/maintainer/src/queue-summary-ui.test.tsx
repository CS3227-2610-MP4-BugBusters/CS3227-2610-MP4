import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { App } from "./App";
import type { Queries } from "./domain";
import { createDemoQueries, fixtureId, reports } from "./demo";

afterEach(cleanup);

const capturedAt = "2026-11-09T08:30:00Z";
type SummaryRequest = {
  filters: { status?: string; priority?: string; assigneeId?: string };
  regenerate: boolean;
};
const finding = (text: string, reportIds: string[]) => ({ text, reportIds });
const result = (overrides: Record<string, unknown> = {}) => ({
  capturedAt,
  filters: {},
  included: [{ id: reports[0].id, version: reports[0].version, truncatedFields: ["description"] }],
  excluded: [{ id: reports[1].id, reason: "Context limit" }],
  findings: {
    overview: [finding("Investigate the intermittent timeout", [reports[0].id])],
    blockers: [],
    missingInformation: [finding("Reproduction details are incomplete", [reports[0].id])],
  },
  requiresReview: true,
  providerMode: "deterministic",
  promptVersion: "MNT-SUMMARY-1",
  ...overrides,
});
const summaryFor = (request: SummaryRequest) => result({ filters: request.filters });

const deferred = <T,>() => {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};

function setup(
  summarizeQueue: (request: SummaryRequest, signal?: AbortSignal) => Promise<unknown>,
  overrides: Partial<Queries> = {},
) {
  const queries = {
    ...createDemoQueries(),
    ...overrides,
    summarizeQueue,
  };
  return queries as unknown as Queries;
}

const summaryButton = () => screen.getByRole("button", { name: /generate summary/i });
const summaryPanel = () => screen.getByRole("region", { name: /summary/i });

describe("MNT-P4 queue summary UI", () => {
  it("shows a reviewable summary with capture time, included/excluded reports, truncation and validated report links", async () => {
    const summarize = vi.fn(async (request: SummaryRequest) => summaryFor(request));
    render(<App queries={setup(summarize)} />);
    await screen.findByText("63 matching reports");

    fireEvent.click(summaryButton());

    expect(await screen.findByText("Investigate the intermittent timeout")).toBeTruthy();
    expect(screen.getByText(/captured/i).textContent).toContain(capturedAt);
    expect(summaryPanel().textContent).toMatch(/review/i);
    expect(summaryPanel().textContent).toContain(reports[0].id);
    expect(summaryPanel().textContent).toContain(reports[1].id);
    expect(screen.getByText(/Context limit/)).toBeTruthy();
    expect(screen.getByText(/description.*truncated/i)).toBeTruthy();
    const reportLinks = screen.getAllByRole("link");
    expect(reportLinks.some((link) => link.getAttribute("href")?.includes(reports[0].id))).toBe(true);
    expect(summarize).toHaveBeenCalledWith({ filters: {}, regenerate: false }, expect.anything());
  });

  it("submits canonical full-queue filters without list pagination and prevents selecting Closed", async () => {
    const summarize = vi.fn(async (request: SummaryRequest) => summaryFor(request));
    render(<App queries={setup(summarize)} />);
    await screen.findByText("63 matching reports");
    fireEvent.change(screen.getByLabelText("Priority"), { target: { value: "HIGH" } });
    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "Assigned" } });
    fireEvent.change(screen.getByLabelText("Assignee"), { target: { value: fixtureId(901) } });
    await waitFor(() => expect(screen.getByText("2 matching reports")).toBeTruthy());

    fireEvent.click(summaryButton());
    await waitFor(() => expect(summarize).toHaveBeenCalled());
    expect(summarize.mock.calls[0][0]).toEqual({
      filters: { priority: "HIGH", status: "Assigned", assigneeId: fixtureId(901) },
      regenerate: false,
    });
    expect((summarize.mock.calls[0][0] as { filters: Record<string, unknown> }).filters)
      .not.toHaveProperty("offset");
    expect((summarize.mock.calls[0][0] as { filters: Record<string, unknown> }).filters)
      .not.toHaveProperty("limit");
    const closed = screen.getByRole("option", { name: "Closed" }) as HTMLOptionElement;
    expect(closed.disabled).toBe(false);
    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "Closed" } });
    await screen.findByText("2 matching reports");
    await screen.findByRole("button", { name: /Report 15:/ });
    await screen.findByRole("button", { name: /Report 35:/ });
    expect((summaryButton() as HTMLButtonElement).disabled).toBe(true);
    expect(summarize).toHaveBeenCalledTimes(1);
  });

  it("uses the same selected filters after navigating to another list page", async () => {
    const summarize = vi.fn(async (request: SummaryRequest) => summaryFor(request));
    render(<App queries={setup(summarize)} />);
    await screen.findByText("63 matching reports");
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    await screen.findByText("Page 2");
    fireEvent.click(summaryButton());
    await waitFor(() => expect(summarize).toHaveBeenCalled());
    expect(summarize.mock.calls[0][0]).toEqual({ filters: {}, regenerate: false });
  });

  it("disables duplicate generation while pending and supports explicit regeneration", async () => {
    const pending = deferred<unknown>();
    const summarize = vi.fn((_request: SummaryRequest) => pending.promise);
    render(<App queries={setup(summarize)} />);
    await screen.findByText("63 matching reports");
    fireEvent.click(summaryButton());
    await waitFor(() => expect(summarize).toHaveBeenCalledTimes(1));
    expect((summaryButton() as HTMLButtonElement).disabled).toBe(true);
    await act(async () => pending.resolve(summaryFor(summarize.mock.calls[0][0])));
    await screen.findByText("Investigate the intermittent timeout");
    const regenerate = screen.getByRole("button", { name: /regenerate/i });
    fireEvent.click(regenerate);
    await waitFor(() => expect(summarize).toHaveBeenCalledTimes(2));
    expect(summarize.mock.calls[1][0]).toEqual({ filters: {}, regenerate: true });
  });

  it("keeps non-AI report browsing usable after summary failure and shows a safe error", async () => {
    const summarize = vi.fn(async (_request: SummaryRequest) => { throw new Error("provider secret raw body"); });
    render(<App queries={setup(summarize)} />);
    await screen.findByText("63 matching reports");
    fireEvent.click(summaryButton());
    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(screen.queryByText(/provider secret raw body/)).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /Report 1:/ }));
    expect(await screen.findByRole("heading", { name: "Description" })).toBeTruthy();
  });

  it("invalidates the displayed result when filters change and when the queue refreshes", async () => {
    const summarize = vi.fn(async (request: SummaryRequest) => summaryFor(request));
    render(<App queries={setup(summarize)} />);
    await screen.findByText("63 matching reports");
    fireEvent.click(summaryButton());
    await screen.findByText("Investigate the intermittent timeout");
    fireEvent.change(screen.getByLabelText("Priority"), { target: { value: "HIGH" } });
    await screen.findByText("16 matching reports");
    expect(screen.queryByText("Investigate the intermittent timeout")).toBeNull();
    fireEvent.click(summaryButton());
    await screen.findByText("Investigate the intermittent timeout");
    fireEvent.click(screen.getByRole("button", { name: "Refresh session and queue" }));
    await screen.findByText("16 matching reports");
    expect(screen.queryByText("Investigate the intermittent timeout")).toBeNull();
  });

  it("invalidates a displayed summary after a successful queue mutation", async () => {
    const summarize = vi.fn(async (request: SummaryRequest) => summaryFor(request));
    const updatePriority = vi.fn(async () => undefined);
    render(<App queries={setup(summarize, { updatePriority })} />);
    await screen.findByText("63 matching reports");
    fireEvent.click(summaryButton());
    await screen.findByText("Investigate the intermittent timeout");
    fireEvent.click(screen.getByRole("button", { name: /Report 1:/ }));
    await screen.findByRole("heading", { name: reports[0].title });
    fireEvent.change(screen.getByLabelText("Report priority"), { target: { value: "HIGH" } });
    fireEvent.click(screen.getByRole("button", { name: "Save priority" }));
    await waitFor(() => expect(updatePriority).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.queryByText("Investigate the intermittent timeout")).toBeNull());
  });

  it("clears a displayed summary after refresh establishes that Maintainer access was lost", async () => {
    const identity = vi.fn(createDemoQueries().getCurrentUser)
      .mockResolvedValueOnce({ id: fixtureId(1), displayName: "Maintainer", role: "MAINTAINER", active: true })
      .mockResolvedValueOnce({ id: fixtureId(1), displayName: "Maintainer", role: "REPORTER", active: true });
    const summarize = vi.fn(async (request: SummaryRequest) => summaryFor(request));
    render(<App queries={setup(summarize, { getCurrentUser: identity })} />);
    await screen.findByText("63 matching reports");
    fireEvent.click(summaryButton());
    await screen.findByText("Investigate the intermittent timeout");
    fireEvent.click(screen.getByRole("button", { name: "Refresh session and queue" }));
    await screen.findByText("Active Maintainer access is required.");
    expect(screen.queryByText("Investigate the intermittent timeout")).toBeNull();
  });

  it("discards a late result after selection filters change while the adapter ignores cancellation", async () => {
    const pending = deferred<unknown>();
    const summarize = vi.fn((_request: SummaryRequest) => pending.promise);
    render(<App queries={setup(summarize)} />);
    await screen.findByText("63 matching reports");
    fireEvent.click(summaryButton());
    await waitFor(() => expect(summarize).toHaveBeenCalledTimes(1));
    fireEvent.change(screen.getByLabelText("Priority"), { target: { value: "HIGH" } });
    await screen.findByText("16 matching reports");
    await act(async () => pending.resolve(result()));
    expect(screen.queryByText("Investigate the intermittent timeout")).toBeNull();
  });

  it("renders provider markup as text and does not execute generated HTML", async () => {
    const payload = result({
      findings: {
        overview: [finding('<img src=x onerror="alert(1)">', [reports[0].id])],
        blockers: [],
        missingInformation: [],
      },
    });
    render(<App queries={setup(async (_request: SummaryRequest) => payload)} />);
    await screen.findByText("63 matching reports");
    fireEvent.click(summaryButton());
    expect(await screen.findByText('<img src=x onerror="alert(1)">')).toBeTruthy();
    expect(document.querySelector("img")).toBeNull();
  });
});
