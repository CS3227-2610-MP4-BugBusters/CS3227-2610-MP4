import { createElement, useEffect, useRef, useState, type ReactNode } from "react";
import {
  messages,
  priorities,
  QueryError,
  statuses,
  type Detail,
  type Filters,
  type Page,
  type Person,
  type Queries,
  type SummaryFilters,
  type SummaryResult,
} from "./domain";
import type { AuthActions } from "./auth-client";
import { createRoleWorkspace } from "../../shared/RoleWorkspace";
import { createRoleSignIn } from "../../shared/RoleSignIn";
import { parseSummaryResult } from "./summary";
import { MarkdownView } from "@mp4/markdown";
type Load<T> = {
  state: "loading" | "ready" | "error";
  data?: T;
  error?: QueryError;
};
const loading = <T,>(): Load<T> => ({ state: "loading" });
const failure = (e: unknown) => {
  if (e instanceof QueryError) return e;
  if (e && typeof e === "object") {
    const error = e as { status?: unknown; code?: unknown };
    if (error.status === 401) return new QueryError("session");
    if (error.status === 403) return new QueryError("denied");
    if (error.status === 409) return new QueryError(error.code === "INVALID_STATE" ? "invalidState" : "conflict");
    if (error.status === 400 || error.status === 422) return new QueryError("validation");
  }
  return new QueryError("network");
};
type ActionName = "updatePriority" | "assignReport" | "returnResolution" | "closeReport" | "reopenReport" | "addComment";
type ActionInput = Record<string, unknown>;
const navigateTo = (path: string) => window.location.assign(path);
export function App({
  queries,
  synthetic = false,
  authActions,
  sharedEntry = false,
  navigate = navigateTo,
}: {
  queries: Queries;
  synthetic?: boolean;
  authActions?: AuthActions;
  sharedEntry?: boolean;
  navigate?: (path: string) => void;
}) {
  const [auth, setAuth] = useState<"loading" | "ready" | "error" | "redirecting">("loading"),
    [authError, setAuthError] = useState<QueryError>(),
    [identity, setIdentity] = useState<{ displayName: string; role: string }>(),
    [session, setSession] = useState(0),
    [refresh, setRefresh] = useState(0),
    [username, setUsername] = useState(""),
    [password, setPassword] = useState(""),
    [authBusy, setAuthBusy] = useState<"signIn" | "signOut">(),
    [authActionError, setAuthActionError] = useState<QueryError>();
  const [filters, setFilters] = useState<Filters>({ offset: 0, limit: 50 }),
    [list, setList] = useState<Load<Page>>(loading),
    [devs, setDevs] = useState<Load<Person[]>>(loading),
    [selected, setSelected] = useState<string>(),
    [detail, setDetail] = useState<Load<Detail>>(loading),
    [queueSummary, setQueueSummary] = useState<Load<SummaryResult>>({ state: "loading" }),
    [summaryPending, setSummaryPending] = useState(false),
    [listRetry, setListRetry] = useState(0),
    [devRetry, setDevRetry] = useState(0),
    [detailRetry, setDetailRetry] = useState(0),
    [actionPending, setActionPending] = useState(false),
    [actionError, setActionError] = useState<QueryError>();
  const epoch = useRef(0),
    selectedRef = useRef(selected),
    authActionGeneration = useRef(0),
    activeAuthController = useRef<AbortController | undefined>(undefined),
    listEpoch = useRef(0),
    detailEpoch = useRef(0),
    devEpoch = useRef(0),
    actionController = useRef<AbortController | undefined>(undefined),
    actionLock = useRef(false),
    summaryEpoch = useRef(0),
    summaryController = useRef<AbortController | undefined>(undefined),
    controllers = useRef(new Set<AbortController>());
  selectedRef.current = selected;
  const control = () => {
    const c = new AbortController();
    controllers.current.add(c);
    return c;
  };
  const invalidateSummary = () => {
    summaryEpoch.current++;
    summaryController.current?.abort();
    summaryController.current = undefined;
    setSummaryPending(false);
    setQueueSummary({ state: "loading" });
  };
  const clear = () => {
    invalidateSummary();
    authActionGeneration.current++;
    activeAuthController.current?.abort();
    activeAuthController.current = undefined;
    setAuthBusy(undefined);
    controllers.current.forEach((c) => c.abort());
    actionController.current?.abort();
    actionController.current = undefined;
    actionLock.current = false;
    setActionPending(false);
    setActionError(undefined);
    controllers.current.clear();
    listEpoch.current++;
    detailEpoch.current++;
    devEpoch.current++;
    setList(loading());
    setDevs(loading());
    setSelected(undefined);
    setDetail(loading());
  };
  const cancelSelectedAction = () => {
    actionController.current?.abort();
    actionController.current = undefined;
    actionLock.current = false;
    setActionPending(false);
  };
  const deny = (e: QueryError, g: number) => {
    if (g !== epoch.current) return;
    epoch.current++;
    clear();
    setAuthError(e);
    setAuth("error");
  };
  useEffect(() => {
    const g = ++epoch.current;
    clear();
    setAuth("loading");
    setAuthError(undefined);
    const c = control();
    queries
      .getCurrentUser(c.signal)
      .finally(() => controllers.current.delete(c))
      .then(
        (u) => {
          if (g !== epoch.current) return;
          setIdentity({ displayName: u.displayName, role: u.role });
          if (sharedEntry && u.active && (u.role === "DEVELOPER" || u.role === "REPORTER")) {
            setAuth("redirecting");
            navigate(u.role === "DEVELOPER" ? "/developer/" : "/reporter/");
            return;
          }
          if (!u.active || u.role !== "MAINTAINER") {
            deny(new QueryError("denied"), g);
            return;
          }
          setAuth("ready");
          setSession(g);
        },
        (e) => {
          if (g === epoch.current && failure(e).code !== "cancelled")
            deny(failure(e), g);
        },
      );
    return () => {
      c.abort();
      if (g === epoch.current) epoch.current++;
    };
  }, [queries, refresh, sharedEntry, navigate]);
  useEffect(() => {
    if (auth !== "ready" || session !== epoch.current) return;
    const g = epoch.current,
      n = ++listEpoch.current,
      c = control();
    setList(loading());
    queries
      .listReports(filters, c.signal)
      .finally(() => controllers.current.delete(c))
      .then(
        (data) => {
          if (g === epoch.current && n === listEpoch.current)
            setList({ state: "ready", data });
        },
        (e) => {
          const error = failure(e);
          if (g !== epoch.current || error.code === "cancelled") return;
          if (error.code === "session" || error.code === "denied") {
            deny(error, g);
            return;
          }
          if (n !== listEpoch.current) return;
          setList({ state: "error", error });
        },
      );
    return () => {
      c.abort();
      listEpoch.current++;
    };
  }, [queries, auth, session, filters, listRetry]);
  useEffect(() => {
    if (auth !== "ready" || session !== epoch.current) return;
    const g = epoch.current,
      n = ++devEpoch.current,
      c = control();
    setDevs(loading());
    queries
      .listDevelopers(c.signal)
      .finally(() => controllers.current.delete(c))
      .then(
        (data) => {
          if (g === epoch.current && n === devEpoch.current)
            setDevs({ state: "ready", data });
        },
        (e) => {
          const error = failure(e);
          if (g !== epoch.current || error.code === "cancelled") return;
          if (error.code === "session" || error.code === "denied") {
            deny(error, g);
            return;
          }
          if (n !== devEpoch.current) return;
          setDevs({ state: "error", error });
        },
      );
    return () => {
      c.abort();
      devEpoch.current++;
    };
  }, [queries, auth, session, devRetry]);
  useEffect(() => {
    if (auth !== "ready" || session !== epoch.current || !selected) return;
    const g = epoch.current,
      n = ++detailEpoch.current,
      c = control();
    setDetail((previous) => previous.data ? { ...previous, state: "loading" } : loading());
    queries
      .getReport(selected, c.signal)
      .finally(() => controllers.current.delete(c))
      .then(
        (data) => {
          if (g === epoch.current && n === detailEpoch.current)
            setDetail({ state: "ready", data: structuredClone(data) });
        },
        (e) => {
          const error = failure(e);
          if (g !== epoch.current || error.code === "cancelled") return;
          if (error.code === "session" || error.code === "denied") {
            deny(error, g);
            return;
          }
          if (n !== detailEpoch.current) return;
          setDetail((previous) => ({ state: "error", error, ...(previous.data ? { data: previous.data } : {}) }));
        },
      );
    return () => {
      c.abort();
      detailEpoch.current++;
    };
  }, [queries, auth, session, selected, detailRetry]);
  const change = (next: Filters) => {
    const canonical = (value: Filters) => JSON.stringify({
      ...(value.status ? { status: value.status } : {}),
      ...(value.priority ? { priority: value.priority } : {}),
      ...(value.assigneeId ? { assigneeId: value.assigneeId } : {}),
    });
    if (canonical(next) !== canonical(filters)) invalidateSummary();
    cancelSelectedAction();
    listEpoch.current++;
    detailEpoch.current++;
    setList(loading());
    setSelected(undefined);
    setDetail(loading());
    setFilters(next);
  };
  const runSummary = async (regenerate = false) => {
    if (!queries.summarizeQueue || summaryPending || actionPending || actionLock.current || auth !== "ready" || session !== epoch.current || filters.status === "Closed") return;
    const requestFilters: SummaryFilters = {
      ...(filters.status ? { status: filters.status } : {}),
      ...(filters.priority ? { priority: filters.priority } : {}),
      ...(filters.assigneeId ? { assigneeId: filters.assigneeId } : {}),
    };
    const request = { filters: requestFilters, regenerate };
    const requestEpoch = epoch.current, requestSummaryEpoch = ++summaryEpoch.current;
    const controller = control();
    summaryController.current = controller;
    setSummaryPending(true);
    setQueueSummary({ state: "loading" });
    const current = () => requestEpoch === epoch.current && requestSummaryEpoch === summaryEpoch.current && !controller.signal.aborted;
    try {
      const raw = await queries.summarizeQueue(request, controller.signal);
      const parsed = parseSummaryResult(raw);
      if (JSON.stringify(parsed.filters) !== JSON.stringify(requestFilters)) throw new QueryError("contract");
      if (current()) setQueueSummary({ state: "ready", data: parsed });
    } catch (cause) {
      const error = failure(cause);
      // A denial applies to the current session even if the user changed filters while it was pending.
      if (requestEpoch === epoch.current && (error.code === "session" || error.code === "denied")) deny(error, requestEpoch);
      else if (current() && error.code !== "cancelled") setQueueSummary({ state: "error", error });
    } finally {
      controllers.current.delete(controller);
      if (summaryController.current === controller) {
        summaryController.current = undefined;
        setSummaryPending(false);
      }
    }
  };
  const select = (id: string) => {
    if (selected === id) return;
    cancelSelectedAction();
    detailEpoch.current++;
    setDetail(loading());
    setSelected(id);
    setActionError(undefined);
  };
  const runAction = async (kind: ActionName, input: ActionInput): Promise<boolean> => {
    if (actionLock.current || !selected || auth !== "ready" || session !== epoch.current) return false;
    const action = queries[kind];
    if (!action) { setActionError(new QueryError("network")); return false; }
    const expectedSelection = selected, expectedEpoch = epoch.current;
    const c = control();
    actionController.current = c;
    actionLock.current = true;
    invalidateSummary();
    setActionPending(true);
    setActionError(undefined);
    const current = () => expectedEpoch === epoch.current && expectedSelection === selectedRef.current && !c.signal.aborted;
    try {
      await (action as (v: ActionInput, signal?: AbortSignal) => Promise<void>)(input, c.signal);
      if (!current()) return false;
      setListRetry((v) => v + 1);
      setDetailRetry((v) => v + 1);
      return true;
    } catch (e) {
      const error = failure(e);
      if (expectedEpoch === epoch.current && (error.code === "session" || error.code === "denied")) deny(error, expectedEpoch);
      else if (current() && error.code !== "cancelled") setActionError(error);
      return false;
    } finally {
      controllers.current.delete(c);
      // An aborted action can still commit server-side. Clear summaries created after its start
      // when its response arrives, provided the same authorized session is still active.
      if (expectedEpoch === epoch.current) invalidateSummary();
      if (actionController.current === c) {
        actionController.current = undefined;
        actionLock.current = false;
        setActionPending(false);
      }
    }
  };
  const reload = () => {
    epoch.current++;
    clear();
    setAuth("loading");
    setRefresh((v) => v + 1);
  };
  const signIn = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!authActions || authBusy || authError?.code !== "session") return;
    setAuthBusy("signIn");
    setAuthActionError(undefined);
    const action = ++authActionGeneration.current;
    const c = control();
    activeAuthController.current = c;
    try {
      await authActions.signIn(username, password, c.signal);
      if (!c.signal.aborted && action === authActionGeneration.current) {
        setPassword("");
        setIdentity(undefined);
        controllers.current.delete(c);
        activeAuthController.current = undefined;
        setAuthBusy(undefined);
        reload();
      }
    } catch (e) {
      if (!c.signal.aborted && action === authActionGeneration.current) {
        setPassword("");
        setAuthActionError(failure(e));
      }
    } finally {
      controllers.current.delete(c);
      if (action === authActionGeneration.current) {
        activeAuthController.current = undefined;
        setAuthBusy(undefined);
      }
    }
  };
  const signOut = async () => {
    if (!authActions || authBusy) return;
    setAuthActionError(undefined);
    epoch.current++;
    clear();
    setAuthError(new QueryError("session"));
    setAuth("error");
    setIdentity(undefined);
    setAuthBusy("signOut");
    const action = ++authActionGeneration.current;
    const c = control();
    activeAuthController.current = c;
    try {
      await authActions.signOut(c.signal);
    } catch (e) {
      if (!c.signal.aborted && action === authActionGeneration.current)
        setAuthActionError(failure(e));
    } finally {
      controllers.current.delete(c);
      if (action === authActionGeneration.current) {
        activeAuthController.current = undefined;
        setAuthBusy(undefined);
      }
    }
  };
  const appContent = (
    <main>
      <header>
        <div>
          <p className="eyebrow">{sharedEntry && auth !== "ready" ? "BUGBUSTERS" : "BUGBUSTERS / MAINTAINER"}</p>
          <h1>{sharedEntry && auth !== "ready" ? "Bug Tracker" : "Report queue"}</h1>
          <p>{sharedEntry && auth !== "ready" ? "Sign in to your workspace." : "Review reports and investigation evidence."}</p>
        </div>
        <button onClick={reload}>Refresh session and queue</button>
      </header>
      {synthetic && (
        <p className="banner">
          Synthetic demonstration data · development only
        </p>
      )}
      {auth === "redirecting" ? (
        <p role="status">Opening your workspace…</p>
      ) : auth === "loading" ? (
        <p role="status">Checking access…</p>
      ) : auth === "error" ? (
        authActions && authError?.code === "session" ? (
          createRoleSignIn(
            (type, props, ...children) => createElement(type, props as never, ...(children as ReactNode[])),
            {
              role: sharedEntry ? "Bug Tracker" : "Maintainer",
              description: sharedEntry ? "Sign in with your account." : "Review reports and investigation evidence.",
              username,
              password,
              busy: !!authBusy,
              requiredCredentials: true,
              busyLabel: authBusy === "signIn" ? "Signing in…" : "Sign in",
              onUsernameChange: setUsername,
              onPasswordChange: setPassword,
              onSubmit: signIn,
              notice: <p>{authError.message}</p>,
              status: authBusy === "signOut" ? <p role="status">Signing out…</p> : null,
              feedback: authActionError ? (
                <p role="alert">
                  {authActionError.code === "session"
                    ? "Sign-in failed. Check your username and password."
                    : messages[authActionError.code]}
                </p>
              ) : null,
            },
          )
        ) : (
          <div role="alert">
            <p>{authError?.message}</p>
            {identity && (
              <p>
                {identity.displayName} · {identity.role}
              </p>
            )}
            {authActions && identity && (
              <button onClick={signOut} disabled={!!authBusy}>
                {authBusy === "signOut" ? "Signing out…" : "Sign out"}
              </button>
            )}
            <button onClick={reload}>Retry access check</button>
          </div>
        )
      ) : (
        <>
          <section className="filters" aria-label="Queue filters">
            <label>
              Status
              <select
                value={filters.status ?? ""}
                onChange={(e) =>
                  change({
                    ...filters,
                    status: (e.target.value || undefined) as Filters["status"],
                    offset: 0,
                  })
                }
              >
                <option value="">All statuses</option>
                {statuses.map((x) => (
                  <option key={x}>{x}</option>
                ))}
              </select>
            </label>
            <label>
              Priority
              <select
                value={filters.priority ?? ""}
                onChange={(e) =>
                  change({
                    ...filters,
                    priority: (e.target.value ||
                      undefined) as Filters["priority"],
                    offset: 0,
                  })
                }
              >
                <option value="">All priorities</option>
                {priorities.map((x) => (
                  <option key={x}>{x}</option>
                ))}
              </select>
            </label>
            <label>
              Assignee
              <select
                value={filters.assigneeId ?? ""}
                disabled={devs.state !== "ready"}
                onChange={(e) =>
                  change({
                    ...filters,
                    assigneeId: e.target.value || undefined,
                    offset: 0,
                  })
                }
              >
                <option value="">All assignees</option>
                {devs.data?.map((x) => (
                  <option key={x.id} value={x.id}>
                    {x.displayName}
                  </option>
                ))}
              </select>
            </label>
            {devs.state === "loading" && (
              <p role="status">Loading developers…</p>
            )}
            {devs.state === "error" && (
              <ErrorView
                error={devs.error!}
                retry={() => setDevRetry((v) => v + 1)}
              />
            )}
          </section>
          {queries.summarizeQueue && <section className="summary-panel" aria-label="Queue summary">
            <h2>Queue summary</h2>
            <p>Advisory summary of the full matching open queue. Review the source reports before acting.</p>
            <button type="button" disabled={summaryPending || actionPending || filters.status === "Closed"} onClick={() => void runSummary(false)}>Generate summary</button>
            {summaryPending && <p role="status">Generating a reviewable summary…</p>}
            {queueSummary.state === "error" && <div role="alert"><p>{messages[queueSummary.error!.code]}</p><button type="button" disabled={filters.status === "Closed"} onClick={() => void runSummary(false)}>Retry summary</button></div>}
            {queueSummary.state === "ready" && <>
              <p><strong>Review required.</strong> Captured {queueSummary.data!.capturedAt}. Provider mode: {queueSummary.data!.providerMode}.</p>
              <button type="button" disabled={summaryPending || actionPending || filters.status === "Closed"} onClick={() => void runSummary(true)}>Regenerate summary</button>
              {(["overview", "blockers", "missingInformation"] as const).map((group) => <section key={group}>
                <h3>{group === "missingInformation" ? "Missing information" : group[0].toUpperCase() + group.slice(1)}</h3>
                {queueSummary.data!.findings[group].length ? <ul>{queueSummary.data!.findings[group].map((finding, index) => <li key={`${group}-${index}`}><p className="text">{finding.text}</p><small>Sources: {finding.reportIds.map((id, i) => <span key={id}>{i > 0 ? ", " : ""}<a href={`#report-${id}`} onClick={() => select(id)}>{id}</a></span>)}</small></li>)}</ul> : <p>None reported.</p>}
              </section>)}
              <details>
                <summary>Included reports ({queueSummary.data!.included.length})</summary>
                {queueSummary.data!.included.length ? <ul>{queueSummary.data!.included.map((item) => <li key={item.id}>
                  <a href={`#report-${item.id}`} onClick={() => select(item.id)}>{item.id}</a>
                  {item.truncatedFields.length > 0 && <span> · {item.truncatedFields.join(", ")} truncated</span>}
                </li>)}</ul> : <p>No reports were included.</p>}
              </details>
              <details>
                <summary>Excluded reports ({queueSummary.data!.excluded.length})</summary>
                {queueSummary.data!.excluded.length ? <ul>{queueSummary.data!.excluded.map((item) => <li key={item.id}><a href={`#report-${item.id}`} onClick={() => select(item.id)}>{item.id}</a> · {item.reason}</li>)}</ul> : <p>No reports were excluded.</p>}
              </details>
            </>}
          </section>}
          <div className="workspace">
            <section aria-label="Reports" className="queue">
              {list.state === "loading" ? (
                <p role="status">Loading reports…</p>
              ) : list.state === "error" ? (
                <ErrorView
                  error={list.error!}
                  retry={() => setListRetry((v) => v + 1)}
                />
              ) : (
                <>
                  <p role="status">{list.data!.total} matching reports</p>
                  {list.data!.items.length === 0 ? (
                    <p>No reports match these filters.</p>
                  ) : (
                    <ul className="report-list">
                      {list.data!.items.map((r) => (
                        <li key={r.id}>
                          <button
                            aria-pressed={selected === r.id}
                            onClick={() => select(r.id)}
                          >
                            <strong>{r.title}</strong>
                            <span>
                              {r.status} · {r.priority}
                            </span>
                            <span>
                              {r.assignee?.displayName ?? "Unassigned"} · v
                              {r.version}
                            </span>
                            <small>Updated {r.updatedAt}</small>
                          </button>
                        </li>
                      ))}
                    </ul>
                  )}
                  <nav aria-label="Report pages">
                    <button
                      disabled={(filters.offset ?? 0) === 0}
                      onClick={() =>
                        change({
                          ...filters,
                          offset: Math.max(0, (filters.offset ?? 0) - 50),
                        })
                      }
                    >
                      Previous
                    </button>
                    <span>
                      Page {Math.floor((filters.offset ?? 0) / 50) + 1}
                    </span>
                    <button
                      disabled={(filters.offset ?? 0) + 50 >= list.data!.total}
                      onClick={() =>
                        change({
                          ...filters,
                          offset: (filters.offset ?? 0) + 50,
                        })
                      }
                    >
                      Next
                    </button>
                  </nav>
                </>
              )}
            </section>
            <section aria-label="Report details" className="detail">
              {!selected ? (
                <p>Select a report to inspect its evidence.</p>
              ) : detail.state === "loading" && !detail.data ? (
                <p role="status">Loading report details…</p>
              ) : detail.state === "error" && !detail.data ? (
                <ErrorView
                  error={detail.error!}
                  retry={() => setDetailRetry((v) => v + 1)}
                />
              ) : (
                <>{detail.state === "loading" && <p role="status">Refreshing report details…</p>}{detail.state === "error" && <ErrorView error={detail.error!} retry={() => setDetailRetry((v) => v + 1)} />}<Report key={detail.data!.id} detail={detail.data!} developers={devs.data ?? []} queries={queries} pending={actionPending} error={actionError} runAction={runAction} clearError={() => setActionError(undefined)} refreshReport={() => setDetailRetry((v) => v + 1)} /></>
              )}
            </section>
          </div>
        </>
      )}
    </main>
  );

  return createRoleWorkspace(
    (type, props, ...children) =>
      createElement(type, props as never, ...(children as ReactNode[])),
    {
      role: "Maintainer",
      identity: identity?.displayName,
      authenticated: auth === "ready" && identity?.role === "MAINTAINER",
      navigation: [{ label: "Dashboard", href: "/", current: true }],
      accountActions: authActions ? (
        <button className="workspace-sign-out" onClick={signOut} disabled={!!authBusy}>
          {authBusy === "signOut" ? "Signing out…" : "Sign out"}
        </button>
      ) : undefined,
      children: appContent,
    },
  );
}
function ErrorView({ error, retry }: { error: QueryError; retry: () => void }) {
  return (
    <div role="alert">
      <p>{messages[error.code]}</p>
      <button onClick={retry}>Retry</button>
    </div>
  );
}
function ActionControls({ r, developers, queries, pending, error, runAction, clearError, refreshReport }: { r: Detail; developers: Person[]; queries: Queries; pending: boolean; error?: QueryError; runAction: (kind: ActionName, input: ActionInput) => Promise<boolean>; clearError: () => void; refreshReport: () => void }) {
  const [priority, setPriority] = useState(r.priority), [developerId, setDeveloperId] = useState(r.assignee?.id ?? ""),
    [assignmentReason, setAssignmentReason] = useState(""), [returnReason, setReturnReason] = useState(""),
    [reopenReason, setReopenReason] = useState(""), [comment, setComment] = useState(""), [ack, setAck] = useState("");
  const proposalKey = r.currentProposal ? [r.id, r.version, r.currentProposal.id, r.currentProposal.version].join(":") : "";
  const acknowledged = !!proposalKey && ack === proposalKey;
  const reasonValid = (s: string) => { const t = s.trim(); return [...t].length >= 1 && [...t].length <= 1000 && !/[\u0000-\u0008\u000b\u000c\u000d-\u001f\u007f-\u009f]/.test(t); };
  const commentValid = (s: string) => { const t = s.trim(); return [...t].length >= 1 && [...t].length <= 4000 && !/[\u0000-\u0008\u000b\u000c\u000d-\u001f\u007f-\u009f]/.test(t); };
  const submit = async (kind: ActionName, input: ActionInput, reset: () => void) => { clearError(); if (await runAction(kind, input)) reset(); };
  const assignmentEligible = r.status === "Submitted" || r.status === "Assigned" || r.status === "In progress";
  const isReassignment = r.status === "Assigned" || r.status === "In progress";
  return <>
    <section aria-label="Maintainer actions" className="action-panel">
      <h3>Priority and assignment</h3>
      <label>Report priority<select aria-label="Report priority" value={priority} disabled={pending} onChange={(e) => setPriority(e.target.value as typeof r.priority)}>{priorities.map((p) => <option key={p}>{p}</option>)}</select></label>
      <button disabled={pending || !queries.updatePriority} onClick={() => void submit("updatePriority", { reportId: r.id, expectedVersion: r.version, priority }, () => setPriority(priority))}>Save priority</button>
      {assignmentEligible && <><label>Assign to<select aria-label="Assign to" value={developerId} disabled={pending} onChange={(e) => setDeveloperId(e.target.value)}><option value="">Choose an active Developer</option>{developers.map((d) => <option key={d.id} value={d.id}>{d.displayName}</option>)}</select></label>
      {isReassignment && <label>Reason<input aria-label="Reason" value={assignmentReason} disabled={pending} onChange={(e) => setAssignmentReason(e.target.value)} /></label>}
      <button disabled={pending || !developerId || developerId === r.assignee?.id || !queries.assignReport || (isReassignment && !reasonValid(assignmentReason))} onClick={() => void submit("assignReport", { reportId: r.id, expectedVersion: r.version, developerId, ...(isReassignment ? { reason: assignmentReason.trim() } : {}) }, () => setAssignmentReason(""))}>{isReassignment ? "Reassign" : "Assign"}</button></>}
    </section>
    {r.status === "Resolution proposed" && r.currentProposal && <section aria-label="Resolution review" className="action-panel">
      <h3>Review resolution</h3>
      <label><input type="checkbox" checked={acknowledged} disabled={pending} onChange={(e) => setAck(e.target.checked ? proposalKey : "")} /> I reviewed this proposal</label>
      <button disabled={pending || !acknowledged || !queries.closeReport} onClick={() => void submit("closeReport", { reportId: r.id, expectedVersion: r.version, proposalId: r.currentProposal!.id, proposalVersion: r.currentProposal!.version, reviewed: true }, () => setAck(""))}>Close</button>
      <label>Return reason<input aria-label="Return reason" value={returnReason} disabled={pending} onChange={(e) => setReturnReason(e.target.value)} /></label>
      <button disabled={pending || !reasonValid(returnReason) || !queries.returnResolution} onClick={() => void submit("returnResolution", { reportId: r.id, expectedVersion: r.version, proposalId: r.currentProposal!.id, proposalVersion: r.currentProposal!.version, reason: returnReason.trim() }, () => setReturnReason(""))}>Return resolution</button>
    </section>}
    {r.status === "Closed" && <section aria-label="Reopen report" className="action-panel"><h3>Reopen report</h3><label>Reopen reason<input aria-label="Reopen reason" value={reopenReason} disabled={pending} onChange={(e) => setReopenReason(e.target.value)} /></label><button disabled={pending || !reasonValid(reopenReason) || !queries.reopenReport} onClick={() => void submit("reopenReport", { reportId: r.id, expectedVersion: r.version, reason: reopenReason.trim() }, () => setReopenReason(""))}>Reopen</button></section>}
    <section aria-label="Discussion" className="action-panel"><h3>Add discussion comment</h3><label>Add comment<textarea aria-label="Add comment" value={comment} disabled={pending} onChange={(e) => setComment(e.target.value)} /></label><button disabled={pending || !commentValid(comment) || !queries.addComment} onClick={() => void submit("addComment", { reportId: r.id, expectedVersion: r.version, text: comment.trim() }, () => setComment(""))}>Add comment</button>{error && <div role="alert"><p>{messages[error.code]}</p>{(error.code === "conflict" || error.code === "invalidState") && <button type="button" onClick={refreshReport}>Refresh report</button>}</div>}</section>
  </>;
}

