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
  const request = async (path: string, body: unknown, method: "POST" | "PUT", caller?: AbortSignal) => {
    const controller = new AbortController();
    const abort = () => controller.abort();
    if (caller?.aborted) throw new QueryError("cancelled");
    caller?.addEventListener("abort", abort, { once: true });
    const timer = globalThis.setTimeout(abort, DEADLINE_MS);
    const signal = controller.signal;
    try {
      const csrfResponse = await fetcher("/api/auth/csrf", {
        method: "GET", credentials: "same-origin", cache: "no-store", signal,
        headers: { Accept: "application/json" },
      });
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
      const response = await fetcher(path, {
        method, credentials: "same-origin", cache: "no-store", signal,
        headers: { Accept: "application/json", "Content-Type": "application/json", "X-CSRF-TOKEN": token },
        body: JSON.stringify(body),
      });
      if (!response.ok) throw await responseError(response, signal);
      let value: unknown;
      try { value = JSON.parse(await bounded(response, signal)); } catch (error) {
        if (error instanceof QueryError) throw error;
        throw new QueryError("contract");
      }
      if (path.endsWith("/comments")) validateCommentResult(value);
      else parseSummary(value);
    } catch (error) {
      if (caller?.aborted) throw new QueryError("cancelled");
      if (signal.aborted) throw new QueryError("network");
      if (error instanceof QueryError) throw error;
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
    if ([...clean].length < 1 || [...clean].length > max || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(clean)) throw new QueryError("validation");
    return clean;
  };
  return Object.assign(queries, {
    updatePriority: async (v: { reportId: string; expectedVersion: number; priority: "LOW" | "MEDIUM" | "HIGH" | "CRITICAL" }, s?: AbortSignal) => { version(v.expectedVersion); if (!["LOW", "MEDIUM", "HIGH", "CRITICAL"].includes(v.priority)) throw new QueryError("validation"); return request(reportPath(v.reportId, "/priority"), { expectedVersion: v.expectedVersion, priority: v.priority }, "PUT", s); },
    assignReport: async (v: { reportId: string; expectedVersion: number; developerId: string; reason?: string }, s?: AbortSignal) => { version(v.expectedVersion); if (!uuid.test(v.developerId)) throw new QueryError("validation"); const reason = v.reason === undefined ? undefined : text(v.reason, 1000); return request(reportPath(v.reportId, "/assignment"), { expectedVersion: v.expectedVersion, developerId: v.developerId, ...(reason === undefined ? {} : { reason }) }, "POST", s); },
    returnResolution: async (v: { reportId: string; expectedVersion: number; proposalId: string; proposalVersion: number; reason: string }, s?: AbortSignal) => { version(v.expectedVersion); version(v.proposalVersion); if (!uuid.test(v.proposalId)) throw new QueryError("validation"); return request(reportPath(v.reportId, "/resolution/return"), { expectedVersion: v.expectedVersion, proposalId: v.proposalId, proposalVersion: v.proposalVersion, reason: text(v.reason, 1000) }, "POST", s); },
    closeReport: async (v: { reportId: string; expectedVersion: number; proposalId: string; proposalVersion: number; reviewed: true }, s?: AbortSignal) => { version(v.expectedVersion); version(v.proposalVersion); if (!uuid.test(v.proposalId) || v.reviewed !== true) throw new QueryError("validation"); return request(reportPath(v.reportId, "/close"), { expectedVersion: v.expectedVersion, proposalId: v.proposalId, proposalVersion: v.proposalVersion, reviewed: true }, "POST", s); },
    reopenReport: async (v: { reportId: string; expectedVersion: number; reason: string }, s?: AbortSignal) => { version(v.expectedVersion); return request(reportPath(v.reportId, "/reopen"), { expectedVersion: v.expectedVersion, reason: text(v.reason, 1000) }, "POST", s); },
    addComment: async (v: { reportId: string; expectedVersion: number; text: string }, s?: AbortSignal) => { version(v.expectedVersion); return request(reportPath(v.reportId, "/comments"), { expectedVersion: v.expectedVersion, text: text(v.text, 4000) }, "POST", s); },
  });
}

async function responseError(response: Response, signal: AbortSignal): Promise<QueryError> {
  let code: unknown;
  try {
    const value: unknown = JSON.parse(await readErrorBody(response, signal));
    if (value && typeof value === "object" && !Array.isArray(value)) code = (value as Record<string, unknown>).code;
  } catch { /* Use only the HTTP status when the safe error envelope is malformed. */ }
  const status = response.status;
  if (status === 401) return new QueryError("session");
  if (status === 403) return new QueryError("denied");
  if (status === 409) return new QueryError(code === "INVALID_STATE" ? "invalidState" : "conflict");
  if (status === 400 || status === 422) return new QueryError("validation");
  return new QueryError("network");
}

async function readErrorBody(response: Response, signal: AbortSignal): Promise<string> {
  if (!response.body) return "";
  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8", { fatal: true });
  let size = 0, text = "";
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
  } finally { reader.releaseLock(); }
}

function validateCommentResult(value: unknown): void {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new QueryError("contract");
  const result = value as Record<string, unknown>, comment = result.comment;
  if (!Number.isSafeInteger(result.reportVersion) || (result.reportVersion as number) < 0 || !comment || typeof comment !== "object" || Array.isArray(comment)) throw new QueryError("contract");
  const item = comment as Record<string, unknown>, author = item.author;
  if (typeof item.id !== "string" || !uuid.test(item.id) || typeof item.text !== "string" || item.text.length > 20_000 || typeof item.createdAt !== "string" || !Number.isFinite(Date.parse(item.createdAt)) || !author || typeof author !== "object" || Array.isArray(author)) throw new QueryError("contract");
  const person = author as Record<string, unknown>;
  if (typeof person.id !== "string" || !uuid.test(person.id) || typeof person.displayName !== "string" || person.displayName.length > 200) throw new QueryError("contract");
}
