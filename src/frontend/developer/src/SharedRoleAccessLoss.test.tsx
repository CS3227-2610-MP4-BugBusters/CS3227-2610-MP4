import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { DeveloperApp } from "./DeveloperApp";

const report = {
  id: "00000000-0000-4000-8000-000000000211",
  title: "Protected export investigation",
  status: "IN_PROGRESS",
};
const draftPath = `/api/reports/${report.id}/checklist/draft`;
const sharePath = `/api/reports/${report.id}/checklist/share`;
const privateStep = "Private reproduction detail: customer export path";

let deniedAction: "Save" | "Share" | null;
let denialStatus: 401 | 403;
let denialCode: "FORBIDDEN" | "CSRF_INVALID";
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
    return json({ id: "dev-1", displayName: "Developer One", role: "DEVELOPER", active: true });
  }
  if (path === "/api/developer/reports") {
    return json({ items: [report], page: 1, pageSize: 20, totalItems: 1 });
  }
  if (path === "/api/developer/notifications") return json({ items: [] });
  if (path === "/api/auth/csrf") {
    return json({ token: "csrf-test-token", headerName: "X-CSRF-TOKEN" });
  }
  if (path === draftPath && (!init?.method || init.method === "GET")) {
    return json({ steps: [privateStep], draftRevision: 2, reportVersion: 9 });
  }
  if (path === draftPath && init?.method === "PUT") {
    return deniedAction === "Save"
      ? json({ code: denialCode, message: "Request denied" }, denialStatus)
      : json({ draftRevision: 3 });
  }
  if (path === sharePath && init?.method === "POST") {
    return deniedAction === "Share"
      ? json({ code: denialCode, message: "Request denied" }, denialStatus)
      : json({ reportVersion: 10, changed: true });
  }
  if (path === "/api/auth/logout") return new Response(null, { status: 204 });
  throw new Error(`Unexpected request: ${init?.method ?? "GET"} ${path}`);
}

async function openSelectedReport() {
  render(<DeveloperApp />);
  fireEvent.click(await screen.findByRole("button", { name: /Protected export investigation/ }));
  const editor = await screen.findByRole("textbox", { name: "Step 1" }) as HTMLInputElement;
  await waitFor(() => expect(editor.value).toBe(privateStep));
  return editor;
}

beforeEach(() => {
  deniedAction = null;
  denialStatus = 403;
  denialCode = "FORBIDDEN";
  fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => responseFor(input, init));
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("Developer workspace access loss during checklist actions", () => {
  it("keeps authorized report content after a successful Save", async () => {
    await openSelectedReport();
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByText("Private draft saved.")).toBeTruthy();
    expect(screen.getAllByText(report.title)).toHaveLength(2);
    expect((screen.getByRole("textbox", { name: "Step 1" }) as HTMLInputElement).value).toBe(privateStep);
  });

  it("preserves an unsaved draft after a CSRF_INVALID 403", async () => {
    deniedAction = "Save";
    denialStatus = 403;
    denialCode = "CSRF_INVALID";
    const editor = await openSelectedReport();
    const unsavedDraft = "Unsaved investigation detail to preserve";
    fireEvent.change(editor, { target: { value: unsavedDraft } });

    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toMatch(/unable|retry|reload|verify/i);
    expect(screen.getAllByText(report.title)).toHaveLength(2);
    expect((screen.getByRole("textbox", { name: "Step 1" }) as HTMLInputElement).value).toBe(unsavedDraft);
    const saveCall = fetchMock.mock.calls.find(
      ([path, init]) => String(path) === draftPath && init?.method === "PUT",
    );
    expect(saveCall?.[1]?.headers).toMatchObject({ "X-CSRF-TOKEN": "csrf-test-token" });
  });

  it.each([
    { action: "Save" as const, status: 401 as const },
    { action: "Share" as const, status: 403 as const },
  ])("clears report and private draft after $action receives $status", async ({ action, status }) => {
    deniedAction = action;
    denialStatus = status;
    await openSelectedReport();

    fireEvent.click(screen.getByRole("button", { name: action }));

    await waitFor(() => {
      expect(screen.queryAllByText(report.title)).toHaveLength(0);
      expect(screen.queryByRole("textbox", { name: "Step 1" })).toBeNull();
    });
    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(screen.getByRole("alert").textContent).toMatch(/access|session|sign in/i);
    expect(screen.queryByText(privateStep)).toBeNull();
  });
});
