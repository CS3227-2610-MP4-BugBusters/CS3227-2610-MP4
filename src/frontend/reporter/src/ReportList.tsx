import { useEffect, useState } from "react";
import { listReports, type Page, type ReportSummary } from "./api";
import { absoluteTime, errorMessage, relativeTime, shortId, statusInfo } from "./format";
import { BugIcon } from "./icons";
import { StateIcon, StatusLabel } from "./ui";

const PAGE_SIZE = 25;

type Filter = "open" | "closed" | "all";

export function ReportList({ page, onPage, reporterName }: { page: number; onPage: (page: number) => void; reporterName: string }) {
  const [data, setData] = useState<Page<ReportSummary> | null>(null);
  const [error, setError] = useState("");
  const [attempt, setAttempt] = useState(0);
  const [filter, setFilter] = useState<Filter>("all");
  const [query, setQuery] = useState("");

  useEffect(() => {
    const controller = new AbortController();
    setData(null);
    setError("");
    listReports(page, PAGE_SIZE, controller.signal)
      .then((result) => {
        if (!controller.signal.aborted) setData(result);
      })
      .catch((cause: unknown) => {
        if (!controller.signal.aborted) setError(errorMessage(cause, "load your reports"));
      });
    return () => controller.abort();
  }, [page, attempt]);

  const items = data?.items ?? [];
  const openCount = items.filter((item) => statusInfo(item.status).open).length;
  const closedCount = items.length - openCount;
  const needle = query.trim().toLowerCase();
  const shown = items.filter(
    (item) =>
      (filter === "all" || statusInfo(item.status).open === (filter === "open")) &&
      (needle === "" || item.title.toLowerCase().includes(needle)),
  );
  const lastPage = data ? Math.max(1, Math.ceil(data.totalItems / PAGE_SIZE)) : 1;

  return (
    <div className="container">
      <div className="list-toolbar">
        <input
          className="input search"
          type="search"
          placeholder="Filter reports on this page by title"
          aria-label="Filter reports by title"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
        <a className="btn btn-primary" href="#/new">
          New report
        </a>
      </div>

      <section className="box" aria-label="Your reports">
        <header className="box-header list-header">
          <div className="filters" role="group" aria-label="Show">
            <button type="button" className={`filter ${filter === "open" ? "selected" : ""}`} aria-pressed={filter === "open"} onClick={() => setFilter("open")}>
              <StateIcon status="SUBMITTED" /> {openCount} Open
            </button>
            <button type="button" className={`filter ${filter === "closed" ? "selected" : ""}`} aria-pressed={filter === "closed"} onClick={() => setFilter("closed")}>
              <StateIcon status="CLOSED" /> {closedCount} Closed
            </button>
            <button type="button" className={`filter ${filter === "all" ? "selected" : ""}`} aria-pressed={filter === "all"} onClick={() => setFilter("all")}>
              All
            </button>
          </div>
          {data && <span className="muted">{data.totalItems} total</span>}
        </header>

        {!data && !error && <p className="box-row muted" role="status">Loading reports…</p>}
        {error && (
          <div className="box-row">
            <p role="alert" className="flash flash-error">{error}</p>
            <button type="button" className="btn" onClick={() => setAttempt((n) => n + 1)}>Retry</button>
          </div>
        )}
        {data && data.totalItems === 0 && (
          <div className="blankslate">
            <BugIcon className="blankslate-icon" />
            <h2>No reports yet</h2>
            <p className="muted">When something breaks, tell the team about it here.</p>
            <a className="btn btn-primary" href="#/new">New report</a>
          </div>
        )}
        {data && data.totalItems > 0 && shown.length === 0 && (
          <p className="box-row muted">No reports on this page match.</p>
        )}
        {shown.length > 0 && (
          <ul className="report-rows">
            {shown.map((item) => (
              <li key={item.id} className="report-row">
                <StateIcon status={item.status} />
                <div className="report-row-main">
                  <a className="report-title" href={`#/reports/${item.id}`}>
                    {item.title}
                  </a>{" "}
                  <StatusLabel status={item.status} />
                  <div className="muted small">
                    #{shortId(item.id)} opened{" "}
                    <time dateTime={item.createdAt} title={absoluteTime(item.createdAt)}>
                      {relativeTime(item.createdAt)}
                    </time>{" "}
                    by {reporterName}
                    {item.updatedAt !== item.createdAt && (
                      <>
                        {" · "}updated{" "}
                        <time dateTime={item.updatedAt} title={absoluteTime(item.updatedAt)}>
                          {relativeTime(item.updatedAt)}
                        </time>
                      </>
                    )}
                  </div>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>

      {data && lastPage > 1 && (
        <nav className="pagination" aria-label="Pages">
          <button type="button" className="btn" disabled={page <= 1} onClick={() => onPage(page - 1)}>
            Previous
          </button>
          <span className="muted">
            Page {page} of {lastPage}
          </span>
          <button type="button" className="btn" disabled={page >= lastPage} onClick={() => onPage(page + 1)}>
            Next
          </button>
        </nav>
      )}
    </div>
  );
}
