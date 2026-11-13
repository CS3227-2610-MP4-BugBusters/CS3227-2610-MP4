import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { DeveloperApp } from "./DeveloperApp";
import { DeveloperNotifications } from "./DeveloperNotifications";
import { inThreadRunner, MarkdownParserProvider } from "@mp4/markdown";

vi.mock("./DeveloperChecklist", () => ({
  DeveloperChecklist: ({ reportId, onReportVersionChange }: { reportId: string; onReportVersionChange?: (previous: number, current: number) => void }) => (
    <div>Checklist for {reportId}<button onClick={() => onReportVersionChange?.(4, 5)}>Mock share</button></div>
  ),
}));

const currentUserPath = "/api/auth/me";
const csrfPath = "/api/auth/csrf";
const logoutPath = "/api/auth/logout";
const reportsPath = "/api/developer/reports";
const assignedReport = {
  id: "00000000-0000-4000-8000-000000000101",
  title: "Assigned export failure",
  status: "ASSIGNED",
};
const csrfToken = "session-bound-csrf-token";

const navigate = vi.fn();

let fetchMock: ReturnType<typeof vi.fn<typeof fetch>>;
let authenticated: boolean;
let listResponse: Response;
let detailResponse: Response;
let notificationsResponse: Response;
let activeDeveloper = "developer.one";

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
          id: activeDeveloper === "developer.two"
            ? "00000000-0000-4000-8000-000000000002"
            : "00000000-0000-4000-8000-000000000001",
          displayName: activeDeveloper === "developer.two" ? "Developer Two" : "Developer One",
          role: "DEVELOPER",
          active: true,
        })
      : jsonResponse({ code: "UNAUTHENTICATED", message: "Sign in", requestId: "r1" }, 401);
  }
  if (path === csrfPath) return csrfResponse();
  if (path === logoutPath && init?.method === "POST") {
    authenticated = false;
    return new Response(null, { status: 204 });
  }
  if (path === "/api/developer/notifications" && init?.method !== "POST") return notificationsResponse.clone();
  if (/^\/api\/developer\/notifications\/[^/]+\/read$/.test(path) && init?.method === "POST") {
    return new Response(null, { status: 204 });
  }
  if (path === `${reportsPath}/${assignedReport.id}`) return detailResponse.clone();
  if (path.startsWith(reportsPath) && init?.method !== "POST") {
    if (new URL(path, "http://localhost").searchParams.get("page") === "2") {
      return jsonResponse({ items: [assignedReport], page: 2, pageSize: 20, totalItems: 40 });
    }
    return listResponse.clone();
  }
  throw new Error(`Unexpected request: ${init?.method ?? "GET"} ${path}`);
}

function headerValue(headers: HeadersInit | undefined, name: string): string | undefined {
  return new Headers(headers).get(name) ?? undefined;
}

