import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { DeveloperApp } from "./DeveloperApp";

vi.mock("./DeveloperChecklist", () => ({
  DeveloperChecklist: ({ reportId }: { reportId: string }) => (
    <div>Checklist for {reportId}</div>
  ),
}));

const currentUserPath = "/api/auth/me";
const csrfPath = "/api/auth/csrf";
const loginPath = "/api/auth/login";
const logoutPath = "/api/auth/logout";
const reportsPath = "/api/developer/reports";
const assignedReport = {
  id: "00000000-0000-4000-8000-000000000101",
  title: "Assigned export failure",
  status: "ASSIGNED",
};
const csrfToken = "session-bound-csrf-token";

let fetchMock: ReturnType<typeof vi.fn<typeof fetch>>;
let authenticated: boolean;
let listResponse: Response;

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function csrfResponse(): Response {
  return jsonResponse({ token: csrfToken, headerName: "X-CSRF-TOKEN" });
}

function responseFor(path: string, init?: RequestInit): Response {
  if (path === currentUserPath) {
    return authenticated
      ? jsonResponse({
          id: "00000000-0000-4000-8000-000000000001",
          displayName: "Developer One",
          role: "DEVELOPER",
          active: true,
        })
      : jsonResponse({ code: "UNAUTHENTICATED", message: "Sign in", requestId: "r1" }, 401);
  }
  if (path === csrfPath) return csrfResponse();
  if (path === loginPath && init?.method === "POST") {
    authenticated = true;
    return jsonResponse({
      id: "00000000-0000-4000-8000-000000000001",
      displayName: "Developer One",
      role: "DEVELOPER",
      active: true,
    });
  }
  if (path === logoutPath && init?.method === "POST") {
    authenticated = false;
    return new Response(null, { status: 204 });
  }
  if (path.startsWith(reportsPath) && init?.method !== "POST") return listResponse;
  throw new Error(`Unexpected request: ${init?.method ?? "GET"} ${path}`);
}

function headerValue(headers: HeadersInit | undefined, name: string): string | undefined {
  return new Headers(headers).get(name) ?? undefined;
}

beforeEach(() => {
  authenticated = false;
  listResponse = jsonResponse({ items: [assignedReport], page: 1, pageSize: 20, totalItems: 1 });
  fetchMock = vi.fn<typeof fetch>(async (input, init) => responseFor(String(input), init));
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("DeveloperApp authenticated report journey", () => {
  it("checks the session, signs in with the session CSRF token, and renders only the assigned list", async () => {
    render(<DeveloperApp />);
    await screen.findByLabelText("Username");
    expect(fetchMock).toHaveBeenCalledWith(currentUserPath, expect.anything());
    expect(fetchMock.mock.calls.some(([path]) => String(path) === reportsPath)).toBe(false);

    fireEvent.change(screen.getByLabelText("Username"), { target: { value: "developer.one" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "password-value" } });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

    await screen.findByText(assignedReport.title);
    const csrfCall = fetchMock.mock.calls.find(([path]) => String(path) === csrfPath);
    const loginCall = fetchMock.mock.calls.find(([path]) => String(path) === loginPath);
    expect(csrfCall?.[1]).toEqual(expect.objectContaining({ method: "GET", credentials: "same-origin", cache: "no-store" }));
    expect(loginCall?.[1]).toEqual(expect.objectContaining({
      method: "POST",
      credentials: "same-origin",
      cache: "no-store",
      headers: expect.objectContaining({ "X-CSRF-TOKEN": csrfToken }),
    }));
    expect(new URLSearchParams(String(loginCall?.[1]?.body))).toEqual(
      new URLSearchParams({ username: "developer.one", password: "password-value" }),
    );

    const reportCalls = fetchMock.mock.calls.filter(([path]) => String(path).startsWith(reportsPath));
    expect(reportCalls).toHaveLength(1);
    expect(String(reportCalls[0][0])).toBe(reportsPath);
    expect(reportCalls[0][1]).toEqual(expect.objectContaining({ credentials: "same-origin", cache: "no-store" }));
    const identityCalls = fetchMock.mock.calls
      .map(([path], index) => (String(path) === currentUserPath ? index : -1))
      .filter((index) => index >= 0);
    const reportsIndex = fetchMock.mock.calls.findIndex(([path]) => String(path) === reportsPath);
    expect(identityCalls).toHaveLength(2);
    expect(identityCalls[1]).toBeLessThan(reportsIndex);
    expect(screen.queryByText("Unassigned report owned by another developer")).toBeNull();
  });

  it("selects a listed report and renders its DeveloperChecklist", async () => {
    authenticated = true;
    render(<DeveloperApp />);

    fireEvent.click(await screen.findByRole("button", { name: new RegExp(assignedReport.title) }));

    expect(await screen.findByText(`Checklist for ${assignedReport.id}`)).toBeTruthy();
  });

  it("renders an empty-assignment state without selecting a report", async () => {
    authenticated = true;
    listResponse = jsonResponse({ items: [], page: 1, pageSize: 20, totalItems: 0 });
    render(<DeveloperApp />);

    expect(await screen.findByText(/no assigned reports/i)).toBeTruthy();
    expect(screen.queryByText(/checklist for/i)).toBeNull();
  });

  it("renders a recoverable list error without exposing response details", async () => {
    authenticated = true;
    listResponse = jsonResponse({ code: "INTERNAL_ERROR", message: "private database detail", requestId: "r2" }, 500);
    render(<DeveloperApp />);

    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(screen.queryByText(/private database detail|INTERNAL_ERROR/)).toBeNull();
    expect(screen.getByText(/retry/i)).toBeTruthy();
  });

  it("logs out with CSRF protection and clears the report view", async () => {
    authenticated = true;
    render(<DeveloperApp />);
    await screen.findByText(assignedReport.title);

    fireEvent.click(screen.getByRole("button", { name: "Sign out" }));

    await screen.findByLabelText("Username");
    await waitFor(() => expect(screen.queryByText(assignedReport.title)).toBeNull());
    const logoutCall = fetchMock.mock.calls.find(([path]) => String(path) === logoutPath);
    expect(logoutCall?.[1]).toEqual(expect.objectContaining({
      method: "POST",
      credentials: "same-origin",
      cache: "no-store",
    }));
    expect(headerValue(logoutCall?.[1]?.headers, "X-CSRF-TOKEN")).toBe(csrfToken);
  });
});
