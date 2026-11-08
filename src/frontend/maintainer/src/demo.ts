import {
  checkedFilters,
  QueryError,
  uuid,
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
  // Each adapter owns a private copy; demo writes never mutate the exported fixtures.
  const state = source.map(cloneDetail);
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
      const items = state
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
        items: items.slice(q.offset, q.offset + q.limit).map(summary),
        total: items.length,
      };
    },
    async getReport(id, s) {
      check(s);
      const r = state.find((x) => x.id === id);
      if (!r) throw new QueryError("missing");
      return cloneDetail(r);
    },
    async updatePriority(id, input, s) {
      check(s);
      if (
        typeof id !== "string" ||
        !uuid.test(id) ||
        !Number.isSafeInteger(input?.expectedVersion) ||
        input.expectedVersion < 0 ||
        !(["LOW", "MEDIUM", "HIGH", "CRITICAL"] as const).includes(
          input?.priority,
        )
      )
        throw new QueryError("contract");
      const report = state.find((x) => x.id === id);
      if (!report) throw new QueryError("missing");
      if (report.version !== input.expectedVersion) {
        const conflict = new Error(
          "The report changed. Reload it and reconcile your change.",
        ) as Error & {
          status: number;
          code: string;
        };
        conflict.status = 409;
        conflict.code = "STALE_VERSION";
        throw conflict;
      }
      if (report.version >= Number.MAX_SAFE_INTEGER)
        throw new QueryError("contract");
      check(s);
      const before = report.priority;
      const updatedAt = new Date().toISOString();
      report.priority = input.priority;
      report.version += 1;
      report.updatedAt = updatedAt;
      report.history = [
        ...report.history,
        {
          id: crypto.randomUUID(),
          type: "Priority changed",
          text: `Priority changed from ${before} to ${input.priority}.`,
          actor: { ...author },
          createdAt: updatedAt,
        },
      ];
      check(s);
      return summary(report);
    },
  };
}

function summary(report: Detail) {
  const {
    id,
    title,
    status,
    priority,
    reporter,
    assignee,
    version,
    createdAt,
    updatedAt,
  } = report;
  return {
    id,
    title,
    status,
    priority,
    reporter: { ...reporter },
    assignee: assignee ? { ...assignee } : null,
    version,
    createdAt,
    updatedAt,
  };
}

function cloneDetail(report: Detail): Detail {
  return {
    ...report,
    reporter: { ...report.reporter },
    assignee: report.assignee ? { ...report.assignee } : null,
    notes: report.notes.map((entry) => ({
      ...entry,
      author: { ...entry.author },
    })),
    responses: report.responses.map((entry) => ({
      ...entry,
      author: { ...entry.author },
    })),
    comments: report.comments.map((entry) => ({
      ...entry,
      author: { ...entry.author },
    })),
    checklist: report.checklist.map((item) => ({ ...item })),
    currentProposal: report.currentProposal
      ? {
          ...report.currentProposal,
          author: { ...report.currentProposal.author },
        }
      : null,
    history: report.history.map((entry) => ({
      ...entry,
      actor: { ...entry.actor },
    })),
  };
}
