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
  input?: Detail[],
  includeActions = input !== undefined,
): Queries {
  const source = input ?? reports.map((report) => structuredClone(report));
  const check = (s: AbortSignal) => {
    if (s.aborted) throw new QueryError("cancelled");
  };
  const find = (id: string) => {
    const report = source.find((r) => r.id === id);
    if (!report) throw new QueryError("missing");
    return report;
  };
  const write = (id: string, version: number) => {
    const r = find(id);
    if (!Number.isSafeInteger(version) || version !== r.version) throw new QueryError("conflict");
    r.version++;
    r.updatedAt = new Date().toISOString();
    return r;
  };
  const history = (r: Detail, type: string, text: string) => r.history.push({
    id: fixtureId(10000 + r.history.length + r.version), type, text, actor: author, createdAt: new Date().toISOString(),
  });
  const retainProposal = (r: Detail) => {
    const proposal = r.currentProposal;
    if (proposal) history(r, "Proposal retained", JSON.stringify({ id: proposal.id, version: proposal.version, text: proposal.text, author: proposal.author, createdAt: proposal.createdAt }));
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
    ...(includeActions ? {
    async updatePriority({ reportId, expectedVersion, priority }: { reportId: string; expectedVersion: number; priority: "LOW" | "MEDIUM" | "HIGH" | "CRITICAL" }) {
      if (!( ["LOW", "MEDIUM", "HIGH", "CRITICAL"] as string[]).includes(priority)) throw new QueryError("validation");
      const r = write(reportId, expectedVersion);
      r.priority = priority; history(r, "Priority changed", priority);
    },
    async assignReport({ reportId, expectedVersion, developerId, reason }: { reportId: string; expectedVersion: number; developerId: string; reason?: string }) {
      const before = find(reportId);
      if (before.status !== "Submitted" && before.status !== "Assigned" && before.status !== "In progress") throw new QueryError("invalidState");
      const target = developers.find((d) => d.id === developerId);
      if (!target) throw new QueryError("validation");
      const reassignment = before.status !== "Submitted";
      if (reassignment && before.assignee?.id === developerId) throw new QueryError("validation");
      const text = reason?.trim() ?? "";
      if (reassignment && (!text || [...text].length > 1000 || /[\u0000-\u0008\u000b\u000c\u000d-\u001f\u007f]/.test(text))) throw new QueryError("validation");
      const r = write(reportId, expectedVersion);
      r.assignee = target; r.status = "Assigned"; history(r, reassignment ? "Reassigned" : "Assigned", reassignment ? text : target.displayName);
    },
    async returnResolution({ reportId, expectedVersion, proposalId, proposalVersion, reason }: { reportId: string; expectedVersion: number; proposalId: string; proposalVersion: number; reason: string }) {
      const before = find(reportId);
      if (before.status !== "Resolution proposed" || before.currentProposal?.id !== proposalId || before.currentProposal.version !== proposalVersion) throw new QueryError("invalidState");
      if (!reason.trim() || [...reason.trim()].length > 1000 || /[\u0000-\u0008\u000b\u000c\u000d-\u001f\u007f]/.test(reason.trim())) throw new QueryError("validation");
      const r = write(reportId, expectedVersion); retainProposal(r); r.status = "In progress"; r.currentProposal = null; history(r, "Resolution returned", reason.trim());
    },
    async closeReport({ reportId, expectedVersion, proposalId, proposalVersion, reviewed }: { reportId: string; expectedVersion: number; proposalId: string; proposalVersion: number; reviewed: true }) {
      const before = find(reportId);
      if (before.status !== "Resolution proposed" || before.currentProposal?.id !== proposalId || before.currentProposal.version !== proposalVersion || reviewed !== true) throw new QueryError("invalidState");
      const r = write(reportId, expectedVersion); r.status = "Closed"; history(r, "Closed", "Proposal reviewed and accepted.");
    },
    async reopenReport({ reportId, expectedVersion, reason }: { reportId: string; expectedVersion: number; reason: string }) {
      if (find(reportId).status !== "Closed") throw new QueryError("invalidState");
      if (!reason.trim() || [...reason.trim()].length > 1000 || /[\u0000-\u0008\u000b\u000c\u000d-\u001f\u007f]/.test(reason.trim())) throw new QueryError("validation");
      const r = write(reportId, expectedVersion); retainProposal(r); r.status = "Submitted"; r.assignee = null; r.currentProposal = null; history(r, "Reopened", reason.trim());
    },
    async addComment({ reportId, expectedVersion, text }: { reportId: string; expectedVersion: number; text: string }) {
      const clean = text.trim();
      if (!clean || [...clean].length > 4000 || /[\u0000-\u0008\u000b\u000c\u000d-\u001f\u007f]/.test(clean)) throw new QueryError("validation");
      const r = write(reportId, expectedVersion); r.comments.push({ id: fixtureId(20000 + r.comments.length + r.version), author, text: clean, createdAt: r.updatedAt }); history(r, "Comment added", clean);
    } } : {}),
  };
}
