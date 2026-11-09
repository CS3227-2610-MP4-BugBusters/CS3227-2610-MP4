export const statuses = [
  "Submitted",
  "Assigned",
  "In progress",
  "Resolution proposed",
  "Closed",
] as const;
export const priorities = ["LOW", "MEDIUM", "HIGH", "CRITICAL"] as const;
export type Person = { id: string; displayName: string };
export type User = Person & {
  role: "MAINTAINER" | "DEVELOPER" | "REPORTER";
  active: boolean;
};
export type Summary = {
  id: string;
  title: string;
  status: (typeof statuses)[number];
  priority: (typeof priorities)[number];
  reporter: Person;
  assignee: Person | null;
  version: number;
  createdAt: string;
  updatedAt: string;
};
export type Entry = {
  id: string;
  author: Person;
  text: string;
  createdAt: string;
};
export type Detail = Summary & {
  description: string;
  reproductionSteps: string;
  expectedBehavior: string;
  actualBehavior: string;
  environment: string;
  notes: Entry[];
  responses: Entry[];
  comments: Entry[];
  checklist: { id: string; text: string; completed: boolean }[];
  currentProposal: (Entry & { version: number }) | null;
  history: {
    id: string;
    type: string;
    text: string;
    actor: Person;
    createdAt: string;
  }[];
};
export type Filters = {
  status?: (typeof statuses)[number];
  priority?: (typeof priorities)[number];
  assigneeId?: string;
  offset?: number;
  limit?: number;
};
export type Page = { items: Summary[]; total: number };
export type SummaryFilters = Pick<Filters, "status" | "priority" | "assigneeId">;
export type SummaryFinding = { text: string; reportIds: string[] };
export type SummaryResult = {
  capturedAt: string;
  filters: SummaryFilters;
  included: { id: string; version: number; truncatedFields: string[] }[];
  excluded: { id: string; reason: string }[];
  findings: {
    overview: SummaryFinding[];
    blockers: SummaryFinding[];
    missingInformation: SummaryFinding[];
  };
  requiresReview: true;
  providerMode: "none" | "deterministic" | "soc";
  promptVersion: "MNT-SUMMARY-1";
};
export type FailureCode =
  | "session"
  | "denied"
  | "missing"
  | "contract"
  | "network"
  | "cancelled"
  | "conflict"
  | "invalidState"
  | "validation";
