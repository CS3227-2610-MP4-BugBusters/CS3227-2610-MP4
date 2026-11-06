import { afterEach, describe, it, expect, vi } from "vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  act,
} from "@testing-library/react";
import { App } from "./App";
import { QueryError, type Page, type Queries, type Detail } from "./domain";
import { createDemoQueries, fixtureId, reports } from "./demo";
afterEach(cleanup);
const setup = (overrides: Partial<Queries> = {}) => ({
  ...createDemoQueries(),
  ...overrides,
});
const deferred = <T,>() => {
  let resolve!: (v: T) => void, reject!: (e: unknown) => void;
  const promise = new Promise<T>((a, b) => {
    resolve = a;
    reject = b;
  });
  return { promise, resolve, reject };
};
describe("QR-01/04/05/07 feature states", () => {
  it("renders all read-only evidence as text and visibly labels the demo", async () => {
    render(<App queries={setup()} synthetic />);
    await screen.findByText("63 matching reports");
    fireEvent.click(screen.getByRole("button", { name: /Report 4:/ }));
    await screen.findByRole("heading", { name: "Description" });
    for (const name of [
      "Reproduction steps",
      "Expected behavior",
      "Actual behavior",
      "Environment",
      "Investigation notes",
      "Responses",
      "Discussion",
      "Checklist",
      "Current proposal",
      "History",
    ])
      expect(screen.getByRole("heading", { name })).toBeTruthy();
    expect(
      screen.getByText("Investigation evidence <img src=x onerror=alert(1)>"),
    ).toBeTruthy();
    expect(document.querySelector("img")).toBeNull();
    expect(screen.getByText(/Synthetic demonstration/)).toBeTruthy();
    expect(
      screen.queryByRole("button", { name: /^(Assign|Close|Generate)( |$)/i }),
    ).toBeNull();
  });
  it.each([
    { active: false, role: "MAINTAINER" },
    { active: true, role: "DEVELOPER" },
    { active: true, role: "REPORTER" },
  ])("prevents protected reads for $role/$active", async (u) => {
    const list = vi.fn();
    render(
      <App
        queries={setup({
          getCurrentUser: async () =>
            ({ id: fixtureId(1), displayName: "X", ...u }) as never,
          listReports: list,
        })}
      />,
    );
    await screen.findByText("Active Maintainer access is required.");
    expect(list).not.toHaveBeenCalled();
  });
  it("handles unauthenticated identity without protected requests", async () => {
    const list = vi.fn();
    render(
      <App
        queries={setup({
          getCurrentUser: async () => {
            throw new QueryError("session");
          },
          listReports: list,
        })}
      />,
    );
    await screen.findByText("Please sign in to continue.");
    expect(list).not.toHaveBeenCalled();
  });
  it("distinguishes empty, list failure and current-operation retry", async () => {
    const list = vi
      .fn()
      .mockRejectedValueOnce(new Error("private secret"))
      .mockResolvedValue({ items: [], total: 0 });
    const identity = vi.fn(createDemoQueries().getCurrentUser);
    render(
      <App queries={setup({ listReports: list, getCurrentUser: identity })} />,
    );
    await screen.findByText("The service is unavailable. Please retry.");
    expect(screen.queryByText(/private secret/)).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    await screen.findByText("No reports match these filters.");
    expect(identity).toHaveBeenCalledTimes(1);
  });
  it("distinguishes detail missing from list content", async () => {
    render(
      <App
        queries={setup({
          getReport: async () => {
            throw new QueryError("missing");
          },
        })}
      />,
    );
    await screen.findByText("63 matching reports");
    fireEvent.click(screen.getByRole("button", { name: /Report 1:/ }));
    await screen.findByText("This report is no longer available.");
    expect(screen.getByText("63 matching reports")).toBeTruthy();
  });
  it("keeps developer failure independent and retries only developers", async () => {
    const list = vi.fn(createDemoQueries().listReports),
      dev = vi
        .fn()
        .mockRejectedValueOnce(new QueryError("network"))
        .mockResolvedValue([]);
    render(<App queries={setup({ listReports: list, listDevelopers: dev })} />);
    await screen.findByText("63 matching reports");
    await screen.findByText("The service is unavailable. Please retry.");
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    await waitFor(() => expect(dev).toHaveBeenCalledTimes(2));
    expect(list).toHaveBeenCalledTimes(1);
  });
});
describe("QR-06 cancellation-ignoring races", () => {
  it("requires the replacement adapter identity before any new protected read", async () => {
    const initial = setup();
    const view = render(<App queries={initial} />);
    await screen.findByText("63 matching reports");
    const identity = deferred<Awaited<ReturnType<Queries["getCurrentUser"]>>>(),
      list = vi.fn(),
      devs = vi.fn();
    view.rerender(
      <App
        queries={setup({
          getCurrentUser: () => identity.promise,
          listReports: list,
          listDevelopers: devs,
        })}
      />,
    );
    await screen.findByText("Checking access…");
    expect(list).not.toHaveBeenCalled();
    expect(devs).not.toHaveBeenCalled();
    await act(async () =>
      identity.resolve({
        id: fixtureId(1),
        displayName: "Reporter",
        active: true,
        role: "REPORTER",
      }),
    );
    await screen.findByText("Active Maintainer access is required.");
    expect(list).not.toHaveBeenCalled();
    expect(devs).not.toHaveBeenCalled();
  });
  it("paginates within bounds and clears selection when filters reset the offset", async () => {
    render(<App queries={setup()} />);
    await screen.findByText("63 matching reports");
    expect(
      (screen.getByRole("button", { name: "Previous" }) as HTMLButtonElement)
        .disabled,
    ).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    await screen.findByText("Page 2");
    expect(screen.getAllByRole("button", { name: /^Report / })).toHaveLength(
      13,
    );
    expect(
      (screen.getByRole("button", { name: "Next" }) as HTMLButtonElement)
        .disabled,
    ).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: /Report 51:/ }));
    await screen.findByRole("heading", { name: "Description" });
    fireEvent.change(screen.getByLabelText("Priority"), {
      target: { value: "HIGH" },
    });
    await screen.findByText("16 matching reports");
    expect(screen.getByText("Page 1")).toBeTruthy();
    expect(screen.queryByRole("heading", { name: "Description" })).toBeNull();
  });
  it("a same-session obsolete protected denial still clears current content", async () => {
    const old = deferred<Page>();
    let n = 0;
    render(
      <App
        queries={setup({
          listReports: async () =>
            ++n === 1 ? old.promise : { items: [reports[1]], total: 1 },
        })}
      />,
    );
    await waitFor(() => expect(n).toBe(1));
    fireEvent.change(screen.getByLabelText("Status"), {
      target: { value: "Assigned" },
    });
    await screen.findByText("1 matching reports");
    await act(async () => old.reject(new QueryError("denied")));
    await screen.findByText("Active Maintainer access is required.");
    expect(screen.queryByText("1 matching reports")).toBeNull();
  });
  it("an obsolete session denial cannot revoke a refreshed identity", async () => {
    const old = deferred<Page>();
    let n = 0;
    render(
      <App
        queries={setup({
          listReports: async () =>
            ++n === 1 ? old.promise : { items: [], total: 0 },
        })}
      />,
    );
    await waitFor(() => expect(n).toBe(1));
    fireEvent.click(
      screen.getByRole("button", { name: "Refresh session and queue" }),
    );
    await screen.findByText("No reports match these filters.");
    await act(async () => old.reject(new QueryError("denied")));
    expect(
      screen.queryByText("Active Maintainer access is required."),
    ).toBeNull();
    expect(screen.getByText("No reports match these filters.")).toBeTruthy();
  });
  it("ignores obsolete filter lists and resets page/selection", async () => {
    const old = deferred<Page>();
    let count = 0;
    const list = vi.fn<Queries["listReports"]>(async () =>
      ++count === 1 ? old.promise : { items: [reports[1]], total: 1 },
    );
    render(<App queries={setup({ listReports: list })} />);
    await waitFor(() => expect(list).toHaveBeenCalledTimes(1));
    fireEvent.change(screen.getByLabelText("Status"), {
      target: { value: "Assigned" },
    });
    await screen.findByText("1 matching reports");
    await act(async () => old.resolve({ items: [reports[0]], total: 99 }));
    expect(screen.queryByText("99 matching reports")).toBeNull();
    expect(list.mock.calls[1][0]).toMatchObject({
      status: "Assigned",
      offset: 0,
    });
  });
  it("clears old detail immediately and ignores old selected detail", async () => {
    const old = deferred<Detail>();
    render(
      <App
        queries={setup({
          getReport: async (id) =>
            id === reports[0].id
              ? old.promise
              : { ...reports[1], description: "CURRENT DETAIL" },
        })}
      />,
    );
    await screen.findByText("63 matching reports");
    fireEvent.click(screen.getByRole("button", { name: /Report 1:/ }));
    await screen.findByText("Loading report details…");
    fireEvent.click(screen.getByRole("button", { name: /Report 2:/ }));
    await screen.findByText("CURRENT DETAIL");
    await act(async () =>
      old.resolve({ ...reports[0], description: "OBSOLETE DETAIL" }),
    );
    expect(screen.queryByText("OBSOLETE DETAIL")).toBeNull();
  });
  it("clears protected rows on denial and blocks late pending detail", async () => {
    const old = deferred<Detail>();
    const dev = deferred<never>();
    render(
      <App
        queries={setup({
          getReport: () => old.promise,
          listDevelopers: () => dev.promise,
        })}
      />,
    );
    await screen.findByText("63 matching reports");
    fireEvent.click(screen.getByRole("button", { name: /Report 1:/ }));
    await act(async () => dev.reject(new QueryError("denied")));
    await screen.findByText("Active Maintainer access is required.");
    expect(screen.queryByText("63 matching reports")).toBeNull();
    await act(async () => old.resolve(reports[0]));
    expect(screen.queryByRole("heading", { name: "Description" })).toBeNull();
  });
  it("refresh rechecks identity and ignores stale previous-session results", async () => {
    const old = deferred<Page>();
    let n = 0;
    const identity = vi.fn(createDemoQueries().getCurrentUser);
    render(
      <App
        queries={setup({
          getCurrentUser: identity,
          listReports: async () =>
            ++n === 1 ? old.promise : { items: [], total: 0 },
        })}
      />,
    );
    await waitFor(() => expect(n).toBe(1));
    fireEvent.click(
      screen.getByRole("button", { name: "Refresh session and queue" }),
    );
    await screen.findByText("No reports match these filters.");
    await act(async () => old.resolve({ items: [reports[0]], total: 1 }));
    expect(screen.queryByText("1 matching reports")).toBeNull();
    expect(identity).toHaveBeenCalledTimes(2);
  });
});