function Report({ detail: r, developers, queries, pending, error, runAction, clearError, refreshReport }: { detail: Detail; developers: Person[]; queries: Queries; pending: boolean; error?: QueryError; runAction: (kind: ActionName, input: ActionInput) => Promise<boolean>; clearError: () => void; refreshReport: () => void }) {
  return (
    <article>
      <h2>{r.title}</h2>
      <p>
        {r.status} · {r.priority} · version {r.version}
      </p>
      <dl>
        <dt>Report ID</dt>
        <dd>{r.id}</dd>
        <dt>Reporter</dt>
        <dd>{r.reporter.displayName}</dd>
        <dt>Assignee</dt>
        <dd>{r.assignee?.displayName ?? "Unassigned"}</dd>
        <dt>Created</dt>
        <dd>{r.createdAt}</dd>
        <dt>Updated</dt>
        <dd>{r.updatedAt}</dd>
      </dl>
      {(queries.updatePriority || queries.assignReport || queries.returnResolution || queries.closeReport || queries.reopenReport || queries.addComment) && <ActionControls r={r} developers={developers} queries={queries} pending={pending} error={error} runAction={runAction} clearError={clearError} refreshReport={refreshReport} />}
      <section>
        <h3>Description</h3>
        <div className="markdown-view">
          <MarkdownView source={r.description} />
        </div>
      </section>
      {(
        [
          "reproductionSteps",
          "expectedBehavior",
          "actualBehavior",
          "environment",
        ] as const
      ).map((key, i) => (
        <section key={key}>
          <h3>
            {
              [
                "Reproduction steps",
                "Expected behavior",
                "Actual behavior",
                "Environment",
              ][i]
            }
          </h3>
          <p className="text">{r[key]}</p>
        </section>
      ))}
      {(["notes", "responses", "comments"] as const).map((key) => (
        <section key={key}>
          <h3>
            {key === "notes"
              ? "Investigation notes"
              : key === "responses"
                ? "Responses"
                : "Discussion"}
          </h3>
          {r[key].length ? (
            r[key].map((e) => (
              <div key={e.id}>
                <p className="text">{e.text}</p>
                <small>
                  {e.author.displayName} · {e.createdAt}
                </small>
              </div>
            ))
          ) : (
            <p>No entries.</p>
          )}
        </section>
      ))}
      <section>
        <h3>Checklist</h3>
        {r.checklist.length ? (
          <ul>
            {r.checklist.map((c) => (
              <li key={c.id}>
                {c.completed ? "Complete" : "Pending"}: {c.text}
              </li>
            ))}
          </ul>
        ) : (
          <p>No checklist items.</p>
        )}
      </section>
      <section>
        <h3>Current proposal</h3>
        {r.currentProposal ? (
          <>
            <p className="text">{r.currentProposal.text}</p>
            <small>
              {r.currentProposal.author.displayName} ·{" "}
              {r.currentProposal.createdAt} · version{" "}
              {r.currentProposal.version}
            </small>
          </>
        ) : (
          <p>No current proposal.</p>
        )}
      </section>
      <section>
        <h3>History</h3>
        {r.history.length ? (
          r.history.map((h) => (
            <div key={h.id}>
              <h4>{h.type}</h4>
              <p className="text">{h.text}</p>
              <small>
                {h.actor.displayName} · {h.createdAt}
              </small>
            </div>
          ))
        ) : (
          <p>No history entries.</p>
        )}
      </section>
    </article>
  );
}
