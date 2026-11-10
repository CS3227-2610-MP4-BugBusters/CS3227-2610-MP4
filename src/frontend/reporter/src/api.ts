// HTTP client for the Reporter app. Paths and shapes follow
// workflow/reporter/reporter-reports-api-contract.md (revision 4) and
// workflow/reporter/report-discussion-api-contract.md (revision 1).

export type User = { id: string; displayName: string; role: string; active: boolean };

export type ReportStatus = "SUBMITTED" | "ASSIGNED" | "IN_PROGRESS" | "RESOLUTION_PROPOSED" | "CLOSED";

export type ReportSummary = {
  id: string;
  title: string;
  status: string;
  createdAt: string;
  updatedAt: string;
};

export type ReportDetail = ReportSummary & { description: string; version: number };

export type Page<T> = { items: T[]; page: number; pageSize: number; totalItems: number };

export type Comment = {
  id: string;
  body: string;
  author: { id: string; displayName: string | null; role: string };
  createdAt: string;
};

/** A non-2xx response. `code` is the contract's error code when the body had one. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly retryAfterSeconds: number | null = null,
  ) {
    super(code);
  }
}

const json = { Accept: "application/json" };

async function call(path: string, init: RequestInit = {}, signal?: AbortSignal): Promise<unknown> {
  const response = await fetch(path, {
    credentials: "same-origin",
    cache: "no-store",
    ...init,
    headers: { ...json, ...(init.headers ?? {}) },
    ...(signal ? { signal } : {}),
  });
  let body: unknown = null;
  try {
    body = await response.json();
  } catch {
    body = null;
  }
  if (!response.ok) {
    const code = isRecord(body) && typeof body.code === "string" ? body.code : `HTTP_${response.status}`;
    const retry = Number.parseInt(response.headers.get("Retry-After") ?? "", 10);
    throw new ApiError(response.status, code, Number.isFinite(retry) ? retry : null);
  }
  return body;
}

async function csrfHeaders(): Promise<Record<string, string>> {
  const value = await call("/api/auth/csrf");
  if (!isRecord(value) || typeof value.token !== "string" || value.headerName !== "X-CSRF-TOKEN") {
    throw new ApiError(0, "CSRF_UNAVAILABLE");
  }
  return { "X-CSRF-TOKEN": value.token };
}

async function send(path: string, method: string, body: unknown): Promise<unknown> {
  return call(path, {
    method,
    headers: { ...(await csrfHeaders()), "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

export async function currentUser(signal?: AbortSignal): Promise<User> {
  const value = await call("/api/auth/me", {}, signal);
  if (
    !isRecord(value) ||
    typeof value.id !== "string" ||
    typeof value.displayName !== "string" ||
    typeof value.role !== "string" ||
    typeof value.active !== "boolean"
  ) {
    throw new ApiError(0, "BAD_RESPONSE");
  }
  return { id: value.id, displayName: value.displayName, role: value.role, active: value.active };
}

export async function signIn(username: string, password: string): Promise<void> {
  await call("/api/auth/login", {
    method: "POST",
    headers: { ...(await csrfHeaders()), "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ username, password }).toString(),
  });
}

export async function signOut(): Promise<void> {
  await call("/api/auth/logout", { method: "POST", headers: await csrfHeaders() });
}

const reports = "/api/reporter/reports";

export async function listReports(page: number, pageSize: number, signal?: AbortSignal): Promise<Page<ReportSummary>> {
  const value = await call(`${reports}?page=${page}&pageSize=${pageSize}`, {}, signal);
  return parsePage(value, parseSummary);
}

export async function readReport(id: string, signal?: AbortSignal): Promise<ReportDetail> {
  return parseDetail(await call(`${reports}/${encodeURIComponent(id)}`, {}, signal));
}

export async function createReport(title: string, description: string): Promise<ReportDetail> {
  return parseDetail(await send(reports, "POST", { title, description }));
}

export async function editReport(id: string, title: string, description: string, version: number): Promise<ReportDetail> {
  return parseDetail(await send(`${reports}/${encodeURIComponent(id)}`, "PUT", { title, description, version }));
}

export async function listComments(id: string, page: number, signal?: AbortSignal): Promise<Page<Comment>> {
  const value = await call(`${reports}/${encodeURIComponent(id)}/comments?page=${page}&pageSize=100`, {}, signal);
  return parsePage(value, parseComment);
}

export async function postComment(id: string, body: string): Promise<Comment> {
  const value = parseComment(await send(`${reports}/${encodeURIComponent(id)}/comments`, "POST", { body }));
  if (value === null) throw new ApiError(0, "BAD_RESPONSE");
  return value;
}

function parsePage<T>(value: unknown, item: (v: unknown) => T | null): Page<T> {
  if (
    !isRecord(value) ||
    !Array.isArray(value.items) ||
    typeof value.page !== "number" ||
    typeof value.pageSize !== "number" ||
    typeof value.totalItems !== "number"
  ) {
    throw new ApiError(0, "BAD_RESPONSE");
  }
  const items = value.items.map(item);
  if (items.some((entry) => entry === null)) throw new ApiError(0, "BAD_RESPONSE");
  return { items: items as T[], page: value.page, pageSize: value.pageSize, totalItems: value.totalItems };
}

function parseSummary(value: unknown): ReportSummary | null {
  if (
    !isRecord(value) ||
    typeof value.id !== "string" ||
    typeof value.title !== "string" ||
    typeof value.status !== "string" ||
    typeof value.createdAt !== "string" ||
    typeof value.updatedAt !== "string"
  ) {
    return null;
  }
  return { id: value.id, title: value.title, status: value.status, createdAt: value.createdAt, updatedAt: value.updatedAt };
}

function parseDetail(value: unknown): ReportDetail {
  const summary = parseSummary(value);
  if (
    summary === null ||
    !isRecord(value) ||
    typeof value.description !== "string" ||
    typeof value.version !== "number"
  ) {
    throw new ApiError(0, "BAD_RESPONSE");
  }
  return { ...summary, description: value.description, version: value.version };
}

function parseComment(value: unknown): Comment | null {
  if (
    !isRecord(value) ||
    typeof value.id !== "string" ||
    typeof value.body !== "string" ||
    typeof value.createdAt !== "string" ||
    !isRecord(value.author) ||
    typeof value.author.id !== "string" ||
    typeof value.author.role !== "string" ||
    !(typeof value.author.displayName === "string" || value.author.displayName === null)
  ) {
    return null;
  }
  return {
    id: value.id,
    body: value.body,
    createdAt: value.createdAt,
    author: { id: value.author.id, displayName: value.author.displayName, role: value.author.role },
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
