import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DeveloperChecklist } from "./DeveloperChecklist";

const csrfPath = "/api/auth/csrf";
const draftPath = (id: string) => `/api/reports/${id}/checklist/draft`;
const suggestionPath = (id: string) => `/api/reports/${id}/checklist/suggestions`;

let fetchMock: ReturnType<typeof vi.fn<typeof fetch>>;

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function draft(steps: string[] = [], draftRevision = 2, reportVersion = 7) {
  return jsonResponse({ steps, draftRevision, reportVersion });
}

function csrf() {
  return jsonResponse({ token: "csrf-canary", headerName: "X-CSRF-TOKEN" });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => (resolve = done));
  return { promise, resolve };
}

function defaultResponse(path: string, init?: RequestInit): Response {
  if (path === csrfPath) return csrf();
  if (/\/checklist\/draft$/.test(path) && (!init?.method || init.method === "GET")) {
    return draft();
  }
  if (/\/checklist\/draft$/.test(path) && init?.method === "PUT") {
    return jsonResponse({ draftRevision: 3 });
  }
  if (/\/checklist\/share$/.test(path) && init?.method === "POST") {
    return jsonResponse({ reportVersion: 8, changed: true });
  }
  if (/\/checklist\/suggestions$/.test(path) && init?.method === "POST") {
    return jsonResponse({ steps: ["Inspect the export path"], sourceVersion: 7, draftRevision: 2 });
  }
  throw new Error(`Unexpected request: ${init?.method ?? "GET"} ${path}`);
}

function requestInit(path: string, method: string) {
  return fetchMock.mock.calls.find(
    ([input, init]) => String(input) === path && init?.method === method,
  )?.[1];
}

