import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { App } from "./App";
import { reports, fixtureId } from "./demo";
import { QueryError } from "./domain";
import { actionFixture } from "./maintainer-actions.test-fixtures";

afterEach(cleanup);
const mountReport = async (i: number) => {
  const { queries, calls, source } = actionFixture(reports[i]);
  render(<App queries={queries} />);
  await screen.findByText(/matching reports/);
  fireEvent.click(screen.getByRole("button", { name: new RegExp(`Report ${i + 1}:`) }));
  await screen.findByRole("heading", { name: reports[i].title });
  return { calls, queries, source };
};

describe("Maintainer action UI contracts (T1/A1/R1/D1)", () => {
  it("saves an explicit priority with the selected report id and expected version", async () => {
    const { calls } = await mountReport(0);
    fireEvent.change(screen.getByLabelText(/^Report priority$/), { target: { value: "CRITICAL" } });
    fireEvent.click(screen.getByRole("button", { name: /^Save priority$/ }));
    await waitFor(() => expect(calls.updatePriority).toEqual([{ reportId: reports[0].id, expectedVersion: reports[0].version, priority: "CRITICAL" }]));
  });

  it("refreshes list and detail after a successful priority change", async () => {
    const { queries, source } = actionFixture(reports[0]);
    const list = vi.spyOn(queries, "listReports");
    const detail = vi.spyOn(queries, "getReport");
    queries.updatePriority = vi.fn(async ({ priority }: { priority: "LOW" | "MEDIUM" | "HIGH" | "CRITICAL" }) => {
      source[0].priority = priority;
      source[0].version++;
    });
    render(<App queries={queries} />);
    await screen.findByText(/matching reports/);
    fireEvent.click(screen.getByRole("button", { name: /Report 1:/ }));
    await screen.findByRole("heading", { name: reports[0].title });
    fireEvent.change(screen.getByLabelText(/^Report priority$/), { target: { value: "CRITICAL" } });
    fireEvent.click(screen.getByRole("button", { name: /^Save priority$/ }));
    await screen.findByText(/CRITICAL.*version 1/);
    expect(list).toHaveBeenCalledTimes(2);
    expect(detail).toHaveBeenCalledTimes(2);
  });

  it("assigns an eligible Submitted report to a selected active Developer", async () => {
    const { calls } = await mountReport(0);
    fireEvent.change(screen.getByLabelText(/^Assign to$/), { target: { value: fixtureId(901) } });
    fireEvent.click(screen.getByRole("button", { name: /^Assign$/ }));
    await waitFor(() => expect(calls.assignReport).toEqual([{ reportId: reports[0].id, expectedVersion: reports[0].version, developerId: fixtureId(901) }]));
  });

  it("requires a reason when reassigning and sends it with current version", async () => {
    const { calls } = await mountReport(1);
    fireEvent.change(screen.getByLabelText(/^Assign to$/), { target: { value: fixtureId(901) } });
    fireEvent.change(screen.getByLabelText(/^Reason$/), { target: { value: "Capacity change" } });
    fireEvent.click(screen.getByRole("button", { name: /^Reassign$/ }));
    await waitFor(() => expect(calls.assignReport).toEqual([{ reportId: reports[1].id, expectedVersion: reports[1].version, developerId: fixtureId(901), reason: "Capacity change" }]));
  });

  it("does not call close without explicit review acknowledgement", async () => {
    const { queries, calls } = await mountReport(3);
    expect(screen.queryByLabelText(/I reviewed this proposal/)).toBeTruthy();
    expect((screen.getByRole("button", { name: /^Close$/ }) as HTMLButtonElement).disabled).toBe(true);
    expect(calls.closeReport).toEqual([]);
    expect(queries.closeReport).toBeDefined();
  });

  it("closes only after explicit acknowledgement and sends current proposal identity and versions", async () => {
    const { calls } = await mountReport(3);
    expect((screen.getByRole("button", { name: /^Close$/ }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByLabelText(/I reviewed this proposal/));
    fireEvent.click(screen.getByRole("button", { name: /^Close$/ }));
    await waitFor(() => expect(calls.closeReport).toEqual([{ reportId: reports[3].id, expectedVersion: reports[3].version, proposalId: reports[3].currentProposal!.id, proposalVersion: reports[3].currentProposal!.version, reviewed: true }]));
  });

  it("returns a proposal with a required reason and exact proposal version", async () => {
    const { calls } = await mountReport(3);
    fireEvent.change(screen.getByLabelText(/^Return reason$/), { target: { value: "Needs more evidence" } });
    fireEvent.click(screen.getByRole("button", { name: /^Return resolution$/ }));
    await waitFor(() => expect(calls.returnResolution).toEqual([{ reportId: reports[3].id, expectedVersion: reports[3].version, proposalId: reports[3].currentProposal!.id, proposalVersion: reports[3].currentProposal!.version, reason: "Needs more evidence" }]));
  });

  it("reopens a Closed report only with a reason and expected version", async () => {
    const { calls } = await mountReport(4);
    fireEvent.change(screen.getByLabelText(/^Reopen reason$/), { target: { value: "Regression reported" } });
    fireEvent.click(screen.getByRole("button", { name: /^Reopen$/ }));
    await waitFor(() => expect(calls.reopenReport).toEqual([{ reportId: reports[4].id, expectedVersion: reports[4].version, reason: "Regression reported" }]));
  });

  it("blocks return when the required reason is empty", async () => {
    const { calls } = await mountReport(3);
    const button = screen.getByRole("button", { name: /^Return resolution$/ });
    expect((button as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(button);
    expect(calls.returnResolution).toEqual([]);
  });

  it("adds discussion as escaped plain text without changing lifecycle", async () => {
    const { calls, source, queries } = await mountReport(0);
    const text = "<img src=x onerror=alert(1)>";
    const comment = { id: fixtureId(999), author: reports[0].reporter, text, createdAt: "2026-11-09T00:00:00Z" };
    queries.addComment = async (input) => { calls.addComment.push(input); source[0].comments.push(comment); };
    fireEvent.change(screen.getByLabelText(/^Add comment$/), { target: { value: text } });
    fireEvent.click(screen.getByRole("button", { name: /^Add comment$/ }));
    await waitFor(() => expect(screen.getByText(text)).toBeTruthy());
    expect(document.querySelector("img")).toBeNull();
  });

  it("disables duplicate action submission while a mutation is pending", async () => {
    const { queries } = actionFixture(reports[0]);
    const pending = new Promise<void>(() => {});
    queries.updatePriority = vi.fn(() => pending);
    render(<App queries={queries} />);
    await screen.findByText(/matching reports/);
    fireEvent.click(screen.getByRole("button", { name: /Report 1:/ }));
    await screen.findByRole("heading", { name: reports[0].title });
    fireEvent.click(screen.getByRole("button", { name: /^Save priority$/ }));
    fireEvent.click(screen.getByRole("button", { name: /^Save priority$/ }));
    expect(queries.updatePriority).toHaveBeenCalledTimes(1);
    expect((screen.getByRole("button", { name: /^Save priority$/ }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("preserves typed reassignment reason after a version conflict without retrying", async () => {
    const { queries } = actionFixture(reports[1]);
    queries.assignReport = vi.fn(async () => { throw Object.assign(new Error("conflict"), { status: 409, code: "STALE_VERSION" }); });
    render(<App queries={queries} />);
    await screen.findByText(/matching reports/);
    fireEvent.click(screen.getByRole("button", { name: /Report 2:/ }));
    await screen.findByRole("heading", { name: reports[1].title });
    fireEvent.change(screen.getByLabelText(/^Assign to$/), { target: { value: fixtureId(901) } });
    fireEvent.change(screen.getByLabelText(/^Reason$/), { target: { value: "Keep this reason" } });
    fireEvent.click(screen.getByRole("button", { name: /^Reassign$/ }));
    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toMatch(/conflict|changed|stale|version/i);
    expect((screen.getByLabelText(/^Reason$/) as HTMLInputElement).value).toBe("Keep this reason");
    expect(queries.assignReport).toHaveBeenCalledTimes(1);
  });

  it("discards a pending mutation result after selection changes", async () => {
    const { queries } = actionFixture([reports[0], reports[1]]);
    let finish!: () => void;
    queries.updatePriority = () => new Promise<void>((resolve) => { finish = resolve; });
    render(<App queries={queries} />);
    await screen.findByText(/matching reports/);
    fireEvent.click(screen.getByRole("button", { name: /Report 1:/ }));
    await screen.findByRole("heading", { name: reports[0].title });
    fireEvent.click(screen.getByRole("button", { name: /^Save priority$/ }));
    fireEvent.click(screen.getByRole("button", { name: /Report 2:/ }));
    await screen.findByRole("heading", { name: reports[1].title });
    await act(async () => finish());
    expect(screen.getByRole("heading", { name: reports[1].title })).toBeTruthy();
  });

  it("discards a pending mutation response after access is denied", async () => {
    const { queries } = actionFixture(reports[0]);
    let finish!: () => void;
    queries.updatePriority = () => new Promise<void>((resolve) => { finish = resolve; });
    let deny!: (error: unknown) => void;
    queries.listDevelopers = () => new Promise((_, reject) => { deny = reject; });
    render(<App queries={queries} />);
    await screen.findByText(/matching reports/);
    fireEvent.click(screen.getByRole("button", { name: /Report 1:/ }));
    await screen.findByRole("heading", { name: reports[0].title });
    fireEvent.click(screen.getByRole("button", { name: /^Save priority$/ }));
    await act(async () => deny(new QueryError("denied")));
    await screen.findByText("Active Maintainer access is required.");
    await act(async () => finish());
    expect(screen.queryByRole("heading", { name: reports[0].title })).toBeNull();
  });
});
