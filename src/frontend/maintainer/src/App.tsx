import { useEffect, useRef, useState } from "react";
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
} from "./domain";
type Load<T> = {
  state: "loading" | "ready" | "error";
  data?: T;
  error?: QueryError;
};
const loading = <T,>(): Load<T> => ({ state: "loading" });
const failure = (e: unknown) =>
  e instanceof QueryError ? e : new QueryError("network");
export function App({
  queries,
  synthetic = false,
}: {
  queries: Queries;
  synthetic?: boolean;
}) {
  const [auth, setAuth] = useState<"loading" | "ready" | "error">("loading"),
    [authError, setAuthError] = useState<QueryError>(),
    [session, setSession] = useState(0),
    [refresh, setRefresh] = useState(0);
  const [filters, setFilters] = useState<Filters>({ offset: 0, limit: 50 }),
    [list, setList] = useState<Load<Page>>(loading),
    [devs, setDevs] = useState<Load<Person[]>>(loading),
    [selected, setSelected] = useState<string>(),
    [detail, setDetail] = useState<Load<Detail>>(loading),
    [listRetry, setListRetry] = useState(0),
    [devRetry, setDevRetry] = useState(0),
    [detailRetry, setDetailRetry] = useState(0);
  const epoch = useRef(0),
    listEpoch = useRef(0),
    detailEpoch = useRef(0),
    devEpoch = useRef(0),
    controllers = useRef(new Set<AbortController>());
  const control = () => {
    const c = new AbortController();
    controllers.current.add(c);
    return c;
  };
  const clear = () => {
    controllers.current.forEach((c) => c.abort());
    controllers.current.clear();
    listEpoch.current++;
    detailEpoch.current++;
    devEpoch.current++;
    setList(loading());
    setDevs(loading());
    setSelected(undefined);
    setDetail(loading());
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
  }, [queries, refresh]);
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
    setDetail(loading());
    queries
      .getReport(selected, c.signal)
      .finally(() => controllers.current.delete(c))
      .then(
        (data) => {
          if (g === epoch.current && n === detailEpoch.current)
            setDetail({ state: "ready", data });
        },
        (e) => {
          const error = failure(e);
          if (g !== epoch.current || error.code === "cancelled") return;
          if (error.code === "session" || error.code === "denied") {
            deny(error, g);
            return;
          }
          if (n !== detailEpoch.current) return;
          setDetail({ state: "error", error });
        },
      );
    return () => {
      c.abort();
      detailEpoch.current++;
    };
  }, [queries, auth, session, selected, detailRetry]);
  const change = (next: Filters) => {
    listEpoch.current++;
    detailEpoch.current++;
    setList(loading());
    setSelected(undefined);
    setDetail(loading());
    setFilters(next);
  };
  const select = (id: string) => {
    if (selected === id) return;
    detailEpoch.current++;
    setDetail(loading());
    setSelected(id);
  };
  const reload = () => {
    epoch.current++;
    clear();
    setAuth("loading");
    setRefresh((v) => v + 1);
  };
  return (
    <main>
      <header>
        <div>
          <p className="eyebrow">BUGBUSTERS / MAINTAINER</p>
          <h1>Report queue</h1>
          <p>Review reports and investigation evidence.</p>
        </div>
        <button onClick={reload}>Refresh session and queue</button>
      </header>
      {synthetic && (
        <p className="banner">
          Synthetic demonstration data · development only
        </p>
      )}
      {auth === "loading" ? (
        <p role="status">Checking access…</p>
      ) : auth === "error" ? (
        <div role="alert">
          <p>{authError?.message}</p>
          <button onClick={reload}>Retry access check</button>
        </div>
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
              ) : detail.state === "loading" ? (
                <p role="status">Loading report details…</p>
              ) : detail.state === "error" ? (
                <ErrorView
                  error={detail.error!}
                  retry={() => setDetailRetry((v) => v + 1)}
                />
              ) : (
                <Report detail={detail.data!} />
              )}
            </section>
          </div>
        </>
      )}
    </main>
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
function Report({ detail: r }: { detail: Detail }) {
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
      {(
        [
          "description",
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
                "Description",
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
