import { useEffect, useState } from "react";
import { ApiError, currentUser, signOut, type User } from "./api";
import { BugIcon } from "./icons";
import { NewReport } from "./NewReport";
import { ReportList } from "./ReportList";
import { ReportView } from "./ReportView";
import { Avatar } from "./ui";

type Session = { kind: "checking" } | { kind: "signed-out" } | { kind: "denied" } | { kind: "error" } | { kind: "active"; user: User };

type Route = { name: "list"; page: number } | { name: "new" } | { name: "report"; id: string };

export function parseRoute(hash: string): Route {
  const path = hash.replace(/^#/, "") || "/";
  if (path === "/new") return { name: "new" };
  const report = /^\/reports\/([^/?#]+)$/.exec(path);
  if (report) return { name: "report", id: decodeURIComponent(report[1]!) };
  const page = /^\/\?page=(\d{1,6})$/.exec(path);
  return { name: "list", page: page ? Math.max(1, Number(page[1])) : 1 };
}

function useRoute(): Route {
  const [route, setRoute] = useState(() => parseRoute(window.location.hash));
  useEffect(() => {
    const update = () => {
      setRoute(parseRoute(window.location.hash));
      document.documentElement.scrollTop = 0;
    };
    window.addEventListener("hashchange", update);
    return () => window.removeEventListener("hashchange", update);
  }, []);
  return route;
}

const navigateToRoot = (path: string) => window.location.replace(path);

export function App({ navigate = navigateToRoot }: { navigate?: (path: string) => void } = {}) {
  const [session, setSession] = useState<Session>({ kind: "checking" });
  const [attempt, setAttempt] = useState(0);
  const [logoutError, setLogoutError] = useState("");
  const [leaving, setLeaving] = useState(false);
  const route = useRoute();
  // "Back to your reports" returns to the list page the Reporter came from.
  const [listPage, setListPage] = useState(1);
  useEffect(() => {
    if (route.name === "list") setListPage(route.page);
  }, [route]);
  const backHref = listPage === 1 ? "#/" : `#/?page=${listPage}`;

  useEffect(() => {
    const controller = new AbortController();
    setSession({ kind: "checking" });
    currentUser(controller.signal)
      .then((user) => {
        if (controller.signal.aborted) return;
        setSession(user.active && user.role === "REPORTER" ? { kind: "active", user } : { kind: "denied" });
      })
      .catch((cause: unknown) => {
        if (controller.signal.aborted) return;
        setSession(cause instanceof ApiError && cause.status === 401 ? { kind: "signed-out" } : { kind: "error" });
      });
    return () => controller.abort();
  }, [attempt]);

  async function leave() {
    if (leaving) return;
    setLeaving(true);
    setLogoutError("");
    try {
      await signOut();
      setSession({ kind: "signed-out" });
    } catch {
      setLogoutError("Unable to sign out. Please try again.");
    } finally {
      setLeaving(false);
    }
  }

  useEffect(() => {
    if (session.kind === "signed-out") navigate("/");
  }, [session.kind, navigate]);

  const user = session.kind === "active" ? session.user : null;

  return (
    <>
      <header className="app-header">
        <a className="app-brand" href="#/">
          <BugIcon /> <span>Bug reports</span>
        </a>
        {user && (
          <nav className="app-nav" aria-label="Account">
            <a className="app-nav-link" href="/account/">Account</a>
            <a className="app-nav-link" href={backHref} aria-current={route.name === "list" ? "page" : undefined}>
              Your reports
            </a>
            <a className="app-nav-link" href="#/new" aria-current={route.name === "new" ? "page" : undefined}>
              New report
            </a>
            <span className="app-user">
              <Avatar name={user.displayName} id={user.id} size={24} />
              <span className="app-user-name">{user.displayName}</span>
            </span>
            <button type="button" className="btn btn-header" disabled={leaving} onClick={() => void leave()}>
              Sign out
            </button>
          </nav>
        )}
      </header>
      <main>
        {session.kind === "checking" && (
          <div className="container">
            <p className="muted" role="status">Checking your session…</p>
          </div>
        )}
        {logoutError && <p role="alert" className="flash flash-error">{logoutError}</p>}
        {session.kind === "signed-out" && (
          <div className="container narrow">
            <p role="status">Returning to sign in...</p>
            <a href="/">Go to sign in</a>
          </div>
        )}
        {session.kind === "denied" && (
          <div className="container narrow">
            <p role="alert" className="flash flash-error">This page is for Reporter accounts. Sign in with an active Reporter account.</p>
            <button type="button" className="btn" disabled={leaving} onClick={() => void leave()}>Sign out</button>
          </div>
        )}
        {session.kind === "error" && (
          <div className="container narrow">
            <p role="alert" className="flash flash-error">Couldn't check your session.</p>
            <button type="button" className="btn" onClick={() => setAttempt((n) => n + 1)}>Retry</button>
          </div>
        )}
        {user && route.name === "list" && (
          <ReportList
            page={route.page}
            reporterName={user.displayName}
            onPage={(page) => {
              window.location.hash = page === 1 ? "#/" : `#/?page=${page}`;
            }}
          />
        )}
        {user && route.name === "new" && (
          <NewReport user={user} backHref={backHref} onCreated={(id) => (window.location.hash = `#/reports/${id}`)} />
        )}
        {user && route.name === "report" && (
          <ReportView
            id={route.id}
            user={user}
            backHref={backHref}
            onSignedOut={() => setSession({ kind: "signed-out" })}
          />
        )}
      </main>
    </>
  );
}
