import { describe, it, expect, vi } from "vitest";
import { checkedFilters, parseDetail, parseUser, QueryError } from "./domain";
import { createHttpQueries } from "./http";
import { createDemoQueries, developers, fixtureId, reports } from "./demo";
const signal = () => new AbortController().signal;
describe("QR-02 synthetic contract", () => {
  it("defaults to 50 with stable tie ordering and paginates remaining rows", async () => {
    const q = createDemoQueries();
    const p = await q.listReports({}, signal());
    expect(p.total).toBe(63);
    expect(p.items).toHaveLength(50);
    expect(p.items.map((x) => x.id)).toEqual(
      reports.slice(0, 50).map((x) => x.id),
    );
    expect((await q.listReports({ offset: 50 }, signal())).items).toHaveLength(
      13,
    );
  });
  it("intersects each and all filters and handles unmatched results", async () => {
    const q = createDemoQueries();
    const cases: [Parameters<typeof q.listReports>[0], number[]][] = [
      [
        { status: "Assigned" },
        [2, 7, 12, 17, 22, 27, 32, 37, 42, 47, 52, 57, 62],
      ],
      [
        { priority: "HIGH" },
        [3, 7, 11, 15, 19, 23, 27, 31, 35, 39, 43, 47, 51, 55, 59, 63],
      ],
      [
        { assigneeId: developers[0].id },
        [
          3, 5, 9, 11, 15, 17, 21, 23, 27, 29, 33, 35, 39, 41, 45, 47, 51, 53,
          57, 59, 63,
        ],
      ],
      [
        { status: "Assigned", priority: "HIGH", assigneeId: developers[0].id },
        [27, 47],
      ],
    ];
    for (const [filter, ids] of cases) {
      const page = await q.listReports(filter, signal());
      expect(page.total).toBe(ids.length);
      expect(page.items.map((x) => x.id)).toEqual(ids.map(fixtureId));
    }
    expect(
      (await q.listReports({ assigneeId: fixtureId(999) }, signal())).total,
    ).toBe(0);
  });
  it("orders unsorted timestamps descending and equal timestamps by ID ascending", async () => {
    const q = createDemoQueries([
      { ...reports[2], updatedAt: "2026-11-06T00:00:00Z" },
      reports[1],
      reports[0],
      { ...reports[3], updatedAt: "2026-11-08T00:00:00Z" },
    ]);
    expect((await q.listReports({}, signal())).items.map((x) => x.id)).toEqual(
      [4, 1, 2, 3].map(fixtureId),
    );
  });
  it("orders fractional timestamps by instant and equivalent instants by ID", async () => {
    const q = createDemoQueries([
      { ...reports[2], updatedAt: "2026-11-07T02:00:00Z" },
      { ...reports[1], updatedAt: "2026-11-07T02:00:00.500Z" },
      { ...reports[0], updatedAt: "2026-11-07T02:00:00.000Z" },
      { ...reports[3], updatedAt: "2026-11-07T02:00:00.050Z" },
    ]);
    expect((await q.listReports({}, signal())).items.map((x) => x.id)).toEqual(
      [2, 4, 1, 3].map(fixtureId),
    );
  });
});
describe("QR-03 HTTP boundary", () => {
  it("uses exact GET paths, filters, credentials, cache, header, signal and no body", async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockImplementation(
        async () => new Response(JSON.stringify({ items: [], total: 0 })),
      );
    const q = createHttpQueries(fetcher),
      s = signal();
    await q.listReports(
      { status: "In progress", priority: "HIGH", assigneeId: developers[0].id },
      s,
    );
    expect(fetcher).toHaveBeenCalledWith(
      `/api/maintainer/reports?offset=0&limit=50&status=In+progress&priority=HIGH&assigneeId=${developers[0].id}`,
      {
        method: "GET",
        credentials: "same-origin",
        cache: "no-store",
        headers: { Accept: "application/json" },
        signal: s,
      },
    );
  });
  it("constructs identity/developer/detail paths and validates their responses", async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            id: fixtureId(900),
            displayName: "M",
            role: "MAINTAINER",
            active: true,
          }),
        ),
      )
      .mockResolvedValueOnce(new Response(JSON.stringify(developers)))
      .mockResolvedValueOnce(new Response(JSON.stringify(reports[0])));
    const q = createHttpQueries(fetcher),
      s = signal();
    await q.getCurrentUser(s);
    await q.listDevelopers(s);
    await q.getReport(reports[0].id, s);
    expect(fetcher.mock.calls.map((x) => x[0])).toEqual([
      "/api/auth/me",
      "/api/maintainer/developers",
      `/api/maintainer/reports/${reports[0].id}`,
    ]);
  });
  it("rejects unsupported/invalid filters before transport", () => {
    const fetcher = vi.fn<typeof fetch>(),
      q = createHttpQueries(fetcher);
    for (const f of [
      { limit: 0 },
      { limit: 101 },
      { offset: -1 },
      { offset: 1.2 },
      { status: "bad" },
      { assigneeId: "bad" },
      { role: "MAINTAINER" },
    ])
      expect(() => q.listReports(f as never, signal())).toThrow(QueryError);
    expect(checkedFilters({ limit: 100 }).limit).toBe(100);
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("validates nested details, dates, identities and collection/text caps", () => {
    expect(parseDetail(reports[0])).toEqual(reports[0]);
    for (const bad of [
      {
        ...reports[0],
        notes: [
          { ...reports[0].notes[0], author: { id: "bad", displayName: "X" } },
        ],
      },
      {
        ...reports[0],
        checklist: [{ id: fixtureId(1), text: "x", completed: "yes" }],
      },
      { ...reports[0], description: "x".repeat(20001) },
      { ...reports[0], comments: Array(501).fill(reports[0].notes[0]) },
      { ...reports[0], updatedAt: "yesterday" },
      {
        ...reports[0],
        currentProposal: { ...reports[0].notes[0], version: -1 },
      },
    ])
      expect(() => parseDetail(bad)).toThrow(QueryError);
    expect(() =>
      parseUser({
        id: fixtureId(1),
        displayName: "M",
        role: "MAINTAINER",
        active: "true",
      }),
    ).toThrow(QueryError);
  });
  it.each([
    [401, "session"],
    [403, "denied"],
    [404, "missing"],
    [500, "network"],
  ])("bounds status %s errors", async (status, code) => {
    const q = createHttpQueries(
      vi
        .fn<typeof fetch>()
        .mockResolvedValue(
          new Response("private stack text", { status: Number(status) }),
        ),
    );
    await expect(q.getReport(fixtureId(1), signal())).rejects.toMatchObject({
      code,
    });
  });
  it("rejects malformed JSON, oversized byte streams, and malformed nested transport payloads", async () => {
    for (const body of [
      "not json",
      "x".repeat(2_000_001),
      JSON.stringify({ ...reports[0], history: [{}] }),
    ]) {
      const q = createHttpQueries(
        vi.fn<typeof fetch>().mockResolvedValue(new Response(body)),
      );
      await expect(q.getReport(fixtureId(1), signal())).rejects.toMatchObject({
        code: "contract",
      });
    }
  });
  it("maps network and caller cancellation without exposing provider text", async () => {
    const q = createHttpQueries(
      vi.fn<typeof fetch>().mockRejectedValue(new Error("private upstream")),
    );
    await expect(q.getCurrentUser(signal())).rejects.toMatchObject({
      code: "network",
      message: "The service is unavailable. Please retry.",
    });
    const c = new AbortController();
    c.abort();
    await expect(q.getCurrentUser(c.signal)).rejects.toMatchObject({
      code: "cancelled",
    });
  });
});
