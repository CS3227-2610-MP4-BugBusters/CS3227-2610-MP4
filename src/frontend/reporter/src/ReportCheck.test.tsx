import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NewReport } from "./NewReport";
import { ReportView } from "./ReportView";

// Check my report (openspec/changes/reporter-report-clarify, RC-01, RC-05c, RC-06, RC-08).

type Reply = { status: number; body?: unknown; headers?: Record<string, string> };

const USER = { id: "11111111-1111-4111-8111-111111111111", displayName: "Riley Reporter" };
const CHECK = "/api/reporter/report-clarify";
const TITLE = "Crash on save";
const DESCRIPTION = "it breaks after the second one\nusing chrome i think";

let calls: { url: string; init: RequestInit }[] = [];

function serve(check: () => Reply | Promise<Reply>, create: () => Reply = () => ({ status: 500 })) {
  calls = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init: RequestInit = {}) => {
      calls.push({ url, init });
      let reply: Reply;
      if (url === "/api/auth/csrf") reply = { status: 200, body: { token: "tok", headerName: "X-CSRF-TOKEN" } };
      else if (url === CHECK) reply = await check();
      else if (url === "/api/reporter/reports") reply = create();
      else reply = { status: 404 };
      return new Response(reply.body === undefined ? null : JSON.stringify(reply.body), {
        status: reply.status,
        headers: { "Content-Type": "application/json", ...(reply.headers ?? {}) },
      });
    }),
  );
}

function setup(onCreated = vi.fn()) {
  render(<NewReport user={USER} backHref="#/" onCreated={onCreated} />);
  const title = screen.getByLabelText("Title") as HTMLInputElement;
  const description = screen.getByLabelText("Description") as HTMLTextAreaElement;
  fireEvent.change(title, { target: { value: TITLE } });
  fireEvent.change(description, { target: { value: DESCRIPTION } });
  return { title, description, onCreated };
}

