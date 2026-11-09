import { describe, expect, it, vi } from "vitest";
import { createHttpQueries } from "./http";
import { withHttpActions } from "./action-http";
import { reports } from "./demo";
import { QueryError } from "./domain";

const csrf = () => new Response(JSON.stringify({ token: "session-csrf", headerName: "X-CSRF-TOKEN" }), { status: 200 });

describe("Maintainer mutation HTTP boundary", () => {
  it("sends an exact versioned payload with session CSRF and validates the updated summary", async () => {
    const updated = { ...reports[0], version: 5, priority: "HIGH" as const };
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(new Response(JSON.stringify(updated), { status: 200 }));
    const queries = withHttpActions(createHttpQueries(fetcher), fetcher);
    await queries.updatePriority({ reportId: reports[0].id, expectedVersion: 4, priority: "HIGH" });
    expect(fetcher).toHaveBeenCalledTimes(2);
    const [csrfPath, csrfInit] = fetcher.mock.calls[0];
    expect(csrfPath).toBe("/api/auth/csrf");
    expect(csrfInit).toMatchObject({ method: "GET", credentials: "same-origin", cache: "no-store" });
    const [path, init] = fetcher.mock.calls[1];
    expect(path).toBe(`/api/maintainer/reports/${reports[0].id}/priority`);
    expect(init).toMatchObject({ method: "PUT", credentials: "same-origin", cache: "no-store", headers: { Accept: "application/json", "Content-Type": "application/json", "X-CSRF-TOKEN": "session-csrf" } });
    expect(JSON.parse(String(init?.body))).toEqual({ expectedVersion: 4, priority: "HIGH" });
  });

  it("maps stale writes safely and does not retry them", async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: "STALE_VERSION", message: "raw details omitted" }), { status: 409 }));
    const queries = withHttpActions(createHttpQueries(fetcher), fetcher);
    await expect(queries.reopenReport({ reportId: reports[4].id, expectedVersion: 2, reason: "Regression" })).rejects.toMatchObject({ code: "conflict" });
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it("requires a valid report identity before any network request", async () => {
    const fetcher = vi.fn<typeof fetch>();
    const queries = withHttpActions(createHttpQueries(fetcher), fetcher);
    await expect(queries.updatePriority({ reportId: "bad", expectedVersion: 0, priority: "HIGH" })).rejects.toBeInstanceOf(QueryError);
    expect(fetcher).not.toHaveBeenCalled();
  });

  it("rejects a successful summary for another report or the wrong version", async () => {
    const wrong = { ...reports[1], version: 5, priority: "HIGH" as const };
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(new Response(JSON.stringify(wrong), { status: 200 }));
    const queries = withHttpActions(createHttpQueries(fetcher), fetcher);
    await expect(queries.updatePriority({ reportId: reports[0].id, expectedVersion: 4, priority: "HIGH" })).rejects.toMatchObject({ code: "contract" });
  });

  it("settles a forbidden response without consuming its unending error stream", async () => {
    const hanging = new Response(new ReadableStream<Uint8Array>({ start() { /* intentionally never closes */ } }), { status: 403 });
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(hanging);
    const queries = withHttpActions(createHttpQueries(fetcher), fetcher);
    await expect(queries.reopenReport({ reportId: reports[4].id, expectedVersion: 2, reason: "Regression" })).rejects.toMatchObject({ code: "denied" });
  });

  it("settles caller cancellation while a response reader ignores cancellation and sends no later request", async () => {
    const hanging = new Response(new ReadableStream<Uint8Array>({ start(controller) { controller.enqueue(new TextEncoder().encode("{")); } }), { status: 200 });
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(hanging);
    const queries = withHttpActions(createHttpQueries(fetcher), fetcher);
    const controller = new AbortController();
    const pending = queries.updatePriority({ reportId: reports[0].id, expectedVersion: 0, priority: "HIGH" }, controller.signal);
    await vi.waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    controller.abort();
    await expect(pending).rejects.toMatchObject({ code: "cancelled" });
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it("enforces the end-to-end deadline while reading an unending conflict body", async () => {
    vi.useFakeTimers();
    try {
      const hanging = new Response(new ReadableStream<Uint8Array>({ start(controller) { controller.enqueue(new TextEncoder().encode("{")); } }), { status: 409 });
      const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(hanging);
      const queries = withHttpActions(createHttpQueries(fetcher), fetcher);
      const pending = queries.reopenReport({ reportId: reports[4].id, expectedVersion: 2, reason: "Regression" });
      const rejection = expect(pending).rejects.toMatchObject({ code: "network" });
      await vi.advanceTimersByTimeAsync(0);
      expect(fetcher).toHaveBeenCalledTimes(2);
      await vi.advanceTimersByTimeAsync(10_000);
      await rejection;
    } finally { vi.useRealTimers(); }
  });

  it("rejects C1 input before fetching and C1 text in a comment response", async () => {
    const fetcher = vi.fn<typeof fetch>();
    const queries = withHttpActions(createHttpQueries(fetcher), fetcher);
    await expect(queries.assignReport({ reportId: reports[1].id, expectedVersion: 1, developerId: reports[0].id, reason: "Bad\u0085reason" })).rejects.toMatchObject({ code: "validation" });
    expect(fetcher).not.toHaveBeenCalled();

    fetcher.mockResolvedValueOnce(csrf()).mockResolvedValueOnce(new Response(JSON.stringify({
      reportVersion: 5,
      comment: { id: reports[0].id, author: reports[0].reporter, text: "Bad\u009bcomment", createdAt: "2026-11-09T00:00:00Z" },
    }), { status: 201 }));
    await expect(queries.addComment({ reportId: reports[0].id, expectedVersion: 4, text: "valid comment" })).rejects.toMatchObject({ code: "contract" });
  });
});
