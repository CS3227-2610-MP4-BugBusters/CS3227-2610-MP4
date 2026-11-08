import { afterEach, describe, expect, it } from "vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from "@testing-library/react";
import { App } from "./App";
import { makePriorityQueries, priorityReport } from "./priority.test-fixtures";
import type { Queries, Summary } from "./domain";

afterEach(cleanup);

describe("priority adapter method receiver", () => {
  it("preserves the adapter receiver when saving priority", async () => {
    let current = priorityReport;
    const base = makePriorityQueries(async (_reportId, input) => {
      current = {
        ...current,
        priority: input.priority,
        version: input.expectedVersion + 1,
      };
      return current;
    });
    class ReceiverQueries implements Queries {
      calls = 0;
      getCurrentUser: Queries["getCurrentUser"] = (...args) =>
        base.getCurrentUser(...args);
      listReports: Queries["listReports"] = async () => ({
        items: [current],
        total: 1,
      });
      listDevelopers: Queries["listDevelopers"] = (...args) =>
        base.listDevelopers(...args);
      getReport: Queries["getReport"] = async () => current;
      updatePriority(
        reportId: string,
        input: { expectedVersion: number; priority: Summary["priority"] },
        signal: AbortSignal,
      ) {
        this.calls += 1;
        return base.updatePriority!(reportId, input, signal);
      }
    }
    const queries = new ReceiverQueries();
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
      {
        target: { value: "HIGH" },
      },
    );
    fireEvent.click(details.getByRole("button", { name: "Save priority" }));
    await details.findByText(/version 8/);
    expect(queries.calls).toBe(1);
    expect(
      screen.getByRole("button", {
        name: /Priority workflow report.*HIGH/s,
      }),
    ).toBeTruthy();
  });
});
