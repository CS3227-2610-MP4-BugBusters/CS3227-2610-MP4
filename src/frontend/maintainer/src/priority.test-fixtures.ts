import { createDemoQueries, fixtureId, reports } from "./demo";
import type { Detail, Queries, Summary } from "./domain";

/** Test-local extension proposed for the Maintainer-owned versioned write seam. */
export type PriorityQueries = Queries & {
  updatePriority(
    reportId: string,
    input: { expectedVersion: number; priority: Summary["priority"] },
    signal: AbortSignal,
  ): Promise<Summary>;
};

export const priorityReport: Detail = {
  ...reports[0],
  id: fixtureId(1),
  title: "Priority workflow report",
  status: "Submitted",
  priority: "MEDIUM",
  version: 7,
  history: [...reports[0].history],
};

export function makePriorityQueries(
  updatePriority: PriorityQueries["updatePriority"],
): PriorityQueries {
  const base = createDemoQueries([priorityReport]);
  return { ...base, updatePriority };
}
