import { useEffect, useState, type FormEvent } from "react";
import { ApiError, currentUser, signIn, signOut, type User } from "./api";
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

export function App() {
  const [session, setSession] = useState<Session>({ kind: "checking" });
  const [attempt, setAttempt] = useState(0);
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
    try {
      await signOut();
    } finally {
      setSession({ kind: "signed-out" });
    }
  }

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
            <button type="button" className="btn btn-header" onClick={() => void leave()}>
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
        {session.kind === "signed-out" && <SignIn onSignedIn={() => setAttempt((n) => n + 1)} />}
        {session.kind === "denied" && (
          <div className="container narrow">
            <p role="alert" className="flash flash-error">This page is for Reporter accounts. Sign in with an active Reporter account.</p>
            <button type="button" className="btn" onClick={() => void leave()}>Sign out</button>
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

function SignIn({ onSignedIn }: { onSignedIn: () => void }) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState("");

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setProblem("");
    try {
      await signIn(username, password);
      setPassword("");
      onSignedIn();
    } catch {
      setProblem("Incorrect username or password.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="signin">
      <BugIcon className="signin-logo" />
      <h1>Sign in to report bugs</h1>
      <form className="box signin-box" onSubmit={(event) => void submit(event)}>
        {problem && <p role="alert" className="flash flash-error">{problem}</p>}
        <label className="field-label" htmlFor="username">Username</label>
        <input id="username" className="input" autoComplete="username" value={username} disabled={busy} onChange={(event) => setUsername(event.target.value)} />
        <label className="field-label" htmlFor="password">Password</label>
        <input id="password" className="input" type="password" autoComplete="current-password" value={password} disabled={busy} onChange={(event) => setPassword(event.target.value)} />
        <button type="submit" className="btn btn-primary btn-block" disabled={busy}>
          {busy ? "Signing in…" : "Sign in"}
        </button>
      </form>
    </div>
  );
}
