import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DeveloperChecklist } from "./DeveloperChecklist";

const csrfPath = "/api/auth/csrf";
const draftPath = (id: string) => `/api/reports/${id}/checklist/draft`;

let fetchMock: ReturnType<typeof vi.fn<typeof fetch>>;

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function draft(steps: string[], draftRevision: number, reportVersion: number) {
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

beforeEach(() => {
  fetchMock = vi.fn<typeof fetch>();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("DeveloperChecklist report isolation", () => {
  it("clears the previous report and disables mutations when the next report fails to load", async () => {
    fetchMock.mockImplementation(async (input) => {
      const path = String(input);
      if (path === draftPath("report-a")) {
        return draft(["Sensitive report A investigation"], 3, 10);
      }
      if (path === draftPath("report-b")) return jsonResponse({}, 403);
      throw new Error(`Unexpected request: ${path}`);
    });

    const view = render(<DeveloperChecklist reportId="report-a" />);
    expect(
      (await screen.findByLabelText(/investigation steps/i) as HTMLTextAreaElement)
        .value,
    ).toBe("Sensitive report A investigation");

    view.rerender(<DeveloperChecklist reportId="report-b" />);
    expect(await screen.findByRole("alert")).toBeTruthy();

    expect(screen.queryByRole("textbox")).toBeNull();
    expect(screen.queryByRole("button", { name: "Save" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Share" })).toBeNull();
  });

  it("does not expose Save or Share after the initial draft read fails", async () => {
    fetchMock.mockResolvedValue(jsonResponse({}, 403));

    render(<DeveloperChecklist reportId="report-a" />);

    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Save" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Share" })).toBeNull();
  });

  it("ignores an in-flight Save from report A after switching to report B", async () => {
    const saveA = deferred<Response>();
    fetchMock.mockImplementation(async (input, init) => {
      const path = String(input);
      if (path === draftPath("report-a") && (!init?.method || init.method === "GET")) {
        return draft(["A saved step"], 3, 10);
      }
      if (path === draftPath("report-b") && (!init?.method || init.method === "GET")) {
        return draft(["B saved step"], 20, 30);
      }
      if (path === csrfPath) return csrf();
      if (path === draftPath("report-a") && init?.method === "PUT") return saveA.promise;
      if (path === draftPath("report-b") && init?.method === "PUT") {
        return jsonResponse({ draftRevision: 21 });
      }
      throw new Error(`Unexpected request: ${init?.method ?? "GET"} ${path}`);
    });

    const view = render(<DeveloperChecklist reportId="report-a" />);
    const editorA = await screen.findByLabelText(/investigation steps/i);
    fireEvent.change(editorA, { target: { value: "A edited step" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => {
      expect(
        fetchMock.mock.calls.some(
          ([path, init]) => path === draftPath("report-a") && init?.method === "PUT",
        ),
      ).toBe(true);
    });

    view.rerender(<DeveloperChecklist reportId="report-b" />);
    const editorB = await screen.findByLabelText(/investigation steps/i);
    await waitFor(() => expect((editorB as HTMLTextAreaElement).value).toBe("B saved step"));

    await act(async () => {
      saveA.resolve(jsonResponse({ draftRevision: 4 }));
      await saveA.promise;
    });
    expect.soft(screen.queryByText("Private draft saved.")).toBeNull();
    expect.soft((editorB as HTMLTextAreaElement).value).toBe("B saved step");

    fireEvent.change(editorB, { target: { value: "B edited step" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => {
      expect(
        fetchMock.mock.calls.some(
          ([path, init]) => path === draftPath("report-b") && init?.method === "PUT",
        ),
      ).toBe(true);
    });
    const saveBCall = fetchMock.mock.calls.find(
      ([path, init]) => path === draftPath("report-b") && init?.method === "PUT",
    );
    expect.soft(JSON.parse(String(saveBCall?.[1]?.body))).toEqual({
      steps: ["B edited step"],
      expectedDraftRevision: 20,
    });
  });
});
