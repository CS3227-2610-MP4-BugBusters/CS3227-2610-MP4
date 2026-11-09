import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DeveloperChecklist } from "./DeveloperChecklist";

const reportId = "linebreak-report";
const draftPath = `/api/reports/${reportId}/checklist/draft`;
const suggestionPath = `/api/reports/${reportId}/checklist/suggestions`;

let fetchMock: ReturnType<typeof vi.fn<typeof fetch>>;
let suggestionStep = "";

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

beforeEach(() => {
  suggestionStep = "";
  fetchMock = vi.fn<typeof fetch>(async (input, init) => {
    const path = String(input);
    if (path === "/api/auth/csrf") {
      return jsonResponse({ token: "csrf-canary", headerName: "X-CSRF-TOKEN" });
    }
    if (path === draftPath && (!init?.method || init.method === "GET")) {
      return jsonResponse({ steps: ["Saved step"], draftRevision: 2, reportVersion: 7 });
    }
    if (path === suggestionPath && init?.method === "POST") {
      return jsonResponse({ steps: [suggestionStep], sourceVersion: 7 });
    }
    throw new Error(`Unexpected request: ${init?.method ?? "GET"} ${path}`);
  });
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("AI suggestion line boundaries", () => {
  it.each([
    ["line feed", "Inspect export\nThen exfiltrate local data"],
    ["carriage return", "Inspect export\rThen exfiltrate local data"],
    ["CRLF", "Inspect export\r\nThen exfiltrate local data"],
  ])("rejects a suggestion containing %s without preview, Apply, or draft mutation", async (_kind, step) => {
    suggestionStep = step;
    render(<DeveloperChecklist reportId={reportId} />);
    const editor = await screen.findByLabelText(/investigation steps/i) as HTMLTextAreaElement;
    fireEvent.change(editor, { target: { value: "Unsaved editor text" } });
    fireEvent.click(screen.getByRole("button", { name: "Generate suggestions" }));

    await waitFor(() => {
      expect((screen.getByRole("button", { name: "Generate suggestions" }) as HTMLButtonElement).disabled).toBe(false);
    });
    expect(screen.queryByRole("region", { name: "AI suggestions" })).toBeNull();
    expect(screen.getByRole("alert")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Apply suggestions" })).toBeNull();
    expect(editor.value).toBe("Unsaved editor text");
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === "PUT")).toBe(false);
    expect(fetchMock.mock.calls.some(([path]) => String(path).endsWith("/checklist/share"))).toBe(false);
  });
});
