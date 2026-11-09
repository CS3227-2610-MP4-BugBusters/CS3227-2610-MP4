import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DeveloperChecklist } from "./DeveloperChecklist";

const reportId = "unicode-report";
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

async function generateFor(step: string) {
  suggestionStep = step;
  render(<DeveloperChecklist reportId={reportId} />);
  const editor = await screen.findByLabelText(/investigation steps/i);
  fireEvent.change(editor, { target: { value: "Unsaved editor text" } });
  fireEvent.click(screen.getByRole("button", { name: "Generate suggestions" }));
  await waitFor(() => {
    expect((screen.getByRole("button", { name: "Generate suggestions" }) as HTMLButtonElement).disabled).toBe(false);
  });
  return editor as HTMLTextAreaElement;
}

describe("AI suggestion Unicode validation", () => {
  it("rejects a trailing unpaired high surrogate without previewing or changing the draft", async () => {
    const editor = await generateFor("Invalid step \ud800");

    expect.soft(screen.queryByRole("region", { name: "AI suggestions" })).toBeNull();
    expect.soft(screen.queryByRole("alert")).not.toBeNull();
    expect.soft(editor.value).toBe("Unsaved editor text");
    expect.soft(screen.queryByRole("button", { name: "Apply suggestions" })).toBeNull();
    expect.soft(fetchMock.mock.calls.some(([, init]) => init?.method === "PUT")).toBe(false);
  });

  it("continues to accept a valid surrogate pair and applies it only after explicit Apply", async () => {
    const validStep = "Inspect export output \ud83d\ude00";
    const editor = await generateFor(validStep);

    expect(await screen.findByText(validStep)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Apply suggestions" }));

    await waitFor(() => {
      expect(editor.value).toBe(`Unsaved editor text\n${validStep}`);
    });
    expect(fetchMock.mock.calls.some(([path, init]) =>
      String(path) === draftPath && (!init?.method || init.method === "GET"),
    )).toBe(true);
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === "PUT")).toBe(false);
  });

  it("rejects a lone low surrogate and leaves the editor unchanged", async () => {
    const editor = await generateFor("Invalid step \udc00");

    expect(screen.queryByRole("region", { name: "AI suggestions" })).toBeNull();
    expect(screen.getByRole("alert")).toBeTruthy();
    expect(editor.value).toBe("Unsaved editor text");
  });
});
