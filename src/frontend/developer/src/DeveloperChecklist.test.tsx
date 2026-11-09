import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DeveloperChecklist } from "./DeveloperChecklist";

const reportId = "report-123";
const draftPath = `/api/reports/${reportId}/checklist/draft`;
const sharePath = `/api/reports/${reportId}/checklist/share`;
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
  throw new Error(`Unexpected request: ${init?.method ?? "GET"} ${path}`);
}

function requestBody(call: number): Record<string, unknown> {
  const init = fetchMock.mock.calls[call]?.[1];
  return JSON.parse(String(init?.body)) as Record<string, unknown>;
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
  it("preserves edits and shares with the new report version after starting", async () => {
    fetchMock.mockImplementation(async (input, init) => {
      if (String(input) === draftPath && (!init?.method || init.method === "GET")) return draft(["Saved step"], 3, 7);
      return responseFor(String(input), init);
    });
    const view = render(<DeveloperChecklist reportId={reportId} />);
    const editor = await screen.findByLabelText(/investigation steps/i) as HTMLTextAreaElement;
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
    expect(
      (await screen.findByLabelText(/investigation steps/i) as HTMLTextAreaElement)
        .value,
    ).toBe("Reproduce the export\nCapture a trace");
    firstView.unmount();

    render(<DeveloperChecklist reportId={reportId} />);
    expect(
      (await screen.findByLabelText(/investigation steps/i) as HTMLTextAreaElement)
        .value,
    ).toBe("Reproduce the export\nCapture a trace");
    expect(fetchMock.mock.calls.filter(([path]) => path === draftPath)).toHaveLength(2);
  });

  it("saves a private draft with CSRF and shares only the saved revisions", async () => {
    render(<DeveloperChecklist reportId={reportId} />);
    const editor = await screen.findByLabelText(/investigation steps/i);
    fireEvent.change(editor, {
      target: { value: "Reproduce the bug\nCapture a trace" },
    });
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
    fireEvent.change(editor, { target: { value: "Unpersisted browser edit" } });
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
    await screen.findByLabelText(/investigation steps/i);

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
    const editor = await screen.findByLabelText(/investigation steps/i);
    fireEvent.change(editor, { target: { value: "Keep this unsaved text" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByRole("alert")).toBeTruthy();
    expect((editor as HTMLTextAreaElement).value).toBe("Keep this unsaved text");
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
