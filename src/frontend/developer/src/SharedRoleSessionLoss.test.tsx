import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { DeveloperApp } from "./DeveloperApp";

const navigate = vi.fn();

const report = {
  id: "00000000-0000-4000-8000-000000000301",
  title: "Protected report title",
  status: "ASSIGNED",
};
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status,
  headers: { "Content-Type": "application/json" },
});

let reportStatus: number;
let fetchMock: ReturnType<typeof vi.fn<typeof fetch>>;

beforeEach(() => {
  navigate.mockClear();
  reportStatus = 200;
  fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path === "/api/auth/me") {
      return json({ id: "dev-1", displayName: "Developer One", role: "DEVELOPER", active: true });
    }
    if (path === "/api/developer/reports") {
      return json(reportStatus === 200 ? { items: [report], page: 1, pageSize: 20, totalItems: 1 } : {
        code: "UNAUTHENTICATED", message: "Sign in", requestId: "request-1",
      }, reportStatus);
    }
    throw new Error(`Unexpected request: ${path}`);
  });
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("Developer shared workspace when the report session expires", () => {
  it("keeps identity and protected navigation for a successful assigned-report read", async () => {
    render(<DeveloperApp navigate={navigate} />);

    expect(await screen.findByText(report.title)).toBeTruthy();
    expect(screen.getByText("Developer One")).toBeTruthy();
    expect(screen.getByRole("navigation", { name: "Developer navigation" })).toBeTruthy();
  });

  it("clears identity and protected navigation when assigned reports return 401", async () => {
    reportStatus = 401;
    render(<DeveloperApp navigate={navigate} />);

    const alert = await screen.findByRole("alert");
    expect.soft(alert.textContent).toMatch(/session|sign in/i);
    expect.soft(screen.queryByText("Developer One")).toBeNull();
    expect.soft(screen.queryByRole("navigation", { name: "Developer navigation" })).toBeNull();
    expect.soft(screen.queryByText(report.title)).toBeNull();
    expect.soft(screen.queryByLabelText("Username")).toBeNull();
    await waitFor(() => expect(navigate).toHaveBeenCalledWith("/"));
    expect.soft(screen.queryByText(/UNAUTHENTICATED|request-1/)).toBeNull();
    expect(fetchMock).toHaveBeenCalledWith("/api/auth/me", expect.anything());
    expect(fetchMock).toHaveBeenCalledWith("/api/developer/reports", expect.anything());
  });
});
