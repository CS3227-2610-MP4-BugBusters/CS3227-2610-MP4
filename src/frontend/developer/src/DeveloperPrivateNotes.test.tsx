import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { DeveloperPrivateNotes } from "./DeveloperPrivateNotes";

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe("Developer private investigation notes", () => {
  it("keeps unsent input after a stale save and renders it in a plain-text editor", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ text: "<script>private</script>", revision: 2 }))
      .mockResolvedValueOnce(json({ token: "csrf", headerName: "X-CSRF-TOKEN" }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: "STALE_VERSION" }), { status: 409 }))
      .mockResolvedValueOnce(json({ text: "another tab's saved notes", revision: 3 }))
      .mockResolvedValueOnce(json({ token: "csrf", headerName: "X-CSRF-TOKEN" }))
      .mockResolvedValueOnce(json({ text: "my newer private notes", revision: 4 }));
    vi.stubGlobal("fetch", fetchMock);

    render(<DeveloperPrivateNotes reportId="report-a" status="IN_PROGRESS" />);
    const editor = await screen.findByLabelText("Notes (visible only to the assigned Developer)");
    expect((editor as HTMLTextAreaElement).value).toBe("<script>private</script>");
    fireEvent.change(editor, { target: { value: "my newer private notes" } });
    fireEvent.click(screen.getByRole("button", { name: "Save notes" }));

    expect((await screen.findByRole("alert")).textContent).toContain("your text is still here");
    expect((editor as HTMLTextAreaElement).value).toBe("my newer private notes");
    expect(fetchMock).toHaveBeenLastCalledWith("/api/developer/reports/report-a/notes", expect.objectContaining({
      method: "PUT",
      body: JSON.stringify({ text: "my newer private notes", expectedRevision: 2 }),
    }));

    fireEvent.click(screen.getByRole("button", { name: "Refresh saved revision" }));
    await screen.findByRole("button", { name: "Save notes" });
    expect((editor as HTMLTextAreaElement).value).toBe("my newer private notes");
    fireEvent.click(screen.getByRole("button", { name: "Save notes" }));
    await screen.findByText("Private notes saved.");
    expect(fetchMock).toHaveBeenLastCalledWith("/api/developer/reports/report-a/notes", expect.objectContaining({
      method: "PUT",
      body: JSON.stringify({ text: "my newer private notes", expectedRevision: 3 }),
    }));
  });

  it("shows notes read-only after closure", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ text: "review note", revision: 1 })));
    render(<DeveloperPrivateNotes reportId="report-a" status="CLOSED" />);
    const editor = await screen.findByLabelText("Notes (visible only to the assigned Developer)");
    expect(editor).toHaveProperty("disabled", true);
    expect(screen.queryByRole("button", { name: "Save notes" })).toBeNull();
  });
});

function json(value: unknown): Response {
  return new Response(JSON.stringify(value), { status: 200, headers: { "Content-Type": "application/json" } });
}
