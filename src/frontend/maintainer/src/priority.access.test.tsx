import { afterEach, describe, expect, it, vi } from "vitest";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from "@testing-library/react";
import { App } from "./App";
import { makePriorityQueries, priorityReport } from "./priority.test-fixtures";
import { fixtureId } from "./demo";

afterEach(cleanup);

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (cause: unknown) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

function multiReportQueries(update: ReturnType<typeof vi.fn>) {
  const second = {
    ...priorityReport,
    id: fixtureId(2),
    title: "Second report",
  };
  const queries = makePriorityQueries(update as never);
  queries.listReports = async () => ({
    items: [priorityReport, second],
    total: 2,
  });
  queries.getReport = async (id) =>
    id === second.id ? second : priorityReport;
  return { queries, second };
}

describe("priority write access and selection guards", () => {
  it("clears protected data for a current-session denial even after selection changes", async () => {
    const write = deferred<typeof priorityReport>();
    const update = vi.fn(() => write.promise);
    const { queries, second } = multiReportQueries(update);
    render(<App queries={queries} />);
    await screen.findByText("2 matching reports");
    fireEvent.click(
      screen.getByRole("button", { name: /Priority workflow report/ }),
    );
    const oldDetails = within(
      await screen.findByRole("region", { name: "Report details" }),
    );
    await oldDetails.findByRole("heading", { name: "Description" });
    fireEvent.change(
      oldDetails.getByRole("combobox", { name: "Report priority" }),
      {
        target: { value: "HIGH" },
      },
    );
    fireEvent.click(oldDetails.getByRole("button", { name: "Save priority" }));
    fireEvent.click(screen.getByRole("button", { name: /Second report/ }));
    await within(
      screen.getByRole("region", { name: "Report details" }),
    ).findByRole("heading", {
      name: "Second report",
    });

    await act(async () => {
      const denied = Object.assign(new Error("private upstream response"), {
        status: 403,
      });
      write.reject(denied);
      await Promise.resolve();
    });
    await screen.findByText("Active Maintainer access is required.");
    expect(screen.queryByRole("button", { name: /Second report/ })).toBeNull();
    expect(screen.queryByText("private upstream response")).toBeNull();
    expect(second.id).toBe(fixtureId(2));
  });

  it("ignores a denial from an older identity epoch", async () => {
    const write = deferred<typeof priorityReport>();
    const update = vi.fn(() => write.promise);
    const { queries } = multiReportQueries(update);
    render(<App queries={queries} />);
    await screen.findByText("2 matching reports");
    fireEvent.click(
      screen.getByRole("button", { name: /Priority workflow report/ }),
    );
    const details = within(
      await screen.findByRole("region", { name: "Report details" }),
    );
    await details.findByRole("heading", { name: "Description" });
    fireEvent.change(
      details.getByRole("combobox", { name: "Report priority" }),
      {
        target: { value: "HIGH" },
      },
    );
    fireEvent.click(details.getByRole("button", { name: "Save priority" }));
    fireEvent.click(
      screen.getByRole("button", { name: "Refresh session and queue" }),
    );
    await screen.findByText("2 matching reports");
    fireEvent.click(
      screen.getByRole("button", { name: /Priority workflow report/ }),
    );
    await within(
      screen.getByRole("region", { name: "Report details" }),
    ).findByRole("heading", {
      name: "Description",
    });

    await act(async () => {
      write.reject(Object.assign(new Error("stale session"), { status: 403 }));
      await Promise.resolve();
    });
    expect(
      screen.queryByText("Active Maintainer access is required."),
    ).toBeNull();
    expect(
      screen.getByRole("heading", { name: "Priority workflow report" }),
    ).toBeTruthy();
  });
});
