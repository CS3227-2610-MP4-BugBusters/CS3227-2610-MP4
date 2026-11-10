import { createElement, useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { DeveloperChecklist } from "./DeveloperChecklist";
import { DeveloperPrivateNotes } from "./DeveloperPrivateNotes";
import { DeveloperNotifications } from "./DeveloperNotifications";
import { createRoleWorkspace } from "../../shared/RoleWorkspace";

type User = {
  id: string;
  displayName: string;
  role: string;
  active: boolean;
};

type Report = { id: string; title: string; status: string };
type ReportDetail = Report & { description: string; version?: number; returnReason?: string | null };
type ReportsPage = { items: Report[]; page: number; pageSize: number; totalItems: number };
type SessionState = "checking" | "signed-out" | "active" | "denied" | "error";
const reportPageSize = 20;
const reportStatuses = [
  "SUBMITTED",
  "ASSIGNED",
  "IN_PROGRESS",
  "RESOLUTION_PROPOSED",
  "CLOSED",
] as const;

const currentUserPath = "/api/auth/me";
const csrfPath = "/api/auth/csrf";
const loginPath = "/api/auth/login";
const logoutPath = "/api/auth/logout";
const reportsPath = "/api/developer/reports";
const jsonHeaders = { Accept: "application/json" };

export function DeveloperApp() {
  const [sessionState, setSessionState] = useState<SessionState>("checking");
  const [user, setUser] = useState<User | null>(null);
  const [reports, setReports] = useState<Report[]>([]);
  const [reportPage, setReportPage] = useState({ page: 1, pageSize: reportPageSize, totalItems: 0 });
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [statusFilter, setStatusFilter] = useState("");
  const [page, setPage] = useState(1);
  const [listNavigated, setListNavigated] = useState(false);
  const [selectedReportId, setSelectedReportId] = useState("");
  const [selectedReportTitle, setSelectedReportTitle] = useState("");
  const [detail, setDetail] = useState<ReportDetail | null>(null);
  const [detailState, setDetailState] = useState<"loading" | "ready" | "error">("loading");
  const [listState, setListState] = useState<"loading" | "ready" | "error">("loading");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [starting, setStarting] = useState(false);
  const [resolution, setResolution] = useState("");
  const [submittingResolution, setSubmittingResolution] = useState(false);
  const [startedVersion, setStartedVersion] = useState<{ reportId: string; previous: number; current: number }>();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [sessionAttempt, setSessionAttempt] = useState(0);
  const [listAttempt, setListAttempt] = useState(0);
  const resolutionController = useRef<AbortController | null>(null);
  const refreshSequence = useRef(0);
  const currentView = useRef("");
  currentView.current = JSON.stringify([sessionState, user?.id, selectedReportId, search, statusFilter, page, listNavigated]);

  function clearDashboardState() {
    setReports([]);
    setReportPage({ page: 1, pageSize: reportPageSize, totalItems: 0 });
    setSearchInput("");
    setSearch("");
    setStatusFilter("");
    setPage(1);
    setListNavigated(false);
    setSelectedReportId("");
    setSelectedReportTitle("");
    setDetail(null);
  }

  function handleChecklistAccessLoss(reason: "session" | "report", reportId: string) {
    if (reason === "report" && selectedReportId !== reportId) return;
    clearDashboardState();
    setUser(null);
    setPassword("");
    setSessionState("signed-out");
    setError(
      reason === "session"
        ? "Your session expired. Please sign in again."
        : "Your session or report access changed. Please sign in again.",
    );
  }

  useEffect(() => {
    const controller = new AbortController();
    setSessionState("checking");
    setUser(null);
    clearDashboardState();
    setError("");
    void readCurrentUser(controller.signal)
      .then((current) => {
        if (controller.signal.aborted) return;
        if (!current.active || current.role !== "DEVELOPER") {
          clearDashboardState();
          setSessionState("denied");
          return;
        }
        setUser(current);
        setSessionState("active");
      })
      .catch((cause: unknown) => {
        if (controller.signal.aborted) return;
        if (cause instanceof SessionExpired) {
          clearDashboardState();
          setSessionState("signed-out");
          return;
        }
        setSessionState("error");
        setError("Unable to check your session. Please retry.");
      });
    return () => controller.abort();
  }, [sessionAttempt]);

  useEffect(() => {
    if (sessionState !== "active" || user === null) return;
    const controller = new AbortController();
    setReports([]);
    setSelectedReportId("");
    setSelectedReportTitle("");
    setListState("loading");
    setError("");
    void readAssignedReports(
      { search, status: statusFilter, page, includePage: listNavigated },
      controller.signal,
    )
      .then((result) => {
        if (controller.signal.aborted) return;
        setReports(result.items);
        setReportPage({
          page: result.page,
          pageSize: result.pageSize,
          totalItems: result.totalItems,
        });
        setListState("ready");
      })
      .catch((cause: unknown) => {
        if (controller.signal.aborted) return;
        if (cause instanceof SessionExpired) {
          clearDashboardState();
          setUser(null);
          setPassword("");
          setSessionState("signed-out");
          setError("Your session expired. Please sign in again.");
          return;
        }
        if (cause instanceof RoleAccessDenied) {
          clearDashboardState();
          setUser(null);
          setSessionState("denied");
          setError("");
          return;
        }
        setListState("error");
        setError("Unable to load assigned reports.");
      });
    return () => controller.abort();
  }, [sessionState, user?.id, listAttempt, search, statusFilter, page, listNavigated]);

  useEffect(() => {
    resolutionController.current?.abort();
    refreshSequence.current++;
    setSubmittingResolution(false);
    setDetail(null);
    setResolution("");
    if (sessionState !== "active" || !selectedReportId) return;
    const controller = new AbortController();
    setDetailState("loading");
    void readReportDetail(selectedReportId, controller.signal)
      .then((report) => {
        if (controller.signal.aborted) return;
        setDetail(report);
        setDetailState("ready");
      })
      .catch((cause: unknown) => {
        if (controller.signal.aborted) return;
        if (cause instanceof SessionExpired || cause instanceof RoleAccessDenied) {
          handleChecklistAccessLoss(cause instanceof SessionExpired ? "session" : "report", selectedReportId);
          return;
        }
        setDetailState("error");
      });
    return () => controller.abort();
  }, [sessionState, user?.id, selectedReportId]);

  function submitSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const nextSearch = searchInput.trim();
    setSelectedReportId("");
    setSelectedReportTitle("");
    setPage(1);
    setListNavigated(true);
    setSearch(nextSearch);
  }

  function changeStatus(nextStatus: string) {
    setSelectedReportId("");
    setSelectedReportTitle("");
    setPage(1);
    setListNavigated(true);
    setStatusFilter(nextStatus);
  }

  function changePage(nextPage: number) {
    setSelectedReportId("");
    setSelectedReportTitle("");
    setListNavigated(true);
    setPage(nextPage);
  }

  async function startInvestigation() {
    if (!detail || detail.version === undefined || starting) return;
    const reportId = detail.id;
    const view = currentView.current;
    setStarting(true);
    setError("");
    try {
      const csrf = await readCsrf();
      const response = await fetch(`${reportsPath}/${encodeURIComponent(reportId)}/start`, {
        method: "POST", credentials: "same-origin", cache: "no-store",
        headers: { ...jsonHeaders, "Content-Type": "application/json", [csrf.headerName]: csrf.token },
        body: JSON.stringify({ expectedVersion: detail.version }),
      });
      if (response.status === 401) throw new SessionExpired();
      if (response.status === 403 || response.status === 404) throw new RoleAccessDenied();
      if (!response.ok && response.status !== 409) throw new Error();
      const controller = new AbortController();
      const [updated, pageResult] = await Promise.all([
        readReportDetail(reportId, controller.signal),
        readAssignedReports({ search, status: statusFilter, page, includePage: listNavigated }, controller.signal),
      ]);
      if (view !== currentView.current) return;
      if (response.ok && updated.version === detail.version + 1) {
        setStartedVersion({ reportId, previous: detail.version, current: updated.version });
      }
      setDetail((current) => current?.id === reportId ? updated : current);
      setReports(pageResult.items);
      setReportPage({ page: pageResult.page, pageSize: pageResult.pageSize, totalItems: pageResult.totalItems });
      if (response.status === 409) setError("The report changed. Its latest details are shown.");
    } catch (cause) {
      if (view !== currentView.current) return;
      if (cause instanceof SessionExpired || cause instanceof RoleAccessDenied) {
        handleChecklistAccessLoss(cause instanceof SessionExpired ? "session" : "report", reportId);
      } else {
        setError("Unable to start the investigation. Please retry.");
      }
    } finally {
      setStarting(false);
    }
  }

  async function refreshAfterShare(reportId: string, previous: number, current: number) {
    setDetail((report) => report?.id === reportId && report.version === previous
      ? { ...report, version: current } : report);
    const view = currentView.current;
    const sequence = ++refreshSequence.current;
    try {
      const controller = new AbortController();
      const [updated, pageResult] = await Promise.all([
        readReportDetail(reportId, controller.signal),
        readAssignedReports({ search, status: statusFilter, page, includePage: listNavigated }, controller.signal),
      ]);
      if (view !== currentView.current || sequence !== refreshSequence.current) return;
      setDetail((report) => report?.id === reportId && (report.version ?? 0) <= (updated.version ?? 0) ? updated : report);
      setReports(pageResult.items);
      setReportPage({ page: pageResult.page, pageSize: pageResult.pageSize, totalItems: pageResult.totalItems });
    } catch (cause) {
      if (view !== currentView.current || sequence !== refreshSequence.current) return;
      if (cause instanceof SessionExpired || cause instanceof RoleAccessDenied) {
        handleChecklistAccessLoss(cause instanceof SessionExpired ? "session" : "report", reportId);
      } else setError("Unable to refresh the report after sharing. Please retry.");
    }
  }

  async function submitResolution(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!detail || detail.version === undefined || submittingResolution || !resolution.trim()) return;
    const reportId = detail.id;
    const version = detail.version;
    const view = currentView.current;
    const controller = new AbortController();
    resolutionController.current = controller;
    setSubmittingResolution(true);
    setError("");
    try {
      const csrf = await readCsrf();
      if (controller.signal.aborted || view !== currentView.current) return;
      const response = await fetch(`${reportsPath}/${encodeURIComponent(reportId)}/resolution`, {
        method: "POST", credentials: "same-origin", cache: "no-store",
        headers: { ...jsonHeaders, "Content-Type": "application/json", [csrf.headerName]: csrf.token },
        body: JSON.stringify({ expectedVersion: version, resolution }),
        signal: controller.signal,
      });
      if (response.status === 401) throw new SessionExpired();
      if (response.status === 403 || response.status === 404) throw new RoleAccessDenied();
      if (!response.ok && response.status !== 409) throw new Error();
      const sequence = ++refreshSequence.current;
      const [updated, pageResult] = await Promise.all([
        readReportDetail(reportId, controller.signal),
        readAssignedReports({ search, status: statusFilter, page, includePage: listNavigated }, controller.signal),
      ]);
      if (controller.signal.aborted || view !== currentView.current || sequence !== refreshSequence.current) return;
      setDetail((current) => current?.id === reportId && (current.version ?? 0) <= (updated.version ?? 0) ? updated : current);
      if (updated.version !== undefined) setStartedVersion({ reportId, previous: version, current: updated.version });
      setReports(pageResult.items);
      setReportPage({ page: pageResult.page, pageSize: pageResult.pageSize, totalItems: pageResult.totalItems });
      if (response.status === 409) setError("The report changed. Your resolution text is still here.");
      else setResolution("");
    } catch (cause) {
      if (controller.signal.aborted || view !== currentView.current) return;
      if (cause instanceof SessionExpired || cause instanceof RoleAccessDenied) {
        handleChecklistAccessLoss(cause instanceof SessionExpired ? "session" : "report", reportId);
      } else setError("Unable to submit the resolution. Your text is still here.");
    } finally {
      if (!controller.signal.aborted) setSubmittingResolution(false);
    }
  }

  async function signIn(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      const csrf = await readCsrf();
      const response = await fetch(loginPath, {
        method: "POST",
        credentials: "same-origin",
        cache: "no-store",
        headers: {
          ...jsonHeaders,
          "Content-Type": "application/x-www-form-urlencoded",
          [csrf.headerName]: csrf.token,
        },
        body: new URLSearchParams({ username, password }).toString(),
      });
      if (!response.ok) throw new Error("Sign in failed. Check your credentials and try again.");

      // Verify the new session before requesting any assigned report data.
      clearDashboardState();
      const current = await readCurrentUser();
      if (!current.active || current.role !== "DEVELOPER") {
        clearDashboardState();
        setUser(null);
        setSessionState("denied");
        return;
      }
      setPassword("");
      setUser(current);
      setSessionState("active");
    } catch (cause) {
      setError(
        cause instanceof Error
          ? cause.message
          : "Sign in failed. Check your credentials and try again.",
      );
      setSessionState("signed-out");
    } finally {
      setBusy(false);
    }
  }

  async function signOut() {
    setBusy(true);
    setError("");
    try {
      const csrf = await readCsrf();
      const response = await fetch(logoutPath, {
        method: "POST",
        credentials: "same-origin",
        cache: "no-store",
        headers: { [csrf.headerName]: csrf.token },
      });
      if (!response.ok) throw new Error("Sign out failed. Please retry.");
      clearDashboardState();
      setUser(null);
      setPassword("");
      setSessionState("signed-out");
    } catch {
      setError("Sign out failed. Please retry.");
    } finally {
      setBusy(false);
    }
  }

  const appContent = (
    <main>
      <h1>Developer reports</h1>
      {sessionState === "checking" && <p role="status">Checking your session…</p>}
      {sessionState === "signed-out" && (
        <section className="developer-sign-in" aria-label="Sign in">
          <p className="developer-sign-in__eyebrow">DEVELOPER WORKSPACE</p>
          <h2>Sign in</h2>
          <p className="developer-sign-in__intro">Sign in to view and investigate your assigned reports.</p>
          <form onSubmit={(event) => void signIn(event)}>
            <label htmlFor="username">Username</label>
            <input
              id="username"
              name="username"
              autoComplete="username"
              value={username}
              disabled={busy}
              onChange={(event) => setUsername(event.target.value)}
            />
            <label htmlFor="password">Password</label>
            <input
              id="password"
              name="password"
              type="password"
              autoComplete="current-password"
              value={password}
              disabled={busy}
              onChange={(event) => setPassword(event.target.value)}
            />
            <button type="submit" disabled={busy}>Sign in</button>
          </form>
          {error && <p role="alert">{error}</p>}
        </section>
      )}
      {sessionState === "denied" && (
        <section>
          <p role="alert">Active Developer access is required.</p>
          <button type="button" onClick={() => setSessionAttempt((attempt) => attempt + 1)}>
            Retry
          </button>
        </section>
      )}
      {sessionState === "error" && (
        <section>
          <p role="alert">{error}</p>
          <button type="button" onClick={() => setSessionAttempt((attempt) => attempt + 1)}>
            Retry
          </button>
        </section>
      )}
      {sessionState === "active" && user && (
        <>
          {error && listState !== "error" && <p role="alert">{error}</p>}
          <DeveloperNotifications
            onSessionExpired={() => handleChecklistAccessLoss("session", "")}
            onOpenReport={(reportId) => {
              const report = reports.find((item) => item.id === reportId);
              setError("");
              setSelectedReportId(reportId);
              setSelectedReportTitle(report?.title ?? "Assigned report");
            }}
          />
          <section aria-label="Assigned reports">
            <h2>Assigned reports</h2>
            <div className="developer-report-controls">
              <form className="developer-report-search" onSubmit={submitSearch}>
                <label htmlFor="report-search">Search</label>
                <div>
                  <input
                    id="report-search"
                    name="search"
                    type="search"
                    value={searchInput}
                    onChange={(event) => setSearchInput(event.target.value)}
                  />
                  <button type="submit">Search</button>
                </div>
              </form>
              <label htmlFor="report-status">Status</label>
              <select
                id="report-status"
                name="status"
                value={statusFilter}
                onChange={(event) => changeStatus(event.target.value)}
              >
                <option value="">All statuses</option>
                {reportStatuses.map((status) => (
                  <option key={status} value={status}>{formatStatus(status)}</option>
                ))}
              </select>
            </div>
            {listState === "loading" && <p role="status">Loading assigned reports…</p>}
            {listState === "error" && (
              <>
                <p role="alert">{error}</p>
                <button type="button" onClick={() => setListAttempt((attempt) => attempt + 1)}>
                  Retry
                </button>
              </>
            )}
            {listState === "ready" && reports.length === 0 && reportPage.totalItems === 0 && (
              <p>{search || statusFilter ? "No matching reports." : "No assigned reports."}</p>
            )}
            {listState === "ready" && reports.length === 0 && reportPage.totalItems > 0 && (
              <p role="status">This page is out of range. Choose another page.</p>
            )}
            {listState === "ready" && reports.length > 0 && (
              <ul className="developer-report-list">
                {reports.map((report) => (
                  <li key={report.id}>
                    <button
                      type="button"
                      className="developer-report-list__button"
                      aria-pressed={selectedReportId === report.id}
                      onClick={() => {
                        setError("");
                        setSelectedReportId(report.id);
                        setSelectedReportTitle(report.title);
                      }}
                    >
                      <span className="developer-report-list__title">{report.title}</span>
                      <span className="developer-report-list__status">
                        {formatStatus(report.status)}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
            {listState === "ready" && reportPage.totalItems > 0 && (
              <nav className="developer-report-pagination" aria-label="Report pages">
                <button
                  type="button"
                  onClick={() => changePage(page - 1)}
                  disabled={page <= 1}
                >
                  Previous
                </button>
                <span aria-live="polite">
                  Page {reportPage.page} of {Math.max(1, Math.ceil(reportPage.totalItems / reportPage.pageSize))}
                </span>
                <button
                  type="button"
                  onClick={() => changePage(page + 1)}
                  disabled={page >= Math.ceil(reportPage.totalItems / reportPage.pageSize)}
                >
                  Next
                </button>
              </nav>
            )}
          </section>
          {selectedReportId && (
            <section className="developer-report-detail" aria-label="Selected report">
              <h2>{detail?.id === selectedReportId ? detail.title : selectedReportTitle}</h2>
              {detailState === "loading" && <p role="status">Loading report details…</p>}
              {detailState === "error" && <p role="alert">Unable to load report details.</p>}
              {detailState === "ready" && detail?.id === selectedReportId && (
                <>
                  <p className="developer-report-detail__description">{detail.description}</p>
                  <p className="developer-report-detail__status">Status: {formatStatus(detail.status)}</p>
                  {detail.returnReason && detail.status === "IN_PROGRESS" && (
                    <aside className="developer-return-reason" aria-label="Maintainer feedback">
                      <h3>Maintainer returned this resolution</h3>
                      <p>{detail.returnReason}</p>
                    </aside>
                  )}
                  {(detail.status === "IN_PROGRESS" || resolution.length > 0) && detail.version !== undefined && (
                    <form className="developer-resolution" onSubmit={(event) => void submitResolution(event)}>
                      <label htmlFor="resolution">Resolution</label>
                      <textarea id="resolution" value={resolution} maxLength={10000} disabled={submittingResolution}
                        onChange={(event) => setResolution(event.target.value)} />
                      <button type="submit" disabled={submittingResolution || detail.status !== "IN_PROGRESS" || !resolution.trim()}>
                        {submittingResolution ? "Submitting…" : "Submit resolution"}
                      </button>
                    </form>
                  )}
                  {detail.status === "ASSIGNED" && detail.version !== undefined && (
                    <button type="button" disabled={starting} onClick={() => void startInvestigation()}>
                      {starting ? "Starting…" : "Start investigation"}
                    </button>
                  )}
                  <DeveloperPrivateNotes
                    key={selectedReportId}
                    reportId={selectedReportId}
                    status={detail.status}
                    onAccessLost={handleChecklistAccessLoss}
                  />
                </>
              )}
              <DeveloperChecklist
                key={selectedReportId}
                reportId={selectedReportId}
                onAccessLost={handleChecklistAccessLoss}
                startedVersion={startedVersion?.reportId === selectedReportId ? startedVersion : undefined}
                onReportVersionChange={(previous, current) => {
                  void refreshAfterShare(selectedReportId, previous, current);
                }}
              />
            </section>
          )}
        </>
      )}
    </main>
  );

  return createRoleWorkspace(
    (type, props, ...children) =>
      createElement(type, props as never, ...(children as ReactNode[])),
    {
      role: "Developer",
      identity: user?.displayName,
      authenticated: sessionState === "active" && user !== null,
      navigation: [{ label: "Dashboard", href: "#workspace-content", current: true }],
      accountActions: user ? createElement(
        "button",
        {
          type: "button",
          className: "developer-sign-out",
          onClick: () => void signOut(),
          disabled: busy,
        },
        busy ? "Signing out…" : "Sign out",
      ) : undefined,
      children: appContent,
    },
  );
}

function formatStatus(status: string): string {
  return status
    .toLowerCase()
    .replace(/[_-]+/g, " ")
    .replace(/\b\w/g, (letter) => letter.toUpperCase());
}

class SessionExpired extends Error {}
class RoleAccessDenied extends Error {}

async function readCurrentUser(signal?: AbortSignal): Promise<User> {
  const response = await fetch(currentUserPath, {
    method: "GET",
    credentials: "same-origin",
    cache: "no-store",
    headers: jsonHeaders,
    ...(signal ? { signal } : {}),
  });
  if (response.status === 401) throw new SessionExpired();
  if (!response.ok) throw new Error("Unable to check your session.");
  const value: unknown = await response.json();
  if (
    !isRecord(value) ||
    typeof value.id !== "string" ||
    typeof value.displayName !== "string" ||
    typeof value.role !== "string" ||
    typeof value.active !== "boolean"
  ) {
    throw new Error("Unable to check your session.");
  }
  return {
    id: value.id,
    displayName: value.displayName,
    role: value.role,
    active: value.active,
  };
}

async function readCsrf(): Promise<{ token: string; headerName: "X-CSRF-TOKEN" }> {
  const response = await fetch(csrfPath, {
    method: "GET",
    credentials: "same-origin",
    cache: "no-store",
    headers: jsonHeaders,
  });
  if (!response.ok) throw new Error("Unable to verify this request.");
  const value: unknown = await response.json();
  if (
    !isRecord(value) ||
    typeof value.token !== "string" ||
    value.token.length === 0 ||
    value.headerName !== "X-CSRF-TOKEN"
  ) {
    throw new Error("Unable to verify this request.");
  }
  return { token: value.token, headerName: value.headerName };
}

async function readAssignedReports(
  filters: { search: string; status: string; page: number; includePage: boolean },
  signal: AbortSignal,
): Promise<ReportsPage> {
  const query = new URLSearchParams();
  if (filters.search) query.set("search", filters.search);
  if (filters.status) query.set("status", filters.status);
  if (filters.includePage || filters.page !== 1 || filters.search || filters.status) {
    query.set("page", String(filters.page));
  }
  const path = query.size > 0 ? `${reportsPath}?${query.toString()}` : reportsPath;
  const response = await fetch(path, {
    method: "GET",
    credentials: "same-origin",
    cache: "no-store",
    headers: jsonHeaders,
    signal,
  });
  if (response.status === 401) throw new SessionExpired();
  if (response.status === 403) {
    let value: unknown;
    try {
      value = await response.clone().json();
    } catch {
      value = null;
    }
    if (isRecord(value) && value.code === "FORBIDDEN") throw new RoleAccessDenied();
  }
  if (!response.ok) throw new Error("Unable to load assigned reports.");
  const value: unknown = await response.json();
  if (
    !isRecord(value) ||
    !Array.isArray(value.items) ||
    !Number.isSafeInteger(value.page) ||
    value.page !== filters.page ||
    !Number.isSafeInteger(value.pageSize) ||
    value.pageSize !== reportPageSize ||
    !Number.isSafeInteger(value.totalItems) ||
    (value.totalItems as number) < 0
  ) {
    throw new Error("Unable to load assigned reports.");
  }
  const reports = value.items.map(parseReport);
  if (reports.some((report) => report === null) || reports.length > reportPageSize) {
    throw new Error("Unable to load assigned reports.");
  }
  return {
    items: reports as Report[],
    page: value.page as number,
    pageSize: value.pageSize as number,
    totalItems: value.totalItems as number,
  };
}

function parseReport(value: unknown): Report | null {
  if (
    !isRecord(value) ||
    typeof value.id !== "string" ||
    typeof value.title !== "string" ||
    typeof value.status !== "string"
  ) {
    return null;
  }
  return { id: value.id, title: value.title, status: value.status };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

async function readReportDetail(id: string, signal: AbortSignal): Promise<ReportDetail> {
  const response = await fetch(`${reportsPath}/${encodeURIComponent(id)}`, {
    method: "GET", credentials: "same-origin", cache: "no-store", headers: jsonHeaders, signal,
  });
  if (response.status === 401) throw new SessionExpired();
  if (response.status === 403 || response.status === 404) throw new RoleAccessDenied();
  if (!response.ok) throw new Error("Unable to load report details.");
  const value: unknown = await response.json();
  const report = parseReport(value);
  if (!report || report.id !== id || !isRecord(value) || typeof value.description !== "string") {
    throw new Error("Unable to load report details.");
  }
  const version = Number.isSafeInteger(value.version) && (value.version as number) >= 0
    ? value.version as number : undefined;
  if (value.returnReason !== undefined && value.returnReason !== null
      && (typeof value.returnReason !== "string" || value.returnReason.length > 2000)) {
    throw new Error("Unable to load report details.");
  }
  return { ...report, description: value.description, version,
    returnReason: typeof value.returnReason === "string" ? value.returnReason : null };
}
