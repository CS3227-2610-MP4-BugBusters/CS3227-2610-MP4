import {
  checkedFilters,
  QueryError,
  type Queries,
  type Detail,
  type Person,
} from "./domain";
export const fixtureId = (n: number) =>
  `00000000-0000-4000-8000-${String(n).padStart(12, "0")}`;
export const developers: Person[] = [
  { id: fixtureId(901), displayName: "Alex Tan" },
  { id: fixtureId(902), displayName: "Sam Lee" },
];
const author = { id: fixtureId(900), displayName: "Jamie Ng" };
export const reports: Detail[] = Array.from({ length: 63 }, (_, i) => {
  const stamp = "2026-11-07T02:00:00Z";
  const item = {
    id: fixtureId(i + 1),
    author,
    text: "Investigation evidence <img src=x onerror=alert(1)>",
    createdAt: stamp,
  };
  return {
    id: fixtureId(i + 1),
    title: `Report ${i + 1}: ${["Login delay", "Search results", "Export failure"][i % 3]}`,
    status: (
      [
        "Submitted",
        "Assigned",
        "In progress",
        "Resolution proposed",
        "Closed",
      ] as const
    )[i % 5],
    priority: (["LOW", "MEDIUM", "HIGH", "CRITICAL"] as const)[i % 4],
    reporter: author,
    assignee: i % 3 === 0 ? null : developers[i % 2],
    version: i,
    createdAt: stamp,
    updatedAt: stamp,
    description: "The reported behavior needs investigation.",
    reproductionSteps: "Open the page. Repeat the action.",
    expectedBehavior: "The operation completes.",
    actualBehavior: "The operation stalls.",
    environment: "Synthetic browser / test environment",
    notes: [item],
    responses: [{ ...item, text: "Additional source information." }],
    comments: [{ ...item, text: "Please inspect the request timing." }],
    checklist: [
      {
        id: fixtureId(i + 1),
        text: "Reproduce the reported behavior",
        completed: false,
      },
    ],
    currentProposal:
      i % 5 === 3
        ? { ...item, text: "Proposed resolution for review.", version: 1 }
        : null,
    history: [
      {
        id: fixtureId(i + 1),
        type: "Submitted",
        text: "Report submitted.",
        actor: author,
        createdAt: stamp,
      },
    ],
  };
});
export function createDemoQueries(
  source: readonly Detail[] = reports,
): Queries {
  const check = (s: AbortSignal) => {
    if (s.aborted) throw new QueryError("cancelled");
  };
  return {
    async getCurrentUser(s) {
      check(s);
      return { ...author, role: "MAINTAINER", active: true };
    },
    async listDevelopers(s) {
      check(s);
      return developers;
    },
    async listReports(f, s) {
      check(s);
      const q = checkedFilters(f);
      const items = source
        .filter(
          (r) =>
            (!q.status || r.status === q.status) &&
            (!q.priority || r.priority === q.priority) &&
            (!q.assigneeId || r.assignee?.id === q.assigneeId),
        )
        .sort(
          (a, b) =>
            Date.parse(b.updatedAt) - Date.parse(a.updatedAt) ||
            a.id.localeCompare(b.id),
        );
      return {
        items: items.slice(q.offset, q.offset + q.limit),
        total: items.length,
      };
    },
    async getReport(id, s) {
      check(s);
      const r = source.find((x) => x.id === id);
      if (!r) throw new QueryError("missing");
      return r;
    },
  };
}
