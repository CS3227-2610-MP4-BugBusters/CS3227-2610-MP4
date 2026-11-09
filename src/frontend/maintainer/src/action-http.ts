import { QueryError, parseSummary, uuid } from "./domain";
import type { Queries } from "./domain";

const DEADLINE_MS = 10_000;
const MAX_BODY = 65_536;
type Csrf = { token: string; headerName: "X-CSRF-TOKEN" };

/** Adds the versioned mutation transport to the ordinary read-only HTTP adapter. */
export function withHttpActions<T extends Queries>(queries: T, fetcher: typeof fetch = fetch): T & Required<Pick<Queries,
  "updatePriority" | "assignReport" | "returnResolution" | "closeReport" | "reopenReport" | "addComment">> {
  const bounded = async (response: Response, signal: AbortSignal): Promise<string> => {
    if (!response.body) throw new QueryError("contract");
    const reader = response.body.getReader();
    const decoder = new TextDecoder("utf-8", { fatal: true });
    let size = 0, result = "";
    const cancel = () => { void reader.cancel().catch(() => undefined); };
    signal.addEventListener("abort", cancel, { once: true });
    try {
      while (true) {
        if (signal.aborted) throw new QueryError("cancelled");
        const { done, value } = await reader.read();
        if (done) break;
        size += value.byteLength;
        if (size > MAX_BODY) { await reader.cancel(); throw new QueryError("contract"); }
        result += decoder.decode(value, { stream: true });
      }
      if (signal.aborted) throw new QueryError("cancelled");
      return result + decoder.decode();
    } catch (error) {
      if (error instanceof QueryError) throw error;
      throw new QueryError("contract");
    } finally { signal.removeEventListener("abort", cancel); reader.releaseLock(); }
  };
  const request = async (path: string, body: unknown, method: "POST" | "PUT", caller?: AbortSignal, expected?: { id: string; version: number; priority?: string; assigneeId?: string; status?: string; unassigned?: boolean }) => {
    const controller = new AbortController();
    let rejectAbort!: (reason: QueryError) => void;
    const aborted = new Promise<never>((_, reject) => { rejectAbort = reject; });
    const abort = () => { controller.abort(); rejectAbort(new QueryError("cancelled")); };
    if (caller?.aborted) throw new QueryError("cancelled");
    caller?.addEventListener("abort", abort, { once: true });
    const timer = globalThis.setTimeout(() => { controller.abort(); rejectAbort(new QueryError("network")); }, DEADLINE_MS);
    const signal = controller.signal;
    const perform = async () => {
      const csrfResponse = await fetcher("/api/auth/csrf", {
        method: "GET", credentials: "same-origin", cache: "no-store", signal,
        headers: { Accept: "application/json" },
      });
      if (signal.aborted) throw new QueryError("cancelled");
      if (!csrfResponse.ok) throw await responseError(csrfResponse, signal);
      let csrf: unknown;
      try { csrf = JSON.parse(await bounded(csrfResponse, signal)); } catch (error) {
        if (error instanceof QueryError) throw error;
        throw new QueryError("contract");
      }
      if (!csrf || typeof csrf !== "object" || Array.isArray(csrf) ||
          typeof (csrf as Record<string, unknown>).token !== "string" ||
          !(csrf as Record<string, unknown>).token || ((csrf as Record<string, unknown>).token as string).length > 2048 ||
          (csrf as Record<string, unknown>).headerName !== "X-CSRF-TOKEN") throw new QueryError("contract");
      const token = (csrf as Csrf).token;
      if (signal.aborted) throw new QueryError("cancelled");
      const response = await fetcher(path, {
        method, credentials: "same-origin", cache: "no-store", signal,
        headers: { Accept: "application/json", "Content-Type": "application/json", "X-CSRF-TOKEN": token },
        body: JSON.stringify(body),
      });
      if (signal.aborted) throw new QueryError("cancelled");
      if (!response.ok) throw await responseError(response, signal);
      let value: unknown;
      try { value = JSON.parse(await bounded(response, signal)); } catch (error) {
        if (error instanceof QueryError) throw error;
        throw new QueryError("contract");
      }
      if (path.endsWith("/comments")) validateCommentResult(value, expected?.version);
      else {
        const summary = parseSummary(value);
        if (expected && (summary.id !== expected.id || summary.version !== expected.version + 1 ||
            (expected.priority !== undefined && summary.priority !== expected.priority) ||
            (expected.assigneeId !== undefined && summary.assignee?.id !== expected.assigneeId) ||
            (expected.status !== undefined && summary.status !== expected.status) ||
            (expected.unassigned && summary.assignee !== null))) throw new QueryError("contract");
      }
    };
    try {
      await Promise.race([perform(), aborted]);
    } catch (error) {
      if (caller?.aborted) throw new QueryError("cancelled");
      if (error instanceof QueryError) throw error;
      if (signal.aborted) throw new QueryError("network");
      throw new QueryError("network");
    } finally { globalThis.clearTimeout(timer); caller?.removeEventListener("abort", abort); }
  };
  const reportPath = (id: string, suffix: string) => {
    if (!uuid.test(id)) throw new QueryError("contract");
    return `/api/maintainer/reports/${encodeURIComponent(id)}${suffix}`;
  };
  const version = (value: number) => {
    if (!Number.isSafeInteger(value) || value < 0) throw new QueryError("validation");
  };
  const text = (value: string, max: number) => {
    const clean = value.trim();
    if ([...clean].length < 1 || [...clean].length > max || /[\u0000-\u0008\u000b\u000c\u000d-\u001f\u007f-\u009f]/.test(clean)) throw new QueryError("validation");
    return clean;
  };
  return Object.assign(queries, {
    updatePriority: async (v: { reportId: string; expectedVersion: number; priority: "LOW" | "MEDIUM" | "HIGH" | "CRITICAL" }, s?: AbortSignal) => { version(v.expectedVersion); if (!["LOW", "MEDIUM", "HIGH", "CRITICAL"].includes(v.priority)) throw new QueryError("validation"); return request(reportPath(v.reportId, "/priority"), { expectedVersion: v.expectedVersion, priority: v.priority }, "PUT", s, { id: v.reportId, version: v.expectedVersion, priority: v.priority }); },
    assignReport: async (v: { reportId: string; expectedVersion: number; developerId: string; reason?: string }, s?: AbortSignal) => { version(v.expectedVersion); if (!uuid.test(v.developerId)) throw new QueryError("validation"); const reason = v.reason === undefined ? undefined : text(v.reason, 1000); return request(reportPath(v.reportId, "/assignment"), { expectedVersion: v.expectedVersion, developerId: v.developerId, ...(reason === undefined ? {} : { reason }) }, "POST", s, { id: v.reportId, version: v.expectedVersion, assigneeId: v.developerId, status: "Assigned" }); },
    returnResolution: async (v: { reportId: string; expectedVersion: number; proposalId: string; proposalVersion: number; reason: string }, s?: AbortSignal) => { version(v.expectedVersion); version(v.proposalVersion); if (!uuid.test(v.proposalId)) throw new QueryError("validation"); return request(reportPath(v.reportId, "/resolution/return"), { expectedVersion: v.expectedVersion, proposalId: v.proposalId, proposalVersion: v.proposalVersion, reason: text(v.reason, 1000) }, "POST", s, { id: v.reportId, version: v.expectedVersion, status: "In progress" }); },
    closeReport: async (v: { reportId: string; expectedVersion: number; proposalId: string; proposalVersion: number; reviewed: true }, s?: AbortSignal) => { version(v.expectedVersion); version(v.proposalVersion); if (!uuid.test(v.proposalId) || v.reviewed !== true) throw new QueryError("validation"); return request(reportPath(v.reportId, "/close"), { expectedVersion: v.expectedVersion, proposalId: v.proposalId, proposalVersion: v.proposalVersion, reviewed: true }, "POST", s, { id: v.reportId, version: v.expectedVersion, status: "Closed" }); },
    reopenReport: async (v: { reportId: string; expectedVersion: number; reason: string }, s?: AbortSignal) => { version(v.expectedVersion); return request(reportPath(v.reportId, "/reopen"), { expectedVersion: v.expectedVersion, reason: text(v.reason, 1000) }, "POST", s, { id: v.reportId, version: v.expectedVersion, status: "Submitted", unassigned: true }); },
    addComment: async (v: { reportId: string; expectedVersion: number; text: string }, s?: AbortSignal) => { version(v.expectedVersion); return request(reportPath(v.reportId, "/comments"), { expectedVersion: v.expectedVersion, text: text(v.text, 4000) }, "POST", s, { id: v.reportId, version: v.expectedVersion }); },
  });
}