const checkCalls = () => calls.filter((c) => c.url === CHECK);

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("Check my report", () => {
  it("RC-01c sends nothing while typing or submitting", async () => {
    const created = { id: "22222222-2222-4222-8222-222222222222", title: TITLE, description: DESCRIPTION, status: "SUBMITTED", version: 0, createdAt: "2026-11-13T00:00:00Z", updatedAt: "2026-11-13T00:00:00Z" };
    serve(() => ({ status: 500 }), () => ({ status: 201, body: created }));
    const { onCreated } = setup();
    fireEvent.click(screen.getByRole("button", { name: "Submit report" }));
    await waitFor(() => expect(onCreated).toHaveBeenCalledWith(created.id));
    expect(checkCalls()).toHaveLength(0);
  });

  it("RC-01a sends one request with exactly title and description, with CSRF, and shows results", async () => {
    serve(() => ({
      status: 200,
      body: {
        unclear: [{ quote: "the second one", question: "The second what?" }],
        missing: [{ category: "Environment", question: "Which browser version?" }],
      },
    }));
    setup();
    fireEvent.click(screen.getByRole("button", { name: "Check my report" }));
    expect(await screen.findByText("The second what?")).toBeTruthy();
    expect(screen.getByText("the second one")).toBeTruthy();
    expect(screen.getByText("Environment")).toBeTruthy();
    expect(screen.getByRole("heading", { name: "Questions by topic" })).toBeTruthy();
    expect(screen.queryByText("Missing information")).toBeNull();
    expect(screen.getByText("Which browser version?")).toBeTruthy();
    expect(checkCalls()).toHaveLength(1);
    const [only] = checkCalls();
    expect(only!.init.method).toBe("POST");
    expect((only!.init.headers as Record<string, string>)["X-CSRF-TOKEN"]).toBe("tok");
    expect(JSON.parse(String(only!.init.body))).toEqual({ title: TITLE, description: DESCRIPTION });
    expect(calls.some((c) => c.url === "/api/reporter/reports")).toBe(false);
  });

  it("RC-05c says when there is nothing to ask", async () => {
    serve(() => ({ status: 200, body: { unclear: [], missing: [] } }));
    setup();
    fireEvent.click(screen.getByRole("button", { name: "Check my report" }));
    expect(await screen.findByText(/No questions found/)).toBeTruthy();
  });

  it("does not call the server for a blank title", async () => {
    serve(() => ({ status: 200, body: { unclear: [], missing: [] } }));
    setup();
    fireEvent.change(screen.getByLabelText("Title"), { target: { value: "" } });
    fireEvent.click(screen.getByRole("button", { name: "Check my report" }));
    expect((await screen.findByRole("alert")).textContent).toContain("Add a title.");
    expect(checkCalls()).toHaveLength(0);
  });

  it("RC-06a leaves the draft unchanged after showing and dismissing results", async () => {
    serve(() => ({ status: 200, body: { unclear: [{ quote: "chrome", question: "Which version?" }], missing: [] } }));
    const { title, description } = setup();
    fireEvent.click(screen.getByRole("button", { name: "Check my report" }));
    await screen.findByText("Which version?");
    expect(title.value).toBe(TITLE);
    expect(description.value).toBe(DESCRIPTION);
    fireEvent.click(screen.getByRole("button", { name: "Dismiss" }));
    expect(screen.queryByText("Which version?")).toBeNull();
    expect(title.value).toBe(TITLE);
    expect(description.value).toBe(DESCRIPTION);
  });

  it("RC-06b shows Markdown, HTML and URLs as literal text", async () => {
    const question = "See [docs](https://evil.example) <img src=x onerror=alert(1)> **bold**";
    serve(() => ({ status: 200, body: { unclear: [], missing: [{ category: "Summary", question }] } }));
    setup();
    fireEvent.click(screen.getByRole("button", { name: "Check my report" }));
    const node = await screen.findByText(question);
    const panel = node.closest("section")!;
    expect(panel.querySelector("a, img, strong, script")).toBeNull();
  });

  it("rejects a quote that is not in the draft", async () => {
    serve(() => ({ status: 200, body: { unclear: [{ quote: "invented words", question: "What?" }], missing: [] } }));
    setup();
    fireEvent.click(screen.getByRole("button", { name: "Check my report" }));
    expect((await screen.findByRole("alert")).textContent).toContain("Couldn't check your report");
    expect(screen.queryByText("What?")).toBeNull();
  });

  it("rejects unknown categories and extra keys", async () => {
    serve(() => ({ status: 200, body: { unclear: [], missing: [{ category: "Mood", question: "Why?" }] } }));
    setup();
    fireEvent.click(screen.getByRole("button", { name: "Check my report" }));
    expect((await screen.findByRole("alert")).textContent).toContain("Couldn't check your report");

    cleanup();
    serve(() => ({ status: 200, body: { unclear: [], missing: [], note: "x" } }));
    setup();
    fireEvent.click(screen.getByRole("button", { name: "Check my report" }));
    expect((await screen.findByRole("alert")).textContent).toContain("Couldn't check your report");
  });

  it("RC-08a shows the retry time when rate limited and keeps the draft", async () => {
    serve(() => ({ status: 429, body: { code: "AI_RATE_LIMITED" }, headers: { "Retry-After": "42" } }));
    const { description } = setup();
    fireEvent.click(screen.getByRole("button", { name: "Check my report" }));
    expect((await screen.findByRole("alert")).textContent).toContain("42 seconds");
    expect(description.value).toBe(DESCRIPTION);
  });

  it("RC-08b explains that checking is unavailable but submit still works", async () => {
    serve(() => ({ status: 503, body: { code: "AI_UNAVAILABLE" } }));
    setup();
    fireEvent.click(screen.getByRole("button", { name: "Check my report" }));
    expect((await screen.findByRole("alert")).textContent).toContain("You can still submit");
    expect((screen.getByRole("button", { name: "Submit report" }) as HTMLButtonElement).disabled).toBe(false);
  });

  it("shows progress and clears old results when a new check starts", async () => {
    let release!: (reply: Reply) => void;
    let first = true;
    serve(() => {
      if (first) {
        first = false;
        return { status: 200, body: { unclear: [], missing: [{ category: "Frequency", question: "How often?" }] } };
      }
      return new Promise<Reply>((resolve) => (release = resolve));
    });
    setup();
    fireEvent.click(screen.getByRole("button", { name: "Check my report" }));
    await screen.findByText("How often?");
    fireEvent.click(screen.getByRole("button", { name: "Check my report" }));
    expect(await screen.findByRole("status")).toBeTruthy();
    expect(screen.queryByText("How often?")).toBeNull();
    release({ status: 200, body: { unclear: [], missing: [] } });
    expect(await screen.findByText(/No questions found/)).toBeTruthy();
  });

  it("RC-01b is not offered on an existing, still editable report", async () => {
    const id = "33333333-3333-4333-8333-333333333333";
    const detail = { id, title: TITLE, description: DESCRIPTION, status: "SUBMITTED", version: 0, createdAt: "2026-11-13T00:00:00Z", updatedAt: "2026-11-13T00:00:00Z" };
    calls = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string, init: RequestInit = {}) => {
        calls.push({ url, init });
        const body = url.startsWith(`/api/reporter/reports/${id}/comments`)
          ? { items: [], page: 1, pageSize: 100, totalItems: 0 }
          : url === `/api/reporter/reports/${id}`
            ? detail
            : null;
        return new Response(body === null ? null : JSON.stringify(body), { status: body === null ? 404 : 200, headers: { "Content-Type": "application/json" } });
      }),
    );
    render(<ReportView id={id} user={{ ...USER, role: "REPORTER", active: true }} backHref="#/" onSignedOut={vi.fn()} />);
    expect((await screen.findAllByText(TITLE)).length).toBeGreaterThan(0);
    expect(screen.queryByRole("button", { name: /Check my report/ })).toBeNull();
    expect(checkCalls()).toHaveLength(0);
  });

  it("clears results on submit", async () => {
    serve(
      () => ({ status: 200, body: { unclear: [], missing: [{ category: "Frequency", question: "How often?" }] } }),
      () => ({ status: 500 }),
    );
    setup();
    fireEvent.click(screen.getByRole("button", { name: "Check my report" }));
    await screen.findByText("How often?");
    fireEvent.click(screen.getByRole("button", { name: "Submit report" }));
    await waitFor(() => expect(screen.queryByText("How often?")).toBeNull());
  });
});
