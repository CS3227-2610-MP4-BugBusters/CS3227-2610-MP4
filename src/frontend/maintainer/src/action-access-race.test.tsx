import { afterEach, describe, expect, it } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { App } from "./App";
import { reports } from "./demo";
import { QueryError } from "./domain";
import { actionFixture } from "./maintainer-actions.test-fixtures";

afterEach(cleanup);

describe("protected action denial after selection changes", () => {
  it.each([
    ["session", "Please sign in to continue."],
    ["denied", "Active Maintainer access is required."],
  ] as const)("clears current-session protected UI for a late %s response", async (code, message) => {
    const { queries } = actionFixture([reports[0], reports[1]]);
    let rejectAction!: (error: unknown) => void;
    queries.updatePriority = () => new Promise<void>((_, reject) => { rejectAction = reject; });

    render(<App queries={queries} />);
    await screen.findByText(/matching reports/);
    fireEvent.click(screen.getByRole("button", { name: /Report 1:/ }));
    await screen.findByRole("heading", { name: reports[0].title });
    fireEvent.click(screen.getByRole("button", { name: /^Save priority$/ }));

    fireEvent.click(screen.getByRole("button", { name: /Report 2:/ }));
    await screen.findByRole("heading", { name: reports[1].title });
    expect(screen.getByText(/matching reports/)).toBeTruthy();

    await act(async () => rejectAction(new QueryError(code)));

    await screen.findByText(message);
    expect(screen.queryByText(/matching reports/)).toBeNull();
    expect(screen.queryByRole("heading", { name: reports[1].title })).toBeNull();
  });

  it("does not let a prior-session denial clear the refreshed session's selected report", async () => {
    const { queries } = actionFixture([reports[0], reports[1]]);
    let rejectAction!: (error: unknown) => void;
    queries.updatePriority = () => new Promise<void>((_, reject) => { rejectAction = reject; });

    render(<App queries={queries} />);
    await screen.findByText(/matching reports/);
    fireEvent.click(screen.getByRole("button", { name: /Report 1:/ }));
    await screen.findByRole("heading", { name: reports[0].title });
    fireEvent.click(screen.getByRole("button", { name: /^Save priority$/ }));

    fireEvent.click(screen.getByRole("button", { name: "Refresh session and queue" }));
    await screen.findByText(/matching reports/);
    fireEvent.click(screen.getByRole("button", { name: /Report 2:/ }));
    await screen.findByRole("heading", { name: reports[1].title });

    await act(async () => rejectAction(new QueryError("denied")));

    expect(screen.queryByText("Active Maintainer access is required.")).toBeNull();
    expect(screen.getByText(/matching reports/)).toBeTruthy();
    expect(screen.getByRole("heading", { name: reports[1].title })).toBeTruthy();
  });
});
