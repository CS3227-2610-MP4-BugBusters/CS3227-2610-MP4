import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { DeveloperApp } from "./DeveloperApp";

vi.mock("./DeveloperChecklist", () => ({
  DeveloperChecklist: ({ reportId }: { reportId: string }) => (
    <div>Checklist for {reportId}</div>
  ),
}));

const reportsPath = "/api/developer/reports";
const firstReport = {
  id: "00000000-0000-4000-8000-000000000101",
  title: "Login & export failure",
  status: "ASSIGNED",
};
const secondReport = {
  id: "00000000-0000-4000-8000-000000000102",
  title: "Export times out",
  status: "IN_PROGRESS",
};
const thirdReport = {
  id: "00000000-0000-4000-8000-000000000103",
  title: "Latest search result",
  status: "IN_PROGRESS",
};

type ReportsResponse = {
  items: typeof firstReport[];
  page: number;
  pageSize: number;
  totalItems: number;
};

let fetchMock: ReturnType<typeof vi.fn<typeof fetch>>;
let listRequests: URL[];
let listHandler: (url: URL) => Response | Promise<Response>;

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function page(items: typeof firstReport[], pageNumber = 1, totalItems = items.length): Response {
  const body: ReportsResponse = { items, page: pageNumber, pageSize: 20, totalItems };
  return jsonResponse(body);
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

function submitSearch(value: string) {
  const input = screen.getByLabelText(/search/i);
  fireEvent.change(input, { target: { value } });
  const form = input.closest("form");
  const searchButton = screen.queryByRole("button", { name: /search/i });
  if (form) fireEvent.submit(form);
  else if (searchButton) fireEvent.click(searchButton);
  else fireEvent.keyDown(input, { key: "Enter", code: "Enter" });
}

function latestListRequest(): URL {
  const latest = listRequests.at(-1);
  if (!latest) throw new Error("No assigned-reports request was made");
  return latest;
}

beforeEach(() => {
  listRequests = [];
  listHandler = () => page([firstReport]);
  fetchMock = vi.fn<typeof fetch>(async (input) => {
    const url = new URL(String(input), window.location.origin);
    if (url.pathname === "/api/auth/me") {
      return jsonResponse({
        id: "00000000-0000-4000-8000-000000000001",
        displayName: "Developer One",
        role: "DEVELOPER",
        active: true,
      });
    }
    if (url.pathname === reportsPath) {
      listRequests.push(url);
      return listHandler(url);
    }
    if (url.pathname === "/api/developer/notifications") return jsonResponse({ items: [] });
    throw new Error(`Unexpected request: ${url.pathname}`);
  });
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("Developer assigned-reports dashboard", () => {
  it("loads the authorized report page and displays its title and status", async () => {
    listHandler = () => page([firstReport, secondReport], 1, 2);
    render(<DeveloperApp />);

    expect(await screen.findByText(firstReport.title)).toBeTruthy();
    expect(screen.getByText(secondReport.title)).toBeTruthy();
    expect(within(screen.getByRole("list")).getByText("Assigned", { exact: true })).toBeTruthy();
    expect(within(screen.getByRole("list")).getByText(/in progress/i)).toBeTruthy();
    expect(latestListRequest().pathname).toBe(reportsPath);
  });

  it("trims and encodes title search, and supports searching by the complete report ID", async () => {
    listHandler = (url) => {
      const search = url.searchParams.get("search");
      if (search === null) return page([firstReport]);
      return search.startsWith("0000") ? page([firstReport]) : page([secondReport]);
    };
    render(<DeveloperApp />);
    await screen.findByText(firstReport.title);

    submitSearch("  login & export  ");
    await screen.findByText(secondReport.title);
    expect(latestListRequest().searchParams.get("search")).toBe("login & export");

    submitSearch(` ${firstReport.id} `);
    await screen.findByText(firstReport.title);
    expect(latestListRequest().searchParams.get("search")).toBe(firstReport.id);
  });

  it("combines title search and status, and returns to page one when filters change", async () => {
    listHandler = (url) => page(url.searchParams.get("page") === "2" ? [secondReport] : [firstReport],
      Number(url.searchParams.get("page") ?? 1), 21);
    render(<DeveloperApp />);
    await screen.findByText(firstReport.title);

    fireEvent.click(screen.getByRole("button", { name: /next/i }));
    await screen.findByText(secondReport.title);
    const status = screen.getByRole("combobox", { name: /status/i });
    fireEvent.change(status, { target: { value: "IN_PROGRESS" } });
    fireEvent.change(screen.getByLabelText(/search/i), {
      target: { value: "export" },
    });
    const searchInput = screen.getByLabelText(/search/i);
    const searchForm = searchInput.closest("form");
    const searchButton = screen.queryByRole("button", { name: /search/i });
    if (searchForm) fireEvent.submit(searchForm);
    else if (searchButton) fireEvent.click(searchButton);
    else fireEvent.keyDown(searchInput, { key: "Enter", code: "Enter" });

    await waitFor(() => expect(latestListRequest().searchParams.get("page")).toBe("1"));
    expect(latestListRequest().searchParams.get("status")).toBe("IN_PROGRESS");
    expect(latestListRequest().searchParams.get("search")).toBe("export");
  });

  it("moves forward and backward through server pages", async () => {
    listHandler = (url) => url.searchParams.get("page") === "2"
      ? page([secondReport], 2, 21)
      : page([firstReport], 1, 21);
    render(<DeveloperApp />);
    await screen.findByText(firstReport.title);

    fireEvent.click(screen.getByRole("button", { name: /next/i }));
    await screen.findByText(secondReport.title);
    expect(latestListRequest().searchParams.get("page")).toBe("2");

    fireEvent.click(screen.getByRole("button", { name: /previous/i }));
    await screen.findByText(firstReport.title);
    expect(latestListRequest().searchParams.get("page")).toBe("1");
  });

  it("distinguishes no assignments, no filter matches, and an out-of-range page", async () => {
    listHandler = () => page([]);
    render(<DeveloperApp />);
    expect(await screen.findByText(/no assigned reports/i)).toBeTruthy();

    cleanup();
    listHandler = (url) => url.searchParams.has("search") ? page([], 1, 0) : page([firstReport]);
    render(<DeveloperApp />);
    await screen.findByText(firstReport.title);
    submitSearch("missing term");
    expect(await screen.findByText(/no (matching )?reports|no matches/i)).toBeTruthy();

    cleanup();
    listHandler = (url) => url.searchParams.get("page") === "2"
      ? page([], 2, 21)
      : page([firstReport], 1, 21);
    render(<DeveloperApp />);
    await screen.findByText(firstReport.title);
    fireEvent.click(screen.getByRole("button", { name: /next/i }));
    await waitFor(() => {
      expect(screen.getByRole("status").textContent).toMatch(/out of range/i);
    });
  });

  it("shows a loading state until the request settles and a recoverable error on failure", async () => {
    const pending = deferred<Response>();
    listHandler = () => pending.promise;
    render(<DeveloperApp />);
    expect(await screen.findByRole("status")).toBeTruthy();
    await act(async () => pending.resolve(page([firstReport])));
    expect(await screen.findByText(firstReport.title)).toBeTruthy();

    cleanup();
    let attempt = 0;
    listHandler = () => ++attempt === 1
      ? jsonResponse({
        code: "INTERNAL_ERROR",
        message: "private database detail",
        requestId: "r2",
      }, 500)
      : page([firstReport]);
    render(<DeveloperApp />);
    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(screen.queryByText(/private database detail|INTERNAL_ERROR/)).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /retry/i }));
    expect(await screen.findByText(firstReport.title)).toBeTruthy();
  });

  it("ignores an older filter response that arrives after the latest response", async () => {
    const older = deferred<Response>();
    listHandler = (url) => url.searchParams.get("search") === "slow"
      ? older.promise
      : url.searchParams.get("search") === "latest"
        ? page([thirdReport])
        : page([firstReport]);
    render(<DeveloperApp />);
    await screen.findByText(firstReport.title);

    submitSearch("slow");
    await waitFor(() => expect(listRequests.length).toBe(2));
    submitSearch("latest");
    await screen.findByText(thirdReport.title);
    await act(async () => older.resolve(page([secondReport])));

    await waitFor(() => expect(screen.getByText(thirdReport.title)).toBeTruthy());
    expect(screen.queryByText(secondReport.title)).toBeNull();
  });

  it("clears report data and keeps an authorization denial distinct from an empty list", async () => {
    listHandler = () => jsonResponse({
      code: "FORBIDDEN",
      message: "Access denied",
      requestId: "r3",
    }, 403);
    render(<DeveloperApp />);

    expect(await screen.findByText(/developer access is required/i)).toBeTruthy();
    expect(screen.queryByText(firstReport.title)).toBeNull();
    expect(screen.queryByText(/no assigned reports/i)).toBeNull();
  });
});
