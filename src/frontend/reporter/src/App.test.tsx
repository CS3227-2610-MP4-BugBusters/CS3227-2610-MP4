import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App, parseRoute } from "./App";

type Handler = (url: string, init: RequestInit) => { status: number; body?: unknown; headers?: Record<string, string> };

const ME = { id: "11111111-1111-4111-8111-111111111111", displayName: "Riley Reporter", role: "REPORTER", active: true };
const REPORT = {
  id: "22222222-2222-4222-8222-222222222222",
  title: "Login fails",
  description: "## Steps\n\n1. Open **login**\n\n<img src=x onerror=alert(1)>",
  status: "SUBMITTED",
  version: 0,
  createdAt: "2026-11-09T05:00:00.000Z",
  updatedAt: "2026-11-09T05:00:00.000Z",
};
const DEV_COMMENT = {
  id: "33333333-3333-4333-8333-333333333333",
  body: "Can you attach the **log**?",
  author: { id: "44444444-4444-4444-8444-444444444444", displayName: "Dana Dev", role: "DEVELOPER" },
  createdAt: "2026-11-09T06:00:00.000Z",
};

let calls: { url: string; init: RequestInit }[] = [];

function serve(handler: Handler) {
  calls = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init: RequestInit = {}) => {
      calls.push({ url, init });
      const { status, body, headers } = handler(url, init);
      return new Response(body === undefined ? null : JSON.stringify(body), {
        status,
        headers: { "Content-Type": "application/json", ...(headers ?? {}) },
      });
    }),
  );
}

function common(url: string): ReturnType<Handler> | null {
  if (url === "/api/auth/me") return { status: 200, body: ME };
  if (url === "/api/auth/csrf") return { status: 200, body: { token: "tok", headerName: "X-CSRF-TOKEN" } };
  return null;
}

beforeEach(() => {
  window.location.hash = "";
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("routes", () => {
  it("parses list, new, report and page routes", () => {
    expect(parseRoute("")).toEqual({ name: "list", page: 1 });
    expect(parseRoute("#/?page=3")).toEqual({ name: "list", page: 3 });
    expect(parseRoute("#/new")).toEqual({ name: "new" });
    expect(parseRoute(`#/reports/${REPORT.id}`)).toEqual({ name: "report", id: REPORT.id });
  });
});

describe("session", () => {
  it("shows the sign-in form when there is no session", async () => {
    serve((url) => (url === "/api/auth/me" ? { status: 401, body: { code: "UNAUTHENTICATED" } } : { status: 404 }));
    render(<App />);
    expect(await screen.findByRole("heading", { name: "Sign in to report bugs" })).toBeTruthy();
  });

  it("refuses non-Reporter accounts without loading reports", async () => {
    serve((url) => (url === "/api/auth/me" ? { status: 200, body: { ...ME, role: "DEVELOPER" } } : { status: 500 }));
    render(<App />);
    expect((await screen.findByRole("alert")).textContent).toContain("Reporter accounts");
    expect(calls.some((c) => c.url.startsWith("/api/reporter"))).toBe(false);
  });
});

describe("report list", () => {
  it("lists the Reporter's reports with state and status", async () => {
    serve((url) => {
      const shared = common(url);
      if (shared) return shared;
      if (url.startsWith("/api/reporter/reports?")) {
        return {
          status: 200,
          body: {
            items: [REPORT, { ...REPORT, id: "55555555-5555-4555-8555-555555555555", title: "Old crash", status: "CLOSED" }],
            page: 1,
            pageSize: 25,
            totalItems: 2,
          },
        };
      }
      return { status: 404 };
    });
    render(<App />);
    expect(await screen.findByRole("link", { name: "Login fails" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "Old crash" }).getAttribute("href")).toBe("#/reports/55555555-5555-4555-8555-555555555555");
    expect(screen.getByRole("button", { name: /1 Open/ })).toBeTruthy();
    expect(screen.getByRole("button", { name: /1 Closed/ })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /1 Closed/ }));
    expect(screen.queryByRole("link", { name: "Login fails" })).toBeNull();
  });
});

