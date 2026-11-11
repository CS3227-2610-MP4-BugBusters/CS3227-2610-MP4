import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DeveloperChecklist } from "./DeveloperChecklist";

const reportId = "report-123";
const draftPath = `/api/reports/${reportId}/checklist/draft`;
const sharePath = `/api/reports/${reportId}/checklist/share`;
const suggestionsPath = `/api/reports/${reportId}/checklist/suggestions`;
const csrfPath = "/api/auth/csrf";

let fetchMock: ReturnType<typeof vi.fn<typeof fetch>>;

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function draft(steps: string[] = [], draftRevision = 0, reportVersion = 7) {
  return jsonResponse({ steps, draftRevision, reportVersion });
}

function csrf() {
  return jsonResponse({ token: "csrf-canary", headerName: "X-CSRF-TOKEN" });
}

function responseFor(path: string, init?: RequestInit): Response {
  if (path === draftPath && (!init?.method || init.method === "GET")) {
    return draft();
  }
  if (path === csrfPath) return csrf();
  if (path === draftPath && init?.method === "PUT") {
    return jsonResponse({ draftRevision: 1 });
  }
  if (path === sharePath && init?.method === "POST") {
    return jsonResponse({ reportVersion: 8, changed: true });
  }
  if (path === suggestionsPath && init?.method === "POST") {
    return jsonResponse({
      steps: ["Check the event listener", "Inspect the network request"],
      sourceVersion: 7,
      draftRevision: 0,
    });
  }
  throw new Error(`Unexpected request: ${init?.method ?? "GET"} ${path}`);
}

function requestBody(call: number): Record<string, unknown> {
  const init = fetchMock.mock.calls[call]?.[1];
  return JSON.parse(String(init?.body)) as Record<string, unknown>;
}

function stepField(index: number): HTMLInputElement {
  return screen.getByRole("textbox", { name: `Step ${index}` }) as HTMLInputElement;
}