beforeEach(() => {
  navigate.mockClear();
  authenticated = false;
  activeDeveloper = "developer.one";
  listResponse = jsonResponse({ items: [assignedReport], page: 1, pageSize: 20, totalItems: 1 });
  detailResponse = jsonResponse({ ...assignedReport, description: "Export fails after submission." });
  notificationsResponse = jsonResponse({ items: [] });
  fetchMock = vi.fn<typeof fetch>(async (input, init) => responseFor(String(input), init));
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("DeveloperApp authenticated report journey", () => {
  it("shows generic notification labels and only links reports still assigned to the Developer", async () => {
    authenticated = true;
    notificationsResponse = jsonResponse({ items: [
      { id: "00000000-0000-4000-8000-000000000201", type: "ASSIGNED", createdAt: "2026-11-10T08:00:00Z", read: false, reportId: assignedReport.id },
      { id: "00000000-0000-4000-8000-000000000202", type: "UNASSIGNED", createdAt: "2026-11-10T08:01:00Z", read: false, reportId: null },
      { id: "00000000-0000-4000-8000-000000000203", type: "RESOLUTION_RETURNED", createdAt: "2026-11-10T08:02:00Z", read: true, reportId: assignedReport.id },
      { id: "00000000-0000-4000-8000-000000000204", type: "REOPENED", createdAt: "2026-11-10T08:03:00Z", read: false, reportId: null },
    ] });
    render(<DeveloperApp navigate={navigate} />);
    const region = await screen.findByRole("region", { name: "Notifications" });
    await within(region).findByText("New report assigned");
    expect(region.textContent).toMatch(/assigned/i);
    expect(region.textContent).toMatch(/assignment removed|unassigned/i);
    expect(region.textContent).toMatch(/resolution returned/i);
    expect(region.textContent).toMatch(/report reopened|reopened/i);
    const reportButtons = within(region).getAllByRole("button", { name: "Open report" });
    expect(reportButtons).toHaveLength(2);
    expect(region.textContent).not.toContain("00000000-0000-4000-8000-000000000202");
    expect(region.textContent).not.toContain("00000000-0000-4000-8000-000000000204");
    expect(region.textContent).not.toContain("Sensitive report");
  });

  it("shows notification loading, empty, and error states", async () => {
    authenticated = true;
    let resolveNotifications!: (response: Response) => void;
    const pending = new Promise<Response>((resolve) => { resolveNotifications = resolve; });
    fetchMock.mockImplementation(async (input, init) => {
      if (String(input) === "/api/developer/notifications") return pending;
      return responseFor(String(input), init);
    });
    const { unmount } = render(<DeveloperApp navigate={navigate} />);
    expect(await screen.findByRole("status", { name: /loading notifications/i })).toBeTruthy();
    resolveNotifications(jsonResponse({ items: [] }));
    expect(await screen.findByText(/no notifications/i)).toBeTruthy();
    unmount();

    fetchMock.mockImplementation(async (input, init) => {
      if (String(input) === "/api/developer/notifications") return jsonResponse({ code: "STORAGE_UNAVAILABLE" }, 503);
      return responseFor(String(input), init);
    });
    render(<DeveloperApp navigate={navigate} />);
    const errorRegion = await screen.findByRole("region", { name: "Notifications" });
    expect(await within(errorRegion).findByRole("alert", { name: "Notification error" })).toBeTruthy();
  });

  it("posts an owned notification read acknowledgement with CSRF", async () => {
    authenticated = true;
    const notificationId = "00000000-0000-4000-8000-000000000205";
    notificationsResponse = jsonResponse({ items: [
      { id: notificationId, type: "ASSIGNED", createdAt: "2026-11-10T08:00:00Z", read: false, reportId: assignedReport.id },
    ] });
    render(<DeveloperApp navigate={navigate} />);
    const region = await screen.findByRole("region", { name: "Notifications" });
    fireEvent.click(await within(region).findByRole("button", { name: /mark read/i }));
    await waitFor(() => expect(fetchMock.mock.calls.some(([path, init]) =>
      String(path) === `/api/developer/notifications/${notificationId}/read` && init?.method === "POST"
      && headerValue(init.headers, "X-CSRF-TOKEN") === csrfToken)).toBe(true));
  });

  it("does not continue a notification read after the dashboard unmounts", async () => {
    authenticated = true;
    const notificationId = "00000000-0000-4000-8000-000000000206";
    notificationsResponse = jsonResponse({ items: [
      { id: notificationId, type: "ASSIGNED", createdAt: "2026-11-10T08:00:00Z", read: false, reportId: assignedReport.id },
    ] });
    let resolveCsrf!: (response: Response) => void;
    const pendingCsrf = new Promise<Response>((resolve) => { resolveCsrf = resolve; });
    fetchMock.mockImplementation(async (input, init) => {
      if (String(input) === csrfPath) return pendingCsrf;
      return responseFor(String(input), init);
    });
    const { unmount } = render(<DeveloperApp navigate={navigate} />);
    const region = await screen.findByRole("region", { name: "Notifications" });
    fireEvent.click(await within(region).findByRole("button", { name: /mark read/i }));
    await waitFor(() => expect(fetchMock.mock.calls.some(([path]) => String(path) === csrfPath)).toBe(true));
    const sessionChecksBeforeUnmount = fetchMock.mock.calls.filter(([path]) => String(path) === currentUserPath).length;
    unmount();
    await act(async () => resolveCsrf(jsonResponse({ code: "UNAUTHENTICATED" }, 401)));
    expect(fetchMock.mock.calls.some(([path]) => String(path).endsWith(`/notifications/${notificationId}/read`))).toBe(false);
    expect(fetchMock.mock.calls.filter(([path]) => String(path) === currentUserPath)).toHaveLength(sessionChecksBeforeUnmount);
  });

  it("does not handle late notification CSRF responses after unmount", async () => {
    const notificationId = "00000000-0000-4000-8000-000000000207";
    notificationsResponse = jsonResponse({ items: [
      { id: notificationId, type: "ASSIGNED", createdAt: "2026-11-10T08:00:00Z", read: false, reportId: assignedReport.id },
    ] });
    const expired = vi.fn();
    let resolveCsrf!: (response: Response) => void;
    const pendingCsrf = new Promise<Response>((resolve) => { resolveCsrf = resolve; });
    fetchMock.mockImplementation(async (input, init) => {
      const path = String(input);
      if (path === "/api/developer/notifications") return notificationsResponse.clone();
      if (path === csrfPath) return pendingCsrf;
      if (path === `/api/developer/notifications/${notificationId}/read` && init?.method === "POST") return new Response(null, { status: 204 });
      throw new Error(`Unexpected request: ${init?.method ?? "GET"} ${path}`);
    });
    const { unmount } = render(<DeveloperNotifications onSessionExpired={expired} onOpenReport={vi.fn()} />);
    fireEvent.click(await screen.findByRole("button", { name: /mark read/i }));
    unmount();
    await act(async () => resolveCsrf(jsonResponse({ code: "UNAUTHENTICATED" }, 401)));
    expect(expired).not.toHaveBeenCalled();
    expect(fetchMock.mock.calls.some(([path]) => String(path) === `/api/developer/notifications/${notificationId}/read`)).toBe(false);

    let resolveSuccessfulCsrf!: (response: Response) => void;
    const pendingSuccessfulCsrf = new Promise<Response>((resolve) => { resolveSuccessfulCsrf = resolve; });
    fetchMock.mockImplementation(async (input, init) => {
      const path = String(input);
      if (path === "/api/developer/notifications") return notificationsResponse.clone();
      if (path === csrfPath) return pendingSuccessfulCsrf;
      if (path === `/api/developer/notifications/${notificationId}/read` && init?.method === "POST") return new Response(null, { status: 204 });
      throw new Error(`Unexpected request: ${init?.method ?? "GET"} ${path}`);
    });
    const second = render(<DeveloperNotifications onSessionExpired={expired} onOpenReport={vi.fn()} />);
    fireEvent.click(await screen.findByRole("button", { name: /mark read/i }));
    second.unmount();
    await act(async () => resolveSuccessfulCsrf(csrfResponse()));
    expect(expired).not.toHaveBeenCalled();
    expect(fetchMock.mock.calls.some(([path]) => String(path) === `/api/developer/notifications/${notificationId}/read`)).toBe(false);
  });

  it("refreshes status after checklist share", async () => {
    authenticated = true;
    let shared = false;
    fetchMock.mockImplementation(async (input, init) => {
      const path = String(input);
      if (path === `${reportsPath}/${assignedReport.id}`) return jsonResponse({ ...assignedReport, status: shared ? "IN_PROGRESS" : "RESOLUTION_PROPOSED", description: "Details", version: shared ? 5 : 4 });
      if (path === reportsPath) return jsonResponse({ items: [{ ...assignedReport, status: shared ? "IN_PROGRESS" : "RESOLUTION_PROPOSED" }], page: 1, pageSize: 20, totalItems: 1 });
      return responseFor(path, init);
    });
    render(<DeveloperApp navigate={navigate} />);
    fireEvent.click(await screen.findByRole("button", { name: /Assigned export failure/ }));
    await screen.findByText("Status: Resolution Proposed");
    shared = true;
    fireEvent.click(screen.getByRole("button", { name: "Mock share" }));
    await screen.findByText("Status: In Progress");
    expect(screen.getByLabelText("Resolution")).toBeTruthy();
  });

  it("refreshes the filtered list and total after resolution submission", async () => {
    authenticated = true;
    let submitted = false;
    fetchMock.mockImplementation(async (input, init) => {
      const path = String(input);
      if (path === `${reportsPath}/${assignedReport.id}/resolution`) { submitted = true; return jsonResponse({}); }
      if (path === `${reportsPath}/${assignedReport.id}`) return jsonResponse({ ...assignedReport, status: submitted ? "RESOLUTION_PROPOSED" : "IN_PROGRESS", description: "Details", version: submitted ? 5 : 4 });
      if (path.startsWith(reportsPath) && !path.includes(assignedReport.id)) return jsonResponse({ items: submitted ? [] : [{ ...assignedReport, status: "IN_PROGRESS" }], page: 1, pageSize: 20, totalItems: submitted ? 0 : 1 });
      return responseFor(path, init);
    });
    render(<DeveloperApp navigate={navigate} />);
    await screen.findByRole("button", { name: /Assigned export failure/ });
    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "IN_PROGRESS" } });
    fireEvent.click(await screen.findByRole("button", { name: /Assigned export failure/ }));
    fireEvent.change(await screen.findByLabelText("Resolution"), { target: { value: "Resolved" } });
    fireEvent.click(screen.getByRole("button", { name: "Submit resolution" }));
    await screen.findByText("No matching reports.");
    expect(screen.getByText(`Checklist for ${assignedReport.id}`)).toBeTruthy();
    expect(screen.queryByText(/Page 1 of/)).toBeNull();
  });

  it("keeps resolution text after a version conflict and retries with the refreshed version", async () => {
    authenticated = true;
    let version = 4;
    let attempts = 0;
    fetchMock.mockImplementation(async (input, init) => {
      const path = String(input);
      if (path === `${reportsPath}/${assignedReport.id}/resolution`) {
        attempts++;
        expect(JSON.parse(String(init?.body))).toEqual({ expectedVersion: attempts === 1 ? 4 : 5, resolution: "Restart the service" });
        version = attempts === 1 ? 5 : 6;
        return jsonResponse({}, attempts === 1 ? 409 : 200);
      }
      if (path === `${reportsPath}/${assignedReport.id}`) return jsonResponse({ ...assignedReport, status: version === 6 ? "RESOLUTION_PROPOSED" : "IN_PROGRESS", description: "Details", version });
      return responseFor(path, init);
    });
    render(<DeveloperApp navigate={navigate} />);
    fireEvent.click(await screen.findByRole("button", { name: /Assigned export failure/ }));
    fireEvent.change(await screen.findByLabelText("Resolution"), { target: { value: "Restart the service" } });
    fireEvent.click(screen.getByRole("button", { name: "Submit resolution" }));
    await screen.findByText("The report changed. Your resolution text is still here.");
    expect((screen.getByLabelText("Resolution") as HTMLTextAreaElement).value).toBe("Restart the service");
    fireEvent.click(screen.getByRole("button", { name: "Submit resolution" }));
    await screen.findByText("Status: Resolution Proposed");
    expect(screen.queryByLabelText("Resolution")).toBeNull();
  });

  it("starts an assigned report with its version and refreshes its details and list", async () => {
    authenticated = true;
    let started = false;
    fetchMock.mockImplementation(async (input, init) => {
      const path = String(input);
      if (path === `${reportsPath}/${assignedReport.id}/start`) {
        expect(init?.body).toBe(JSON.stringify({ expectedVersion: 4 }));
        expect(headerValue(init?.headers, "X-CSRF-TOKEN")).toBe(csrfToken);
        started = true;
        return jsonResponse({ status: "IN_PROGRESS", version: 5 });
      }
      if (path === `${reportsPath}/${assignedReport.id}`) {
        return jsonResponse({ ...assignedReport, status: started ? "IN_PROGRESS" : "ASSIGNED", description: "Details", version: started ? 5 : 4 });
      }
      if (path === reportsPath) return jsonResponse({ items: [{ ...assignedReport, status: started ? "IN_PROGRESS" : "ASSIGNED" }], page: 1, pageSize: 20, totalItems: 1 });
      return responseFor(path, init);
    });
    render(<DeveloperApp navigate={navigate} />);
    fireEvent.click(await screen.findByRole("button", { name: /Assigned export failure/ }));
    fireEvent.click(await screen.findByRole("button", { name: "Start investigation" }));
    await screen.findByText("Status: In Progress");
    expect(screen.queryByRole("button", { name: "Start investigation" })).toBeNull();
    expect(screen.getByText(`Checklist for ${assignedReport.id}`)).toBeTruthy();
  });

  it("returns anonymous visitors to the root login without loading reports", async () => {
    render(<DeveloperApp navigate={navigate} />);
    await waitFor(() => expect(navigate).toHaveBeenCalledWith("/"));
    expect(screen.queryByLabelText("Username")).toBeNull();
    expect(screen.queryByLabelText("Password")).toBeNull();
    expect(fetchMock).toHaveBeenCalledWith(currentUserPath, expect.anything());
    expect(fetchMock.mock.calls.some(([path]) => String(path).startsWith(reportsPath))).toBe(false);
  });

  it("verifies the existing session before requesting the assigned list", async () => {
    authenticated = true;
    render(<DeveloperApp navigate={navigate} />);
    await screen.findByText(assignedReport.title);
    const identityIndex = fetchMock.mock.calls.findIndex(([path]) => String(path) === currentUserPath);
    const reportIndex = fetchMock.mock.calls.findIndex(([path]) => String(path) === reportsPath);
    expect(identityIndex).toBeLessThan(reportIndex);
    expect(navigate).not.toHaveBeenCalled();
  });

  it("loads report details for the selected report", async () => {
    authenticated = true;
    render(<DeveloperApp navigate={navigate} />);
    fireEvent.click(await screen.findByRole("button", { name: /Assigned export failure/ }));
    expect(await screen.findByText("Export fails after submission.")).toBeTruthy();
    expect(screen.getByText("Status: Assigned")).toBeTruthy();
    expect(fetchMock).toHaveBeenCalledWith(`${reportsPath}/${assignedReport.id}`, expect.objectContaining({
      credentials: "same-origin", cache: "no-store",
    }));
  });

  it("renders the Reporter description with shared Markdown and keeps the title plain text", async () => {
    authenticated = true;
    const report = { ...assignedReport, title: "**bold**" };
    listResponse = jsonResponse({ items: [report], page: 1, pageSize: 20, totalItems: 1 });
    detailResponse = jsonResponse({
      ...report,
      description: "- first item\n- second item\n\n| Name | Value |\n| --- | --- |\n| row | 1 |\n\n<b>x</b>\n\n![remote](https://attacker.example/image.png)",
    });

    render(
      <MarkdownParserProvider runner={inThreadRunner}>
        <DeveloperApp navigate={navigate} />
      </MarkdownParserProvider>,
    );
    fireEvent.click(await screen.findByRole("button", { name: /\*\*bold\*\*/ }));

    const detail = await screen.findByRole("region", { name: "Selected report" });
    expect(within(detail).getByRole("heading", { level: 2, name: "**bold**" })).toBeTruthy();
    expect(within(detail).getByText("first item")).toBeTruthy();
    expect(within(detail).getByRole("table")).toBeTruthy();
    expect(within(detail).getByText("<b>x</b>")).toBeTruthy();
    expect(detail.querySelector("b, img, a")).toBeNull();
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes("attacker.example"))).toBe(false);
  });

  it("shows the latest Maintainer return reason as plain text", async () => {
    authenticated = true;
    const reason = "Inspect <script>alert(1)</script> trace";
    detailResponse = jsonResponse({
      ...assignedReport,
      status: "IN_PROGRESS",
      description: "Export fails after submission.",
      returnReason: reason,
    });
    render(<DeveloperApp navigate={navigate} />);
    fireEvent.click(await screen.findByRole("button", { name: /Assigned export failure/ }));
    expect(await screen.findByText(reason)).toBeTruthy();
    expect(screen.getByText(/maintainer returned this resolution/i)).toBeTruthy();
  });

  it("selects a listed report and renders its DeveloperChecklist", async () => {
    authenticated = true;
    render(<DeveloperApp navigate={navigate} />);

    fireEvent.click(await screen.findByRole("button", { name: new RegExp(assignedReport.title) }));

    expect(await screen.findByText(`Checklist for ${assignedReport.id}`)).toBeTruthy();
  });

  it("renders an empty-assignment state without selecting a report", async () => {
    authenticated = true;
    listResponse = jsonResponse({ items: [], page: 1, pageSize: 20, totalItems: 0 });
    render(<DeveloperApp navigate={navigate} />);

    expect(await screen.findByText(/no assigned reports/i)).toBeTruthy();
    expect(screen.queryByText(/checklist for/i)).toBeNull();
  });

  it("renders a recoverable list error without exposing response details", async () => {
    authenticated = true;
    listResponse = jsonResponse({ code: "INTERNAL_ERROR", message: "private database detail", requestId: "r2" }, 500);
    render(<DeveloperApp navigate={navigate} />);

    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(screen.queryByText(/private database detail|INTERNAL_ERROR/)).toBeNull();
    expect(screen.getByText(/retry/i)).toBeTruthy();
  });

  it("logs out with CSRF protection and clears the report view", async () => {
    authenticated = true;
    render(<DeveloperApp navigate={navigate} />);
    await screen.findByText(assignedReport.title);

    fireEvent.click(screen.getByRole("button", { name: "Sign out" }));

    await waitFor(() => expect(navigate).toHaveBeenCalledWith("/"));
    await waitFor(() => expect(screen.queryByText(assignedReport.title)).toBeNull());
    const logoutCall = fetchMock.mock.calls.find(([path]) => String(path) === logoutPath);
    expect(logoutCall?.[1]).toEqual(expect.objectContaining({
      method: "POST",
      credentials: "same-origin",
      cache: "no-store",
    }));
    expect(headerValue(logoutCall?.[1]?.headers, "X-CSRF-TOKEN")).toBe(csrfToken);
  });

  it("keeps logout failures visible without redirecting", async () => {
    authenticated = true;
    fetchMock.mockImplementation(async (input, init) => String(input) === logoutPath
      ? jsonResponse({ code: "STORAGE_UNAVAILABLE" }, 503)
      : responseFor(String(input), init));
    render(<DeveloperApp navigate={navigate} />);
    await screen.findByText(assignedReport.title);
    fireEvent.click(screen.getByRole("button", { name: "Sign out" }));
    expect((await screen.findByRole("alert")).textContent).toContain("Sign out failed");
    expect(navigate).not.toHaveBeenCalled();
    expect(screen.getByText(assignedReport.title)).toBeTruthy();
  });

  it("clears one Developer's dashboard query and pagination before another signs in", async () => {
    listResponse = jsonResponse({ items: [assignedReport], page: 1, pageSize: 20, totalItems: 40 });
    authenticated = true;
    const first = render(<DeveloperApp navigate={navigate} />);
    await screen.findByText(assignedReport.title);

    const privateQuery = "private-report-title-or-uuid";
    fireEvent.change(screen.getByLabelText("Search"), { target: { value: privateQuery } });
    fireEvent.click(screen.getByRole("button", { name: "Search" }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining(`search=${privateQuery}`),
      expect.anything(),
    ));
    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "IN_PROGRESS" } });
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining("status=IN_PROGRESS"),
      expect.anything(),
    ));
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining("page=2"),
      expect.anything(),
    ));

    fireEvent.click(screen.getByRole("button", { name: "Sign out" }));
    await waitFor(() => expect(navigate).toHaveBeenCalledWith("/"));
    expect(screen.queryByText(assignedReport.title)).toBeNull();
    first.unmount();
    activeDeveloper = "developer.two";
    authenticated = true;
    const callsBeforeReturn = fetchMock.mock.calls.length;
    render(<DeveloperApp navigate={navigate} />);

    await screen.findByText(assignedReport.title);
    expect.soft((screen.getByLabelText("Search") as HTMLInputElement).value).toBe("");
    expect.soft((screen.getByLabelText("Status") as HTMLSelectElement).value).toBe("");
    expect.soft(screen.getByRole("navigation", { name: "Report pages" }).textContent)
      .toContain("Page 1 of 2");

    const secondDeveloperReportCalls = fetchMock.mock.calls
      .slice(callsBeforeReturn)
      .filter(([path]) => String(path).startsWith(reportsPath));
    expect.soft(secondDeveloperReportCalls).toHaveLength(1);
    expect.soft(String(secondDeveloperReportCalls[0]?.[0])).toBe(reportsPath);
    expect.soft(String(secondDeveloperReportCalls[0]?.[0])).not.toContain(privateQuery);
  });
});
