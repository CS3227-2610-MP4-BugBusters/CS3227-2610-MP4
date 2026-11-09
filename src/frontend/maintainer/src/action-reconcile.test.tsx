import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { App } from "./App";
import { reports, fixtureId } from "./demo";
import { actionFixture } from "./maintainer-actions.test-fixtures";

afterEach(cleanup);

describe("Maintainer action reconciliation", () => {
  it("keeps the typed reassignment reason when refreshing the same report after a conflict", async () => {
    const { queries, source } = actionFixture(reports[1]);
    queries.assignReport = vi.fn(async () => { throw Object.assign(new Error("stale"), { status: 409, code: "STALE_VERSION" }); });
    render(<App queries={queries} />);
    await screen.findByText(/matching reports/);
    fireEvent.click(screen.getByRole("button", { name: /Report 2:/ }));
    await screen.findByRole("heading", { name: reports[1].title });
    fireEvent.change(screen.getByLabelText(/^Assign to$/), { target: { value: fixtureId(901) } });
    fireEvent.change(screen.getByLabelText(/^Reason$/), { target: { value: "Preserve through review" } });
    fireEvent.click(screen.getByRole("button", { name: /^Reassign$/ }));
    await screen.findByRole("button", { name: /^Refresh report$/ });
    source[0].version += 1;
    fireEvent.click(screen.getByRole("button", { name: /^Refresh report$/ }));
    await screen.findByText(/version 2/);
    expect((screen.getByLabelText(/^Reason$/) as HTMLInputElement).value).toBe("Preserve through review");
    expect(queries.assignReport).toHaveBeenCalledTimes(1);
  });
});
