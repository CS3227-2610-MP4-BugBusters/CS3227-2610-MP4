import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { DeveloperSharedNotes } from "./DeveloperSharedNotes";

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const csrf = () => json({ token: "token", headerName: "X-CSRF-TOKEN" });

describe("Developer shared investigation notes", () => {
  it("saves a separate draft and shares its revision without publishing unsaved edits", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(json({ text: "", draftRevision: 0, reportVersion: 2 }))
      .mockResolvedValueOnce(csrf()).mockResolvedValueOnce(json({ draftRevision: 1 }))
      .mockResolvedValueOnce(csrf()).mockResolvedValueOnce(json({ reportVersion: 3, changed: true }));
    const onVersion = vi.fn(); vi.stubGlobal("fetch", fetchMock);
    render(<DeveloperSharedNotes reportId="report-a" status="IN_PROGRESS" onReportVersionChange={onVersion} />);
    const editor = await screen.findByLabelText("Shared investigation note draft");
    fireEvent.change(editor, { target: { value: "Saved for Maintainer" } });
    fireEvent.click(screen.getByRole("button", { name: "Save shared draft" }));
    await screen.findByText("Shared note draft saved privately.");
    fireEvent.change(editor, { target: { value: "Unsaved local edit" } });
    fireEvent.click(screen.getByRole("button", { name: "Share saved note" }));
    await screen.findByText("Saved note shared with Maintainers.");
    const share = fetchMock.mock.calls.find(([path]) => String(path).endsWith("/shared-notes/share"));
    expect(JSON.parse(String(share?.[1]?.body))).toEqual({ expectedDraftRevision: 1, expectedReportVersion: 2 });
    expect((editor as HTMLTextAreaElement).value).toBe("Unsaved local edit");
    expect(onVersion).toHaveBeenCalledWith(2, 3);
  });

  it("keeps unsent text after a conflict and refreshes revisions without replacing the editor", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(json({ text: "old", draftRevision: 1, reportVersion: 3 }))
      .mockResolvedValueOnce(csrf()).mockResolvedValueOnce(json({ code: "STALE_VERSION" }, 409))
      .mockResolvedValueOnce(json({ text: "other saved draft", draftRevision: 2, reportVersion: 4 }))
      .mockResolvedValueOnce(csrf()).mockResolvedValueOnce(json({ draftRevision: 3 }));
    vi.stubGlobal("fetch", fetchMock);
    render(<DeveloperSharedNotes reportId="report-a" status="IN_PROGRESS" />);
    const editor = await screen.findByLabelText("Shared investigation note draft");
    fireEvent.change(editor, { target: { value: "unsent text" } });
    fireEvent.click(screen.getByRole("button", { name: "Save shared draft" }));
    expect((await screen.findByRole("alert")).textContent).toContain("your text is still here");
    fireEvent.click(screen.getByRole("button", { name: "Refresh saved revision" }));
    await waitFor(() => expect(screen.queryByRole("button", { name: "Refresh saved revision" })).toBeNull());
    expect((editor as HTMLTextAreaElement).value).toBe("unsent text");
    expect((screen.getByRole("button", { name: "Share saved note" }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "Save shared draft" }));
    await screen.findByText("Shared note draft saved privately.");
    expect((screen.getByRole("button", { name: "Share saved note" }) as HTMLButtonElement).disabled).toBe(false);
    expect(fetchMock.mock.calls.at(-1)?.[1]?.body).toBe(JSON.stringify({ text: "unsent text", expectedDraftRevision: 2 }));
  });

  it("uses a newer known report version when another action changes the report", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(json({ text: "saved", draftRevision: 1, reportVersion: 2 }))
      .mockResolvedValueOnce(csrf()).mockResolvedValueOnce(json({ reportVersion: 4, changed: true }));
    vi.stubGlobal("fetch", fetchMock);
    const { rerender } = render(<DeveloperSharedNotes reportId="report-a" status="IN_PROGRESS" />);
    await screen.findByLabelText("Shared investigation note draft");
    rerender(<DeveloperSharedNotes reportId="report-a" status="IN_PROGRESS" startedVersion={{ previous: 2, current: 3 }} />);
    fireEvent.click(screen.getByRole("button", { name: "Share saved note" }));
    await screen.findByText("Saved note shared with Maintainers.");
    expect(JSON.parse(String(fetchMock.mock.calls.at(-1)?.[1]?.body))).toEqual({ expectedDraftRevision: 1, expectedReportVersion: 3 });
  });

  it("reports a revoked session without publishing the draft", async () => {
    const lost = vi.fn();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(json({ text: "private draft", draftRevision: 1, reportVersion: 2 }))
      .mockResolvedValueOnce(json({ code: "UNAUTHENTICATED" }, 401)));
    render(<DeveloperSharedNotes reportId="report-a" status="IN_PROGRESS" onAccessLost={lost} />);
    await screen.findByLabelText("Shared investigation note draft");
    fireEvent.click(screen.getByRole("button", { name: "Share saved note" }));
    await waitFor(() => expect(lost).toHaveBeenCalledWith("session", "report-a"));
  });

  it("isolates report drafts and renders closed reports read-only", async () => {
    vi.stubGlobal("fetch", vi.fn(async (path: string) => path.includes("report-a")
      ? json({ text: "draft A", draftRevision: 1, reportVersion: 1 })
      : json({ text: "draft B", draftRevision: 2, reportVersion: 4 })));
    const { rerender } = render(<DeveloperSharedNotes reportId="report-a" status="IN_PROGRESS" />);
    expect((await screen.findByLabelText("Shared investigation note draft") as HTMLTextAreaElement).value).toBe("draft A");
    rerender(<DeveloperSharedNotes reportId="report-b" status="CLOSED" />);
    await waitFor(() => expect((screen.getByLabelText("Shared investigation note draft") as HTMLTextAreaElement).value).toBe("draft B"));
    expect((screen.getByLabelText("Shared investigation note draft") as HTMLTextAreaElement).disabled).toBe(true);
    expect(screen.queryByRole("button", { name: "Share saved note" })).toBeNull();
  });
});
