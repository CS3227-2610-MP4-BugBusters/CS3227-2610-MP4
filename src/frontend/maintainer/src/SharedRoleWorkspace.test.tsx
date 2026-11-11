import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { App } from "./App";
import { QueryError, type Queries } from "./domain";
import { createDemoQueries, fixtureId } from "./demo";

afterEach(cleanup);

const setup = (overrides: Partial<Queries> = {}) => ({
  ...createDemoQueries(),
  ...overrides,
});

describe("Maintainer role workspace", () => {
  it("provides accessible role navigation while keeping queue actions reachable", async () => {
    render(<App queries={setup()} />);

    expect(await screen.findByText("63 matching reports")).toBeTruthy();
    expect(screen.getByRole("navigation", { name: /maintainer/i })).toBeTruthy();
    expect(screen.getByRole("heading", { name: "Report queue" })).toBeTruthy();
  });

  it("keeps existing queue filters and refresh action reachable", async () => {
    render(<App queries={setup()} />);

    expect(await screen.findByText("63 matching reports")).toBeTruthy();
    expect(screen.getByLabelText("Queue filters")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Refresh session and queue" })).toBeTruthy();
  });

  it("does not expose protected report content when access is denied", async () => {
    const listReports = vi.fn();
    render(
      <App
        queries={setup({
          getCurrentUser: async () => ({
            id: fixtureId(1),
            displayName: "Other Role",
            role: "REPORTER",
            active: true,
          }),
          listReports,
        })}
      />,
    );

    expect(await screen.findByText("Active Maintainer access is required.")).toBeTruthy();
    expect(screen.queryByText("Report 1: Login delay")).toBeNull();
    expect(listReports).not.toHaveBeenCalled();
  });

  it("does not expose report content when the session is unavailable", async () => {
    const listReports = vi.fn();
    render(
      <App
        queries={setup({
          getCurrentUser: async () => {
            throw new QueryError("session");
          },
          listReports,
        })}
      />
    );

    expect(await screen.findByText("Please sign in to continue.")).toBeTruthy();
    expect(screen.queryByText("Report 1: Login delay")).toBeNull();
    expect(listReports).not.toHaveBeenCalled();
  });
});
