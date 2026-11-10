import type { Queries } from "./domain";
import { createDemoQueries, reports } from "./demo";

export type ActionQueries = Queries & {
  updatePriority(input: { reportId: string; expectedVersion: number; priority: "LOW" | "MEDIUM" | "HIGH" | "CRITICAL" }): Promise<void>;
  assignReport(input: { reportId: string; expectedVersion: number; developerId: string; reason?: string }): Promise<void>;
  returnResolution(input: { reportId: string; expectedVersion: number; proposalId: string; proposalVersion: number; reason: string }): Promise<void>;
  closeReport(input: { reportId: string; expectedVersion: number; proposalId: string; proposalVersion: number; reviewed: true }): Promise<void>;
  reopenReport(input: { reportId: string; expectedVersion: number; reason: string }): Promise<void>;
  addComment(input: { reportId: string; expectedVersion: number; text: string }): Promise<void>;
};

export function actionFixture(report: typeof reports[number] | readonly (typeof reports[number])[] = reports[0]) {
  const source = (Array.isArray(report) ? report : [report]).map((item) => structuredClone(item));
  const base = createDemoQueries(source);
  const calls = {
    updatePriority: [] as unknown[], assignReport: [] as unknown[],
    returnResolution: [] as unknown[], closeReport: [] as unknown[],
    reopenReport: [] as unknown[], addComment: [] as unknown[],
  };
  const queries = Object.assign({}, base, Object.fromEntries(Object.keys(calls).map((key) => [key, async (input: unknown) => { calls[key as keyof typeof calls].push(input); }])));
  return { queries: queries as unknown as ActionQueries, calls, source };
}