beforeEach(() => {
  fetchMock = vi.fn<typeof fetch>(async (input, init) =>
    responseFor(String(input), init),
  );
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("DeveloperChecklist HTTP workflow", () => {
  it("edits checklist steps individually and adds/removes steps", async () => {
    render(<DeveloperChecklist reportId={reportId} />);
    const first = await screen.findByRole("textbox", { name: /step 1/i });
    fireEvent.change(first, { target: { value: "Reproduce the issue" } });
    fireEvent.click(screen.getByRole("button", { name: /add step/i }));
    const second = await screen.findByRole("textbox", { name: /step 2/i });
    fireEvent.change(second, { target: { value: "Inspect browser logs" } });

    fireEvent.click(screen.getByRole("button", { name: /remove step 1/i }));

    await waitFor(() => expect(screen.queryByRole("textbox", { name: /step 2/i })).toBeNull());
    expect(stepField(1).value).toBe("Inspect browser logs");
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => {
      const saveCall = fetchMock.mock.calls.findIndex(
        ([path, init]) => path === draftPath && init?.method === "PUT",
      );
      expect(requestBody(saveCall).steps).toEqual(["Inspect browser logs"]);
    });
  });

  it("allows selected AI suggestions to be added or replace the checklist", async () => {
    render(<DeveloperChecklist reportId={reportId} />);
    const first = await screen.findByRole("textbox", { name: /step 1/i });
    fireEvent.change(first, { target: { value: "Keep this existing step" } });
    fireEvent.click(screen.getByRole("button", { name: /generate suggestions/i }));

    const firstSuggestion = await screen.findByRole("checkbox", { name: /check the event listener/i });
    const secondSuggestion = screen.getByRole("checkbox", { name: /inspect the network request/i });
    fireEvent.click(firstSuggestion);
    fireEvent.click(screen.getByRole("button", { name: /add selected/i }));

    await waitFor(() => expect(stepField(2).value).toBe("Check the event listener"));
    expect(stepField(1).value).toBe("Keep this existing step");

    await waitFor(() => expect((screen.getByRole("button", { name: /generate suggestions/i }) as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(screen.getByRole("button", { name: /generate suggestions/i }));
    await waitFor(() => expect((screen.getByRole("checkbox", { name: /inspect the network request/i }) as HTMLInputElement).disabled).toBe(false));
    fireEvent.click(screen.getByRole("checkbox", { name: /inspect the network request/i }));
    fireEvent.click(screen.getByRole("button", { name: /replace checklist/i }));

    await waitFor(() => expect(screen.getAllByRole("textbox", { name: /step/i })).toHaveLength(1));
    expect(stepField(1).value).toBe("Inspect the network request");
  });

  it("preserves edits and shares with the new report version after starting", async () => {
    fetchMock.mockImplementation(async (input, init) => {
      if (String(input) === draftPath && (!init?.method || init.method === "GET")) return draft(["Saved step"], 3, 7);
      return responseFor(String(input), init);
    });
    const view = render(<DeveloperChecklist reportId={reportId} />);
    const editor = await screen.findByRole("textbox", { name: "Step 1" }) as HTMLInputElement;
    await waitFor(() => expect(editor.value).toBe("Saved step"));
    fireEvent.change(editor, { target: { value: "Unsaved edit" } });
    view.rerender(<DeveloperChecklist reportId={reportId} startedVersion={{ previous: 7, current: 8 }} />);
    expect(editor.value).toBe("Unsaved edit");
    fireEvent.click(screen.getByRole("button", { name: "Share" }));
    await waitFor(() => expect(fetchMock.mock.calls.some(([path]) => path === sharePath)).toBe(true));
    const call = fetchMock.mock.calls.findIndex(([path]) => path === sharePath);
    expect(requestBody(call)).toEqual({ expectedDraftRevision: 3, expectedReportVersion: 8 });
  });
  it("loads the saved draft and reloads it when the report is reopened", async () => {
    fetchMock.mockImplementation(async (input, init) => {
      if (String(input) === draftPath) {
        return draft(["Reproduce the export", "Capture a trace"], 4, 12);
      }
      return responseFor(String(input), init);
    });

    const firstView = render(<DeveloperChecklist reportId={reportId} />);
    await screen.findByRole("textbox", { name: "Step 2" });
    expect([stepField(1).value, stepField(2).value]).toEqual(["Reproduce the export", "Capture a trace"]);
    firstView.unmount();

    render(<DeveloperChecklist reportId={reportId} />);
    await screen.findByRole("textbox", { name: "Step 2" });
    expect(
      [stepField(1).value, stepField(2).value],
    ).toEqual(["Reproduce the export", "Capture a trace"]);
    expect(fetchMock.mock.calls.filter(([path]) => path === draftPath)).toHaveLength(2);
  });

  it("saves a private draft with CSRF and shares only the saved revisions", async () => {
    render(<DeveloperChecklist reportId={reportId} />);
    const editor = await screen.findByRole("textbox", { name: "Step 1" });
    fireEvent.change(editor, { target: { value: "Reproduce the bug" } });
    fireEvent.click(screen.getByRole("button", { name: /add step/i }));
    fireEvent.change(await screen.findByRole("textbox", { name: "Step 2" }), { target: { value: "Capture a trace" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        draftPath,
        expect.objectContaining({ method: "PUT" }),
      );
    });
    const saveCall = fetchMock.mock.calls.findIndex(
      ([path, init]) => path === draftPath && init?.method === "PUT",
    );
    expect(requestBody(saveCall)).toEqual({
      steps: ["Reproduce the bug", "Capture a trace"],
      expectedDraftRevision: 0,
    });
    expect(fetchMock.mock.calls[saveCall][1]?.headers).toMatchObject({
      "X-CSRF-TOKEN": "csrf-canary",
    });

    // Unsaved browser edits must not be included in Share: the server publishes
    // the saved draft selected by its revision numbers.
    fireEvent.change(stepField(1), { target: { value: "Unpersisted browser edit" } });
    fireEvent.click(screen.getByRole("button", { name: "Share" }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        sharePath,
        expect.objectContaining({ method: "POST" }),
      );
    });
    const shareCall = fetchMock.mock.calls.findIndex(
      ([path, init]) => path === sharePath && init?.method === "POST",
    );
    expect(requestBody(shareCall)).toEqual({
      expectedDraftRevision: 1,
      expectedReportVersion: 7,
    });
    expect(requestBody(shareCall)).not.toHaveProperty("steps");
    expect(fetchMock.mock.calls[shareCall][1]?.headers).toMatchObject({
      "X-CSRF-TOKEN": "csrf-canary",
    });
  });

  it("gives empty-share feedback without sending a Share request", async () => {
    render(<DeveloperChecklist reportId={reportId} />);
    await screen.findByRole("textbox", { name: "Step 1" });

    fireEvent.click(screen.getByRole("button", { name: "Share" }));

    expect((await screen.findByRole("status")).textContent).toMatch(
      /add at least one step before sharing/i,
    );
    expect(fetchMock.mock.calls.some(([path]) => path === sharePath)).toBe(false);
  });

  it("preserves typed steps and reports a stale Save response", async () => {
    fetchMock.mockImplementation(async (input, init) => {
      if (String(input) === draftPath && init?.method === "PUT") {
        return jsonResponse(
          { code: "STALE_VERSION", message: "Draft changed", requestId: "req-1" },
          409,
        );
      }
      return responseFor(String(input), init);
    });

    render(<DeveloperChecklist reportId={reportId} />);
    const editor = await screen.findByRole("textbox", { name: "Step 1" });
    fireEvent.change(editor, { target: { value: "Keep this unsaved text" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByRole("alert")).toBeTruthy();
    expect((editor as HTMLInputElement).value).toBe("Keep this unsaved text");
  });

  it("shows loading and then a recoverable load error", async () => {
    let rejectLoad!: (error: Error) => void;
    fetchMock.mockImplementation(
      () => new Promise<Response>((_resolve, reject) => (rejectLoad = reject)),
    );

    render(<DeveloperChecklist reportId={reportId} />);
    expect(screen.getByRole("status").textContent).toMatch(/loading/i);
    rejectLoad(new Error("offline"));
    expect(await screen.findByRole("alert")).toBeTruthy();
  });
});
