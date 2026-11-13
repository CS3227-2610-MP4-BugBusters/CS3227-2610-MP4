import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { DeveloperApp } from "./DeveloperApp";

const report = {
  id: "00000000-0000-4000-8000-000000000101",
  title: "Workspace access report",
  status: "ASSIGNED",
};
let authenticated = false;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

beforeEach(() => {
  authenticated = true;
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path === "/api/auth/me") {
        return authenticated
          ? json({ id: "dev-1", displayName: "Developer One", role: "DEVELOPER", active: true })
          : json({ code: "UNAUTHENTICATED", message: "Sign in" }, 401);
      }
      if (path === "/api/developer/reports") {
        return json({ items: [report], page: 1, pageSize: 20, totalItems: 1 });
      }
      if (path === "/api/auth/csrf") return json({ token: "csrf", headerName: "X-CSRF-TOKEN" });
      if (path === "/api/auth/logout") {
        authenticated = false;
        return new Response(null, { status: 204 });
      }
      throw new Error(`Unexpected request: ${path}`);
    }),
  );
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("Developer role workspace", () => {
  it("provides accessible role navigation while keeping assigned report actions reachable", async () => {
    render(<DeveloperApp />);

    expect(await screen.findByText(report.title)).toBeTruthy();
    expect(screen.getByRole("navigation", { name: /developer/i })).toBeTruthy();
    expect(screen.queryByRole("link", { name: "Account" })).toBeNull();
    expect(screen.getByRole("link", { name: "Dashboard" }).getAttribute("href")).toBe("#workspace-content");
    expect(screen.getByRole("heading", { name: /developer reports/i })).toBeTruthy();
  });

  it("keeps existing sign-out and assigned report selection reachable", async () => {
    render(<DeveloperApp />);

    expect(await screen.findByText(report.title)).toBeTruthy();
    expect(screen.getByRole("button", { name: /Workspace access report/ })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Sign out" }));
    expect(await screen.findByLabelText("Username")).toBeTruthy();
  });

  it("does not expose report content or report actions while signed out", async () => {
    authenticated = false;
    render(<DeveloperApp />);

    expect(await screen.findByLabelText("Username")).toBeTruthy();
    expect(screen.queryByText(report.title)).toBeNull();
    expect(screen.queryByRole("button", { name: /sign out/i })).toBeNull();
  });
});