export class QueryError extends Error {
  constructor(public code: FailureCode) {
    super(messages[code]);
  }
}
export const messages: Record<FailureCode, string> = {
  session: "Please sign in to continue.",
  denied: "Active Maintainer access is required.",
  missing: "This report is no longer available.",
  contract: "The service returned an unsupported response.",
  network: "The service is unavailable. Please retry.",
  cancelled: "Request cancelled.",
  conflict: "This report changed. Refresh it and reconcile your changes.",
  invalidState: "This action is no longer available for the report state.",
  validation: "Please check the entered values and try again.",
};
export interface Queries {
  getCurrentUser(signal: AbortSignal): Promise<User>;
  listReports(filters: Filters, signal: AbortSignal): Promise<Page>;
  listDevelopers(signal: AbortSignal): Promise<Person[]>;
  getReport(id: string, signal: AbortSignal): Promise<Detail>;
  summarizeQueue?(request: { filters: SummaryFilters; regenerate: boolean }, signal?: AbortSignal): Promise<SummaryResult>;
  updatePriority?(input: { reportId: string; expectedVersion: number; priority: (typeof priorities)[number] }, signal?: AbortSignal): Promise<void>;
  assignReport?(input: { reportId: string; expectedVersion: number; developerId: string; reason?: string }, signal?: AbortSignal): Promise<void>;
  returnResolution?(input: { reportId: string; expectedVersion: number; proposalId: string; proposalVersion: number; reason: string }, signal?: AbortSignal): Promise<void>;
  closeReport?(input: { reportId: string; expectedVersion: number; proposalId: string; proposalVersion: number; reviewed: true }, signal?: AbortSignal): Promise<void>;
  reopenReport?(input: { reportId: string; expectedVersion: number; reason: string }, signal?: AbortSignal): Promise<void>;
  addComment?(input: { reportId: string; expectedVersion: number; text: string }, signal?: AbortSignal): Promise<void>;
}
export const uuid =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const fail = (): never => {
  throw new QueryError("contract");
};
function obj(v: unknown): Record<string, unknown> {
  return v !== null && typeof v === "object" && !Array.isArray(v)
    ? (v as Record<string, unknown>)
    : fail();
}
function str(v: unknown, max = 20000): string {
  return typeof v === "string" && v.length <= max ? v : fail();
}
function id(v: unknown): string {
  const s = str(v, 36);
  return uuid.test(s) ? s : fail();
}
function num(v: unknown): number {
  return typeof v === "number" && Number.isSafeInteger(v) && v >= 0
    ? v
    : fail();
}
function bool(v: unknown): boolean {
  return typeof v === "boolean" ? v : fail();
}
function time(v: unknown): string {
  const s = str(v, 40);
  const parsed = new Date(s);
  return /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z$/.test(s) &&
    Number.isFinite(parsed.getTime()) &&
    parsed.toISOString().slice(0, 19) === s.slice(0, 19)
    ? s
    : fail();
}
function one<T extends string>(v: unknown, values: readonly T[]): T {
  return values.includes(v as T) ? (v as T) : fail();
}
function arr<T>(v: unknown, parse: (x: unknown) => T, max = 500): T[] {
  return Array.isArray(v) && v.length <= max ? v.map(parse) : fail();
}
export function parsePerson(v: unknown): Person {
  const o = obj(v);
  return { id: id(o.id), displayName: str(o.displayName, 200) };
}
export function parseUser(v: unknown): User {
  const o = obj(v);
  return {
    ...parsePerson(o),
    role: one(o.role, ["MAINTAINER", "DEVELOPER", "REPORTER"]),
    active: bool(o.active),
  };
}
export function parseSummary(v: unknown): Summary {
  const o = obj(v);
  return {
    id: id(o.id),
    title: str(o.title, 200),
    status: one(o.status, statuses),
    priority: one(o.priority, priorities),
    reporter: parsePerson(o.reporter),
    assignee: o.assignee === null ? null : parsePerson(o.assignee),
    version: num(o.version),
    createdAt: time(o.createdAt),
    updatedAt: time(o.updatedAt),
  };
}
function entry(v: unknown): Entry {
  const o = obj(v);
  return {
    id: id(o.id),
    author: parsePerson(o.author),
    text: str(o.text),
    createdAt: time(o.createdAt),
  };
}
export function parseDetail(v: unknown): Detail {
  const o = obj(v);
  return {
    ...parseSummary(o),
    description: str(o.description),
    reproductionSteps: str(o.reproductionSteps),
    expectedBehavior: str(o.expectedBehavior),
    actualBehavior: str(o.actualBehavior),
    environment: str(o.environment),
    notes: arr(o.notes, entry),
    responses: arr(o.responses, entry),
    comments: arr(o.comments, entry),
    checklist: arr(o.checklist, (x) => {
      const c = obj(x);
      return { id: id(c.id), text: str(c.text), completed: bool(c.completed) };
    }),
    currentProposal:
      o.currentProposal === null
        ? null
        : {
            ...entry(o.currentProposal),
            version: num(obj(o.currentProposal).version),
          },
    history: arr(o.history, (x) => {
      const h = obj(x);
      return {
        id: id(h.id),
        type: str(h.type, 200),
        text: str(h.text),
        actor: parsePerson(h.actor),
        createdAt: time(h.createdAt),
      };
    }),
  };
}
export function parsePage(v: unknown): Page {
  const o = obj(v),
    items = arr(o.items, parseSummary, 100),
    total = num(o.total);
  return total >= items.length ? { items, total } : fail();
}
export const parseDevelopers = (v: unknown) => arr(v, parsePerson);
export function checkedFilters(
  v: Filters,
): Required<Pick<Filters, "offset" | "limit">> & Filters {
  const o = obj(v);
  if (
    Object.keys(o).some(
      (k) =>
        !["status", "priority", "assigneeId", "offset", "limit"].includes(k),
    )
  )
    fail();
  const offset = o.offset === undefined ? 0 : num(o.offset),
    limit = o.limit === undefined ? 50 : num(o.limit);
  if (limit < 1 || limit > 100) fail();
  return {
    offset,
    limit,
    ...(o.status === undefined ? {} : { status: one(o.status, statuses) }),
    ...(o.priority === undefined
      ? {}
      : { priority: one(o.priority, priorities) }),
    ...(o.assigneeId === undefined ? {} : { assigneeId: id(o.assigneeId) }),
  };
}
