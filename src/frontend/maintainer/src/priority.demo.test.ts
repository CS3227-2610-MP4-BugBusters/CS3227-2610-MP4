import { describe, expect, it } from "vitest";
import { createDemoQueries, fixtureId, reports } from "./demo";
import { parseDetail, QueryError } from "./domain";

describe("isolated demo priority operation", () => {
  it("increments version and history while preserving other report data and exported fixtures", async () => {
    const original = reports[0];
    const fixtureBefore = structuredClone(original);
    const first = createDemoQueries([original]);
    const second = createDemoQueries([original]);
    const update = first.updatePriority!;
    const summary = await update(
      original.id,
      { expectedVersion: original.version, priority: "CRITICAL" },
      new AbortController().signal,
    );

    expect(summary).toMatchObject({
      id: original.id,
      priority: "CRITICAL",
      version: original.version + 1,
    });
    expect(summary).not.toHaveProperty("description");
    const changed = await first.getReport(
      original.id,
      new AbortController().signal,
    );
    expect(parseDetail(changed)).toEqual(changed);
    expect(changed).toMatchObject({
      status: original.status,
      priority: "CRITICAL",
      version: original.version + 1,
      description: original.description,
      notes: original.notes,
      checklist: original.checklist,
      currentProposal: original.currentProposal,
    });
    expect(changed.history).toHaveLength(original.history.length + 1);
    expect(changed.history.at(-1)?.type).toBe("Priority changed");
    expect(
      await second.getReport(original.id, new AbortController().signal),
    ).toEqual(original);
    expect(original).toEqual(fixtureBefore);
  });

  it("rejects invalid IDs and priorities and reports stale versions without changing state", async () => {
    const adapter = createDemoQueries([reports[0]]);
    const update = adapter.updatePriority!;
    const signal = new AbortController().signal;
    await expect(
      update("not-an-id", { expectedVersion: 0, priority: "HIGH" }, signal),
    ).rejects.toMatchObject({ code: "contract" });
    await expect(
      update(fixtureId(1), { expectedVersion: -1, priority: "HIGH" }, signal),
    ).rejects.toMatchObject({ code: "contract" });
    await expect(
      update(
        fixtureId(1),
        { expectedVersion: 0, priority: "urgent" as never },
        signal,
      ),
    ).rejects.toMatchObject({ code: "contract" });

    const before = await adapter.getReport(fixtureId(1), signal);
    await expect(
      update(fixtureId(1), { expectedVersion: 99, priority: "HIGH" }, signal),
    ).rejects.toMatchObject({ status: 409, code: "STALE_VERSION" });
    expect(await adapter.getReport(fixtureId(1), signal)).toEqual(before);
    await expect(
      update(fixtureId(2), { expectedVersion: 0, priority: "HIGH" }, signal),
    ).rejects.toBeInstanceOf(QueryError);
  });

  it("honors an already-aborted signal without mutating the demo report", async () => {
    const adapter = createDemoQueries([reports[0]]);
    const controller = new AbortController();
    controller.abort();
    await expect(
      adapter.updatePriority!(
        reports[0].id,
        { expectedVersion: reports[0].version, priority: "HIGH" },
        controller.signal,
      ),
    ).rejects.toMatchObject({ code: "cancelled" });
    expect(
      await adapter.getReport(reports[0].id, new AbortController().signal),
    ).toEqual(reports[0]);
  });

  it("rejects a version that cannot be incremented safely", async () => {
    const maximal = { ...reports[0], version: Number.MAX_SAFE_INTEGER };
    const adapter = createDemoQueries([maximal]);
    const signal = new AbortController().signal;
    await expect(
      adapter.updatePriority!(
        maximal.id,
        { expectedVersion: Number.MAX_SAFE_INTEGER, priority: "HIGH" },
        signal,
      ),
    ).rejects.toMatchObject({ code: "contract" });
    expect(await adapter.getReport(maximal.id, signal)).toEqual(maximal);
  });
});
