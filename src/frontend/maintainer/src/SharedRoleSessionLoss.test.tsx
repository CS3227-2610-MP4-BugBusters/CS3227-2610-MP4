import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { App } from "./App";
import { QueryError, type Queries } from "./domain";
import { createDemoQueries, reports } from "./demo";

afterEach(cleanup);

const setup = (overrides: Partial<Queries> = {}) => ({ ...createDemoQueries(), ...overrides });

describe("Maintainer shared workspace when a protected query loses its session", () => {
  it("keeps identity and protected queue content after a successful query", async () => {
    render(<App queries={setup()} />);

    expect(await screen.findByText("63 matching reports")).toBeTruthy();
    expect(screen.getByText("Jamie Ng")).toBeTruthy();
    expect(screen.getByRole("navigation", { name: /maintainer/i })).toBeTruthy();
  });

  it("clears identity, navigation, and prior queue data after a session query error", async () => {
    const list = vi.fn<Queries["listReports"]>()
      .mockImplementationOnce(createDemoQueries().listReports)
      .mockRejectedValueOnce(new QueryError("session"));
    render(<App queries={setup({ listReports: list })} />);

    expect(await screen.findByText("63 matching reports")).toBeTruthy();
    expect(screen.getByText("Jamie Ng")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "Assigned" } });

    expect(await screen.findByText("Please sign in to continue.")).toBeTruthy();
    await waitFor(() => {
      expect(screen.queryByText("Jamie Ng")).toBeNull();
      expect(screen.queryByRole("navigation", { name: /maintainer/i })).toBeNull();
      expect(screen.queryByText("63 matching reports")).toBeNull();
      expect(screen.queryByText(reports[0].title)).toBeNull();
    });
    expect(list).toHaveBeenCalledTimes(2);
    expect(screen.queryByText(/private|internal|stack trace/i)).toBeNull();
  });
});
