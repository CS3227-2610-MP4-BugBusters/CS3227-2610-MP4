import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { DeveloperApp } from "./DeveloperApp";

const reports = [
  {
    id: "00000000-0000-4000-8000-000000000401",
    title: "Export crash investigation",
    status: "IN_PROGRESS",
  },
  {
    id: "00000000-0000-4000-8000-000000000402",
    title: "Search index regression",
    status: "ASSIGNED",
  },
];
const draftPath = `/api/reports/${reports[0].id}/checklist/draft`;
const privateStep = "Private reproduction detail: customer export path";

let accountActive: boolean;
let fetchMock: ReturnType<typeof vi.fn<typeof fetch>>;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function responseFor(input: RequestInfo | URL, init?: RequestInit): Response {
  const path = String(input);
  if (path === "/api/auth/me") {
    if (!accountActive) {
      return json({ code: "FORBIDDEN", message: "Account is inactive" }, 403);
    }
    return json({ id: "dev-1", displayName: "Developer One", role: "DEVELOPER", active: true });
  }
  if (path === "/api/developer/reports") {
    return json({ items: reports, page: 1, pageSize: 20, totalItems: reports.length });
  }
  if (path === "/api/auth/csrf") {
    return json({ token: "csrf-test-token", headerName: "X-CSRF-TOKEN" });
  }
  if (path === draftPath && (!init?.method || init.method === "GET")) {
    return json({ steps: [privateStep], draftRevision: 2, reportVersion: 9 });
  }
  if (path === draftPath && init?.method === "PUT") {
    accountActive = false;
    return json(
      { code: "FORBIDDEN", message: "Account is inactive", requestId: "internal-request-id" },
      403,
    );
  }
  if (path === "/api/auth/logout") return new Response(null, { status: 204 });
  throw new Error(`Unexpected request: ${init?.method ?? "GET"} ${path}`);
}

beforeEach(() => {
  accountActive = true;
  fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => responseFor(input, init));
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("Developer workspace when the account becomes inactive during Save", () => {
  it("clears every protected report and identity after the draft PUT returns FORBIDDEN", async () => {
    render(<DeveloperApp />);

    expect(await screen.findByText(reports[0].title)).toBeTruthy();
    expect(screen.getByText(reports[1].title)).toBeTruthy();
    expect(screen.getByText("Developer One")).toBeTruthy();
    expect(screen.getByRole("navigation", { name: "Developer navigation" })).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: /Export crash investigation/ }));
    const editor = await screen.findByRole("textbox", { name: "Step 1" }) as HTMLInputElement;
    await waitFor(() => expect(editor.value).toBe(privateStep));
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith("/api/auth/csrf", expect.anything());
      expect(accountActive).toBe(false);
    });
    const saveCall = fetchMock.mock.calls.find(
      ([path, init]) => String(path) === draftPath && init?.method === "PUT",
    );
    expect(saveCall?.[1]?.headers).toMatchObject({ "X-CSRF-TOKEN": "csrf-test-token" });
    await waitFor(() => expect(screen.getByRole("alert")).toBeTruthy());

    expect.soft(screen.queryByText(reports[0].title)).toBeNull();
    expect.soft(screen.queryByText(reports[1].title)).toBeNull();
    expect.soft(screen.queryByRole("textbox", { name: "Step 1" })).toBeNull();
    expect.soft(screen.queryByDisplayValue(privateStep)).toBeNull();
    expect.soft(screen.queryByText("Developer One")).toBeNull();
    expect.soft(screen.queryByRole("navigation", { name: "Developer navigation" })).toBeNull();
    expect.soft(screen.queryByRole("button", { name: "Sign out" })).toBeNull();
    expect.soft(screen.getByRole("alert").textContent).toMatch(/sign in|session|active developer/i);
    expect.soft(screen.getByRole("alert").textContent).not.toMatch(/FORBIDDEN|internal-request-id|Account is inactive/i);
  });
});
