import { afterEach, describe, expect, it, vi } from "vitest";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { App } from "./App";
import {
  makePriorityQueries,
  priorityReport,
  type PriorityQueries,
} from "./priority.test-fixtures";

afterEach(cleanup);

describe("MNT-P2 task 2.1 priority controls", () => {
  it("preserves the existing read-only detail and active Maintainer access controls", async () => {
    const update = vi.fn<PriorityQueries["updatePriority"]>();
    const readOnly = makePriorityQueries(update);
    render(<App queries={readOnly} />);
    await screen.findByText("1 matching reports");
    fireEvent.click(
      screen.getByRole("button", { name: /Priority workflow report/ }),
    );
    const details = await screen.findByRole("region", {
      name: "Report details",
    });
    await within(details).findByRole("heading", { name: "Description" });
    expect(
      within(details).getByText(/version 7/, { selector: "p" }).textContent,
    ).toContain("MEDIUM");
    expect(update).not.toHaveBeenCalled();

    const denied = makePriorityQueries(vi.fn());
    denied.getCurrentUser = async () => ({
      id: priorityReport.reporter.id,
      displayName: priorityReport.reporter.displayName,
      role: "DEVELOPER",
      active: true,
    });
    const deniedUpdate = vi.spyOn(denied, "updatePriority");
    cleanup();
    render(<App queries={denied} />);
    await screen.findByText("Active Maintainer access is required.");
    expect(deniedUpdate).not.toHaveBeenCalled();
  });

  it("shows the current priority and four choices without writing before explicit save", async () => {
    const update = vi.fn<PriorityQueries["updatePriority"]>();
    render(<App queries={makePriorityQueries(update)} />);
    await screen.findByText("1 matching reports");
    fireEvent.click(
      screen.getByRole("button", { name: /Priority workflow report/ }),
    );
    const details = within(
      await screen.findByRole("region", { name: "Report details" }),
    );
    await details.findByRole("heading", { name: "Description" });
    const editor = details.getByRole("combobox", { name: "Report priority" });
    expect((editor as HTMLSelectElement).value).toBe("MEDIUM");
    expect(
      within(editor)
        .getAllByRole("option")
        .map((o) => (o as HTMLOptionElement).value),
    ).toEqual(["LOW", "MEDIUM", "HIGH", "CRITICAL"]);
    expect(details.getByRole("button", { name: "Save priority" })).toBeTruthy();
    fireEvent.change(editor, { target: { value: "HIGH" } });
    expect((editor as HTMLSelectElement).value).toBe("HIGH");
    expect(
      details.getByText(/version 7/, { selector: "p" }).textContent,
    ).toContain("MEDIUM");
    expect(update).not.toHaveBeenCalled();
  });

  it("sends the selected priority with the report id and displayed version, then refreshes the visible summary/detail", async () => {
    let current = priorityReport;
    const update = vi.fn<PriorityQueries["updatePriority"]>(
      async (id, input) => {
        expect(id).toBe(priorityReport.id);
        expect(input).toEqual({ expectedVersion: 7, priority: "CRITICAL" });
        current = {
          ...current,
          priority: input.priority,
          version: 8,
          updatedAt: "2026-11-08T01:00:00Z",
        };
        return current;
      },
    );
    const queries = makePriorityQueries(update);
    queries.getReport = async () => current;
    queries.listReports = async () => ({ items: [current], total: 1 });
    render(<App queries={queries} />);
    await screen.findByText("1 matching reports");
    fireEvent.click(
      screen.getByRole("button", { name: /Priority workflow report/ }),
    );
    const details = within(
      await screen.findByRole("region", { name: "Report details" }),
    );
    await details.findByRole("heading", { name: "Description" });
    fireEvent.change(
      details.getByRole("combobox", { name: "Report priority" }),
      { target: { value: "CRITICAL" } },
    );
    fireEvent.click(details.getByRole("button", { name: "Save priority" }));
    await details.findByText(/version 8/);
    expect(update).toHaveBeenCalledTimes(1);
    expect(
      screen.getByRole("button", {
        name: /Priority workflow report.*CRITICAL/s,
      }),
    ).toBeTruthy();
  });

  it("keeps the attempted priority after a version conflict and does not retry or overwrite", async () => {
    const update = vi.fn<PriorityQueries["updatePriority"]>(async () => {
      const error = new Error("stale") as Error & {
        status: number;
        code: string;
      };
      error.status = 409;
      error.code = "STALE_VERSION";
      throw error;
    });
    render(<App queries={makePriorityQueries(update)} />);
    await screen.findByText("1 matching reports");
    fireEvent.click(
      screen.getByRole("button", { name: /Priority workflow report/ }),
    );
    const details = within(
      await screen.findByRole("region", { name: "Report details" }),
    );
    await details.findByRole("heading", { name: "Description" });
    fireEvent.change(
      details.getByRole("combobox", { name: "Report priority" }),
      { target: { value: "HIGH" } },
    );
    fireEvent.click(details.getByRole("button", { name: "Save priority" }));
    await details.findByRole("alert");
    expect(
      (
        details.getByRole("combobox", {
          name: "Report priority",
        }) as HTMLSelectElement
      ).value,
    ).toBe("HIGH");
    expect(update).toHaveBeenCalledTimes(1);
    expect(
      details.getByText(/version 7/, { selector: "p" }).textContent,
    ).toContain("MEDIUM");
  });

  it("disables duplicate saves while a priority write is pending", async () => {
    let resolve!: (value: typeof priorityReport) => void;
    const update = vi.fn<PriorityQueries["updatePriority"]>(
      () =>
        new Promise((r) => {
          resolve = r;
        }),
    );
    render(<App queries={makePriorityQueries(update)} />);
    await screen.findByText("1 matching reports");
    fireEvent.click(
      screen.getByRole("button", { name: /Priority workflow report/ }),
    );
    const details = within(
      await screen.findByRole("region", { name: "Report details" }),
    );
    await details.findByRole("heading", { name: "Description" });
    fireEvent.change(
      details.getByRole("combobox", { name: "Report priority" }),
      { target: { value: "HIGH" } },
    );
    const save = details.getByRole("button", { name: "Save priority" });
    fireEvent.click(save);
    fireEvent.click(save);
    expect(update).toHaveBeenCalledTimes(1);
    expect((save as HTMLButtonElement).disabled).toBe(true);
    await act(async () =>
      resolve({ ...priorityReport, priority: "HIGH", version: 8 }),
    );
    await waitFor(() =>
      expect(
        (
          details.getByRole("button", {
            name: "Save priority",
          }) as HTMLButtonElement
        ).disabled,
      ).toBe(false),
    );
  });
});
