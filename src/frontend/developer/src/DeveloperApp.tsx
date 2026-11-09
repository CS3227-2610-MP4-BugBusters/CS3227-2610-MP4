import { useEffect, useState, type FormEvent } from "react";
import { DeveloperChecklist } from "./DeveloperChecklist";

type User = {
  id: string;
  displayName: string;
  role: string;
  active: boolean;
};

type Report = { id: string; title: string; status: string };
type SessionState = "checking" | "signed-out" | "active" | "denied" | "error";

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
  const [selectedReportId, setSelectedReportId] = useState("");
  const [selectedReportTitle, setSelectedReportTitle] = useState("");
  const [listState, setListState] = useState<"loading" | "ready" | "error">("loading");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [sessionAttempt, setSessionAttempt] = useState(0);
  const [listAttempt, setListAttempt] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setSessionState("checking");
    setUser(null);
    setReports([]);
    setSelectedReportId("");
    setSelectedReportTitle("");
    setError("");
    void readCurrentUser(controller.signal)
      .then((current) => {
        if (controller.signal.aborted) return;
        if (!current.active || current.role !== "DEVELOPER") {
          setSessionState("denied");
          return;
        }
        setUser(current);
        setSessionState("active");
      })
      .catch((cause: unknown) => {
        if (controller.signal.aborted) return;
        if (cause instanceof SessionExpired) {
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
    void readAssignedReports(controller.signal)
      .then((items) => {
        if (controller.signal.aborted) return;
        setReports(items);
        setListState("ready");
      })
      .catch(() => {
        if (controller.signal.aborted) return;
        setListState("error");
        setError("Unable to load assigned reports.");
      });
    return () => controller.abort();
  }, [sessionState, user?.id, listAttempt]);

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
      const current = await readCurrentUser();
      if (!current.active || current.role !== "DEVELOPER") {
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
      setUser(null);
      setReports([]);
      setSelectedReportId("");
      setSelectedReportTitle("");
      setPassword("");
      setSessionState("signed-out");
    } catch {
      setError("Sign out failed. Please retry.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main>
      <h1>Developer reports</h1>
      {sessionState === "checking" && <p role="status">Checking your session…</p>}
      {sessionState === "signed-out" && (
        <section aria-label="Sign in">
          <h2>Sign in</h2>
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
          <p>Signed in as {user.displayName}</p>
          <button type="button" onClick={() => void signOut()} disabled={busy}>
            Sign out
          </button>
          <section aria-label="Assigned reports">
            <h2>Assigned reports</h2>
            {listState === "loading" && <p role="status">Loading assigned reports…</p>}
            {listState === "error" && (
              <>
                <p role="alert">{error}</p>
                <button type="button" onClick={() => setListAttempt((attempt) => attempt + 1)}>
                  Retry
                </button>
              </>
            )}
            {listState === "ready" && reports.length === 0 && <p>No assigned reports.</p>}
            {listState === "ready" && reports.length > 0 && (
              <ul>
                {reports.map((report) => (
                  <li key={report.id}>
                    <button
                      type="button"
                      aria-pressed={selectedReportId === report.id}
                      onClick={() => {
                        setSelectedReportId(report.id);
                        setSelectedReportTitle(report.title);
                      }}
                    >
                      <span>{report.title}</span> ({report.status})
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>
          {selectedReportId && (
            <section aria-label="Selected report">
              <h2>{selectedReportTitle}</h2>
              <DeveloperChecklist key={selectedReportId} reportId={selectedReportId} />
            </section>
          )}
        </>
      )}
    </main>
  );
}

class SessionExpired extends Error {}

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

async function readAssignedReports(signal: AbortSignal): Promise<Report[]> {
  const response = await fetch(reportsPath, {
    method: "GET",
    credentials: "same-origin",
    cache: "no-store",
    headers: jsonHeaders,
    signal,
  });
  if (!response.ok) throw new Error("Unable to load assigned reports.");
  const value: unknown = await response.json();
  if (!isRecord(value) || !Array.isArray(value.items)) {
    throw new Error("Unable to load assigned reports.");
  }
  const reports = value.items.map(parseReport);
  if (reports.some((report) => report === null)) {
    throw new Error("Unable to load assigned reports.");
  }
  return reports as Report[];
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