async function responseError(response: Response, signal: AbortSignal): Promise<QueryError> {
  if (response.status === 401) return new QueryError("session");
  if (response.status === 403) return new QueryError("denied");
  if (response.status === 400 || response.status === 422) return new QueryError("validation");
  let code: unknown;
  if (response.status === 409) {
    try {
      const value: unknown = JSON.parse(await readErrorBody(response, signal));
      if (value && typeof value === "object" && !Array.isArray(value)) code = (value as Record<string, unknown>).code;
    } catch { /* Use only the HTTP status when the safe error envelope is malformed. */ }
    return new QueryError(code === "INVALID_STATE" ? "invalidState" : "conflict");
  }
  return new QueryError("network");
}

async function readErrorBody(response: Response, signal: AbortSignal): Promise<string> {
  if (!response.body) return "";
  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8", { fatal: true });
  let size = 0, text = "";
  const cancel = () => { void reader.cancel().catch(() => undefined); };
  signal.addEventListener("abort", cancel, { once: true });
  try {
    while (true) {
      if (signal.aborted) return "";
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > 4096) { await reader.cancel(); return ""; }
      text += decoder.decode(value, { stream: true });
    }
    return text + decoder.decode();
  } finally { signal.removeEventListener("abort", cancel); reader.releaseLock(); }
}

function validateCommentResult(value: unknown, expectedVersion?: number): void {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new QueryError("contract");
  const result = value as Record<string, unknown>, comment = result.comment;
  if (!Number.isSafeInteger(result.reportVersion) || (result.reportVersion as number) < 0 || (expectedVersion !== undefined && result.reportVersion !== expectedVersion + 1) || !comment || typeof comment !== "object" || Array.isArray(comment)) throw new QueryError("contract");
  const item = comment as Record<string, unknown>, author = item.author;
  if (typeof item.id !== "string" || !uuid.test(item.id) || typeof item.text !== "string" || !item.text.trim() || [...item.text].length > 4000 || /[\u0000-\u0008\u000b\u000c\u000d-\u001f\u007f-\u009f]/.test(item.text) || typeof item.createdAt !== "string" || !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z$/.test(item.createdAt) || !Number.isFinite(Date.parse(item.createdAt)) || new Date(item.createdAt).toISOString().slice(0, 19) !== item.createdAt.slice(0, 19) || !author || typeof author !== "object" || Array.isArray(author)) throw new QueryError("contract");
  const person = author as Record<string, unknown>;
  if (typeof person.id !== "string" || !uuid.test(person.id) || typeof person.displayName !== "string" || person.displayName.length > 200) throw new QueryError("contract");
}