describe("report page", () => {
  function reportServer(post: () => ReturnType<Handler>) {
    serve((url, init) => {
      const shared = common(url);
      if (shared) return shared;
      if (url === `/api/reporter/reports/${REPORT.id}`) return { status: 200, body: REPORT };
      if (url.startsWith(`/api/reporter/reports/${REPORT.id}/comments`)) {
        if (init.method === "POST") return post();
        return { status: 200, body: { items: [DEV_COMMENT], page: 1, pageSize: 100, totalItems: 1 } };
      }
      return { status: 404 };
    });
  }

  it("renders the description and comments as safe Markdown in a timeline", async () => {
    reportServer(() => ({ status: 500 }));
    window.location.hash = `#/reports/${REPORT.id}`;
    const { container } = render(<App />);
    expect(await screen.findByText("Dana Dev")).toBeTruthy();
    expect(screen.getByRole("heading", { level: 1 }).textContent).toContain("Login fails");
    await waitFor(() => expect(container.querySelector("strong")?.textContent).toBeTruthy());
    await waitFor(() => expect([...container.querySelectorAll(".markdown-body strong")].map((n) => n.textContent)).toContain("log"));
    expect(container.querySelector("img")).toBeNull();
    expect(container.innerHTML).not.toContain("onerror=\"");
    expect(screen.getByText("Developer")).toBeTruthy();
  });

  it("posts a comment with the CSRF token and appends it", async () => {
    const posted = { ...DEV_COMMENT, id: "66666666-6666-4666-8666-666666666666", body: "Attached.", author: { id: ME.id, displayName: ME.displayName, role: "REPORTER" } };
    reportServer(() => ({ status: 201, body: posted }));
    window.location.hash = `#/reports/${REPORT.id}`;
    render(<App />);
    fireEvent.change(await screen.findByRole("textbox", { name: "Comment" }), { target: { value: "Attached." } });
    fireEvent.click(screen.getByRole("button", { name: "Comment" }));
    expect(await screen.findByText("Attached.")).toBeTruthy();
    const post = calls.find((c) => c.init.method === "POST");
    expect(post?.url).toBe(`/api/reporter/reports/${REPORT.id}/comments`);
    expect(JSON.parse(String(post?.init.body))).toEqual({ body: "Attached." });
    expect((post?.init.headers as Record<string, string>)["X-CSRF-TOKEN"]).toBe("tok");
    expect((screen.getByRole("textbox", { name: "Comment" }) as HTMLTextAreaElement).value).toBe("");
  });

  it("keeps the draft and shows the wait time when rate limited", async () => {
    reportServer(() => ({ status: 429, body: { code: "RATE_LIMITED", message: "x", requestId: "r" }, headers: { "Retry-After": "17" } }));
    window.location.hash = `#/reports/${REPORT.id}`;
    render(<App />);
    fireEvent.change(await screen.findByRole("textbox", { name: "Comment" }), { target: { value: "secret draft" } });
    fireEvent.click(screen.getByRole("button", { name: "Comment" }));
    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toContain("17 seconds");
    expect(alert.textContent).not.toContain("secret draft");
    expect((screen.getByRole("textbox", { name: "Comment" }) as HTMLTextAreaElement).value).toBe("secret draft");
  });

  it("links back to the reports list from the report page", async () => {
    reportServer(() => ({ status: 500 }));
    window.location.hash = `#/reports/${REPORT.id}`;
    render(<App />);
    const crumbs = await screen.findByRole("navigation", { name: "Breadcrumb" });
    expect(crumbs.querySelector("a")?.getAttribute("href")).toBe("#/");
    expect(crumbs.textContent).toContain("Your reports");
    expect(crumbs.querySelector("[aria-current=page]")?.textContent).toBe("Login fails");
    expect(screen.getByRole("link", { name: /Back to your reports/ }).getAttribute("href")).toBe("#/");
  });

  it("returns to the list page the Reporter came from", async () => {
    serve((url) => {
      const shared = common(url);
      if (shared) return shared;
      if (url.startsWith("/api/reporter/reports?")) return { status: 200, body: { items: [REPORT], page: 2, pageSize: 25, totalItems: 30 } };
      if (url === `/api/reporter/reports/${REPORT.id}`) return { status: 200, body: REPORT };
      if (url.startsWith(`/api/reporter/reports/${REPORT.id}/comments`)) return { status: 200, body: { items: [], page: 1, pageSize: 100, totalItems: 0 } };
      return { status: 404 };
    });
    window.location.hash = "#/?page=2";
    render(<App />);
    await screen.findByRole("link", { name: "Login fails" });
    window.location.hash = `#/reports/${REPORT.id}`;
    window.dispatchEvent(new HashChangeEvent("hashchange"));
    const crumbs = await screen.findByRole("navigation", { name: "Breadcrumb" });
    expect(crumbs.querySelector("a")?.getAttribute("href")).toBe("#/?page=2");
  });

  it("offers editing only while the report is submitted", async () => {
    reportServer(() => ({ status: 500 }));
    window.location.hash = `#/reports/${REPORT.id}`;
    render(<App />);
    expect(await screen.findByRole("button", { name: "Edit description" })).toBeTruthy();
  });
});