beforeEach(() => {
  fetchMock = vi.fn<typeof fetch>(async (input, init) =>
    defaultResponse(String(input), init),
  );
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("Developer AI suggestion review flow", () => {
  it("requests suggestions with CSRF and an empty body, displaying a separate escaped preview without saving or sharing", async () => {
    const untrustedStep = "<img src=x onerror=alert(1)> Inspect export handling";
    fetchMock.mockImplementation(async (input, init) => {
      if (String(input) === suggestionPath("report-a") && init?.method === "POST") {
        return jsonResponse({ steps: [untrustedStep], sourceVersion: 7, draftRevision: 2 });
      }
      return defaultResponse(String(input), init);
    });

    render(<DeveloperChecklist reportId="report-a" />);
    const editor = await screen.findByRole("textbox", { name: "Step 1" });
    fireEvent.change(editor, { target: { value: "Keep my unsaved note" } });
    fireEvent.click(screen.getByRole("button", { name: /generate suggestions/i }));

    const preview = await screen.findByRole("region", { name: /suggestions/i });
    expect(within(preview).getByText(untrustedStep)).toBeTruthy();
    expect(screen.getByText(/last saved checklist/i)).toBeTruthy();
    expect(preview.querySelector("img")).toBeNull();
    expect((editor as HTMLInputElement).value).toBe("Keep my unsaved note");

    const post = requestInit(suggestionPath("report-a"), "POST");
    expect(post?.body).toBe("{}");
    expect(post?.credentials).toBe("same-origin");
    expect(post?.cache).toBe("no-store");
    expect(post?.headers).toMatchObject({
      "Content-Type": "application/json",
      "X-CSRF-TOKEN": "csrf-canary",
    });
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === "PUT")).toBe(false);
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === "POST" && init !== post)).toBe(false);
    expect(fetchMock.mock.calls.some(([path]) => /\/checklist\/share$/.test(String(path)))).toBe(false);
    expect(screen.getByRole("button", { name: "Save" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Share" })).toBeTruthy();
  });

  it("applies only after fresh matching report and saved-checklist revision checks and does not persist the draft", async () => {
    render(<DeveloperChecklist reportId="report-a" />);
    const editor = await screen.findByRole("textbox", { name: "Step 1" });
    fireEvent.click(screen.getByRole("button", { name: /generate suggestions/i }));
    const preview = await screen.findByRole("region", { name: /suggestions/i });
    fireEvent.click(within(preview).getByRole("checkbox", { name: "Inspect the export path" }));
    fireEvent.click(within(preview).getByRole("button", { name: /add selected/i }));

    await waitFor(() => {
      expect((screen.getByRole("textbox", { name: "Step 1" }) as HTMLInputElement).value)
        .toBe("Inspect the export path");
    });
    expect(fetchMock.mock.calls.some(([path, init]) =>
      String(path) === draftPath("report-a") && (!init?.method || init.method === "GET"),
    )).toBe(true);
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === "PUT")).toBe(false);
    expect(fetchMock.mock.calls.some(([path]) => /\/checklist\/share$/.test(String(path)))).toBe(false);
  });

  it("discards a stale preview on Apply and keeps the unsaved draft text", async () => {
    let versionReads = 0;
    fetchMock.mockImplementation(async (input, init) => {
      const path = String(input);
      if (path === draftPath("report-a") && (!init?.method || init.method === "GET")) {
        versionReads += 1;
        return draft(["Existing step"], 2, versionReads === 1 ? 7 : 8);
      }
      return defaultResponse(path, init);
    });

    render(<DeveloperChecklist reportId="report-a" />);
    const editor = await screen.findByRole("textbox", { name: "Step 1" });
    fireEvent.change(editor, { target: { value: "Unsaved user wording" } });
    fireEvent.click(screen.getByRole("button", { name: /generate suggestions/i }));
    const preview = await screen.findByRole("region", { name: /suggestions/i });
    fireEvent.click(within(preview).getByRole("checkbox", { name: "Inspect the export path" }));
    fireEvent.click(within(preview).getByRole("button", { name: /add selected/i }));

    expect(await screen.findByRole("alert")).toBeTruthy();
    expect((editor as HTMLInputElement).value).toBe("Unsaved user wording");
    expect(screen.queryByText("Inspect the export path")).toBeNull();
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === "PUT")).toBe(false);
    expect(fetchMock.mock.calls.some(([path]) => /\/checklist\/share$/.test(String(path)))).toBe(false);
  });

  it("rejects suggestions when the saved checklist revision changes during generation", async () => {
    let draftReads = 0;
    fetchMock.mockImplementation(async (input, init) => {
      const path = String(input);
      if (path === draftPath("report-a") && (!init?.method || init.method === "GET")) {
        draftReads += 1;
        return draft([draftReads === 1 ? "Saved before generation" : "Saved after generation"],
          draftReads === 1 ? 2 : 3, 7);
      }
      return defaultResponse(path, init);
    });

    render(<DeveloperChecklist reportId="report-a" />);
    const editor = await screen.findByRole("textbox", { name: "Step 1" });
    fireEvent.change(editor, { target: { value: "Unsaved wording stays local" } });
    fireEvent.click(screen.getByRole("button", { name: /generate suggestions/i }));
    const preview = await screen.findByRole("region", { name: /suggestions/i });
    fireEvent.click(within(preview).getByRole("checkbox", { name: "Inspect the export path" }));
    fireEvent.click(within(preview).getByRole("button", { name: /add selected/i }));

    expect(await screen.findByRole("alert")).toBeTruthy();
    expect((editor as HTMLInputElement).value).toBe("Unsaved wording stays local");
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === "PUT")).toBe(false);
  });

  it("keeps the current report intact when a pending suggestion for the previous report completes late", async () => {
    const resultA = deferred<Response>();
    fetchMock.mockImplementation(async (input, init) => {
      const path = String(input);
      if (path === suggestionPath("report-a") && init?.method === "POST") return resultA.promise;
      if (path === draftPath("report-a") && (!init?.method || init.method === "GET")) {
        return draft(["Report A saved step"], 4, 10);
      }
      if (path === draftPath("report-b") && (!init?.method || init.method === "GET")) {
        return draft(["Report B saved step"], 9, 20);
      }
      return defaultResponse(path, init);
    });

    const view = render(<DeveloperChecklist reportId="report-a" />);
    const editorA = await screen.findByRole("textbox", { name: "Step 1" });
    fireEvent.change(editorA, { target: { value: "Unsaved A text" } });
    fireEvent.click(screen.getByRole("button", { name: /generate suggestions/i }));
    await waitFor(() => expect(requestInit(suggestionPath("report-a"), "POST")).toBeTruthy());

    view.rerender(<DeveloperChecklist reportId="report-b" />);
    const editorB = await screen.findByRole("textbox", { name: "Step 1" });
    expect((editorB as HTMLInputElement).value).toBe("Report B saved step");
    await act(async () => {
      resultA.resolve(jsonResponse({ steps: ["Late report A suggestion"], sourceVersion: 10, draftRevision: 4 }));
      await resultA.promise;
    });

    expect((editorB as HTMLInputElement).value).toBe("Report B saved step");
    expect(screen.queryByText("Late report A suggestion")).toBeNull();
    expect(screen.queryByText("Unsaved A text")).toBeNull();
  });

  it("preserves unsaved editor text when suggestion generation fails", async () => {
    fetchMock.mockImplementation(async (input, init) => {
      if (String(input) === suggestionPath("report-a") && init?.method === "POST") {
        return jsonResponse({ code: "AI_UNAVAILABLE", message: "Suggestions unavailable", requestId: "req-1" }, 503);
      }
      return defaultResponse(String(input), init);
    });

    render(<DeveloperChecklist reportId="report-a" />);
    const editor = await screen.findByRole("textbox", { name: "Step 1" });
    fireEvent.change(editor, { target: { value: "Unsaved investigation note" } });
    fireEvent.click(screen.getByRole("button", { name: /generate suggestions/i }));

    expect(await screen.findByRole("alert")).toBeTruthy();
    expect((editor as HTMLInputElement).value).toBe("Unsaved investigation note");
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === "PUT")).toBe(false);
  });
});
