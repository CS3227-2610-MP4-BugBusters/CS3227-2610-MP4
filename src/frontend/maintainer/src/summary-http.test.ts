import { describe, expect, it, vi } from "vitest";
import { createHttpQueries } from "./http";
import { withHttpActions } from "./action-http";
import { QueryError, type SummaryResult } from "./domain";

const id = "11111111-1111-4111-8111-111111111111";
const csrf = () => new Response(JSON.stringify({ token: "csrf-value", headerName: "X-CSRF-TOKEN" }));
const result: SummaryResult = {
  capturedAt: "2026-11-09T10:00:00Z", filters: { priority: "HIGH" },
  included: [{ id, version: 4, truncatedFields: ["latestUpdates"] }], excluded: [],
  findings: { overview: [{ text: "等待確認 🧪", reportIds: [id] }], blockers: [], missingInformation: [] },
  requiresReview: true, providerMode: "soc", promptVersion: "MNT-SUMMARY-1",
};

function api(fetcher: typeof fetch) {
  const queries = withHttpActions(createHttpQueries(fetcher), fetcher);
  const summarize = queries.summarizeQueue;
  expect(typeof summarize).toBe("function");
  if (typeof summarize !== "function") throw new Error("summary HTTP transport is missing");
  return { queries, summarize: summarize.bind(queries) };
}

describe("Maintainer summary HTTP boundary", () => {
  it("posts canonical unresolved filters with CSRF and validates the result", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(
      new Response(JSON.stringify(result)),
    );
    const { summarize } = api(fetcher);
    await expect(summarize({ filters: { priority: "HIGH" }, regenerate: true })).resolves.toEqual(result);
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(fetcher.mock.calls[0][0]).toBe("/api/auth/csrf");
    expect(fetcher.mock.calls[0][1]).toMatchObject({ method: "GET", credentials: "same-origin", cache: "no-store" });
    expect(fetcher.mock.calls[1][0]).toBe("/api/maintainer/queue-summary");
    expect(fetcher.mock.calls[1][1]).toMatchObject({
      method: "POST", credentials: "same-origin", cache: "no-store",
      headers: { Accept: "application/json", "Content-Type": "application/json", "X-CSRF-TOKEN": "csrf-value" },
      body: JSON.stringify({ filters: { priority: "HIGH" }, regenerate: true }),
    });
  });

  it.each([
    [{ status: "Closed" }, "validation"],
    [{ status: "not-a-status" }, "validation"],
    [{ assigneeId: "bad-id" }, "validation"],
    [{ unexpected: true }, "validation"],
  ])("rejects invalid queue filters before fetch (%j)", async (filters, code) => {
    const fetcher = vi.fn<typeof fetch>();
    const { summarize } = api(fetcher);
    await expect(summarize({ filters, regenerate: false } as never)).rejects.toMatchObject({ code });
    expect(fetcher).not.toHaveBeenCalled();
  });

  it.each([
    ["not-json", "contract"],
    [JSON.stringify({ ...result, unexpected: true }), "contract"],
    [JSON.stringify({ ...result, findings: { ...result.findings, overview: [{ text: "orphan", reportIds: ["22222222-2222-4222-8222-222222222222"] }] } }), "contract"],
    [JSON.stringify({ ...result, findings: { ...result.findings, overview: [{ text: "x".repeat(601), reportIds: [id] }] } }), "contract"],
  ])("rejects malformed or unsafe response without exposing it", async (body, code) => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(new Response(body));
    const { summarize } = api(fetcher);
    await expect(summarize({ filters: {}, regenerate: false })).rejects.toMatchObject({ code });
  });

  it.each([[401, "session"], [403, "denied"], [409, "conflict"], [502, "network"], [503, "network"], [504, "network"]] as const)(
    "maps HTTP %i to a safe query error", async (status, code) => {
      const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(
        new Response("provider secret: raw completion", { status }),
      );
      const { summarize } = api(fetcher);
      const error = await summarize({ filters: {}, regenerate: false }).catch((e: unknown) => e);
      expect(error).toBeInstanceOf(QueryError);
      expect(error).toMatchObject({ code });
      expect((error as Error).message).not.toContain("provider secret");
    },
  );

  it("enforces a 35 second client deadline even when fetch ignores abort", async () => {
    vi.useFakeTimers();
    try {
      const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(csrf()).mockImplementationOnce(() => new Promise(() => undefined));
      const { summarize } = api(fetcher);
      const pending = summarize({ filters: {}, regenerate: false });
      const rejection = expect(pending).rejects.toMatchObject({ code: "network" });
      await vi.advanceTimersByTimeAsync(35_000);
      await rejection;
    } finally { vi.useRealTimers(); }
  });

  it("honors caller cancellation", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(csrf()).mockImplementationOnce(() => new Promise(() => undefined));
    const { summarize } = api(fetcher);
    const controller = new AbortController();
    const pending = summarize({ filters: {}, regenerate: false }, controller.signal);
    controller.abort();
    await expect(pending).rejects.toMatchObject({ code: "cancelled" });
  });
});
