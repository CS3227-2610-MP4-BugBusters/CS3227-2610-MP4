import { useEffect, useRef, useState } from "react";

type NotificationType = "ASSIGNED" | "UNASSIGNED" | "RESOLUTION_RETURNED" | "REOPENED";
type Notification = {
  id: string;
  type: NotificationType;
  createdAt: string;
  read: boolean;
  reportId: string | null;
};

const path = "/api/developer/notifications";
const headers = { Accept: "application/json" };
const labels: Record<NotificationType, string> = {
  ASSIGNED: "New report assigned",
  UNASSIGNED: "Assignment removed",
  RESOLUTION_RETURNED: "Resolution returned",
  REOPENED: "Report reopened",
};

export function DeveloperNotifications({
  onSessionExpired,
  onOpenReport,
}: {
  onSessionExpired: () => void;
  onOpenReport: (reportId: string) => void;
}) {
  const [items, setItems] = useState<Notification[]>([]);
  const [state, setState] = useState<"loading" | "ready" | "error">("loading");
  const [pendingId, setPendingId] = useState("");
  const sessionExpiredRef = useRef(onSessionExpired);
  const readControllerRef = useRef<AbortController | null>(null);
  sessionExpiredRef.current = onSessionExpired;

  useEffect(() => () => readControllerRef.current?.abort(), []);

  useEffect(() => {
    const controller = new AbortController();
    setState("loading");
    void fetch(path, {
      method: "GET",
      credentials: "same-origin",
      cache: "no-store",
      headers,
      signal: controller.signal,
    }).then(async (response) => {
      if (controller.signal.aborted) return;
      if (response.status === 401) {
        sessionExpiredRef.current();
        return;
      }
      if (!response.ok) throw new Error("Unable to load notifications.");
      const value: unknown = await response.json();
      if (controller.signal.aborted) return;
      const parsed = parseNotifications(value);
      if (!parsed) throw new Error("Unable to load notifications.");
      setItems(parsed);
      setState("ready");
    }).catch(() => {
      if (!controller.signal.aborted) setState("error");
    });
    return () => controller.abort();
  }, []);

  async function markRead(id: string) {
    if (pendingId) return;
    readControllerRef.current?.abort();
    const controller = new AbortController();
    readControllerRef.current = controller;
    setPendingId(id);
    try {
      const csrfResponse = await fetch("/api/auth/csrf", {
        method: "GET", credentials: "same-origin", cache: "no-store", headers,
        signal: controller.signal,
      });
      if (controller.signal.aborted) return;
      if (csrfResponse.status === 401) { sessionExpiredRef.current(); return; }
      if (!csrfResponse.ok) throw new Error("Request verification failed.");
      const csrf: unknown = await csrfResponse.json();
      if (controller.signal.aborted) return;
      if (!isRecord(csrf) || typeof csrf.token !== "string" || !csrf.token
          || csrf.headerName !== "X-CSRF-TOKEN") throw new Error("Request verification failed.");
      const response = await fetch(`${path}/${encodeURIComponent(id)}/read`, {
        method: "POST",
        credentials: "same-origin",
        cache: "no-store",
        headers: { [csrf.headerName]: csrf.token },
        signal: controller.signal,
      });
      if (controller.signal.aborted) return;
      if (response.status === 401) { sessionExpiredRef.current(); return; }
      if (response.status === 404) throw new Error("Notification is no longer available.");
      if (!response.ok) throw new Error("Unable to update notification.");
      setItems((current) => current.map((item) => item.id === id ? { ...item, read: true } : item));
    } catch {
      if (!controller.signal.aborted) setState("error");
    } finally {
      if (!controller.signal.aborted) setPendingId("");
      if (readControllerRef.current === controller) readControllerRef.current = null;
    }
  }

  return <section className="developer-notifications" aria-label="Notifications">
    <h2>Notifications</h2>
    {state === "loading" && <p role="status" aria-label="Loading notifications">Loading notifications…</p>}
    {state === "error" && <p role="alert" aria-label="Notification error">Unable to load or update notifications.</p>}
    {state === "ready" && items.length === 0 && <p>No notifications.</p>}
    {state === "ready" && items.length > 0 && <ul className="developer-notifications__list">
      {items.map((item) => <li className={item.read ? "is-read" : "is-unread"} key={item.id}>
        <div className="developer-notifications__item">
          <span className="developer-notifications__label">{labels[item.type]}</span>
          <time dateTime={item.createdAt}>{formatTimestamp(item.createdAt)}</time>
          {item.reportId && <button type="button" className="developer-notifications__report"
            onClick={() => onOpenReport(item.reportId!)}>Open report</button>}
          {!item.read && <button type="button" onClick={() => void markRead(item.id)} disabled={pendingId !== ""}>
            {pendingId === item.id ? "Marking read…" : "Mark read"}
          </button>}
          {item.read && <span className="developer-notifications__read">Read</span>}
        </div>
      </li>)}
    </ul>}
  </section>;
}

function parseNotifications(value: unknown): Notification[] | null {
  if (!isRecord(value) || !Array.isArray(value.items) || value.items.length > 50) return null;
  const result: Notification[] = [];
  for (const item of value.items) {
    if (!isRecord(item) || typeof item.id !== "string" || !isNotificationType(item.type)
        || typeof item.createdAt !== "string" || !Number.isFinite(Date.parse(item.createdAt))
        || typeof item.read !== "boolean"
        || !(item.reportId === null || typeof item.reportId === "string")) return null;
    result.push({ id: item.id, type: item.type, createdAt: item.createdAt, read: item.read, reportId: item.reportId });
  }
  return result;
}

function isNotificationType(value: unknown): value is NotificationType {
  return value === "ASSIGNED" || value === "UNASSIGNED"
    || value === "RESOLUTION_RETURNED" || value === "REOPENED";
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function formatTimestamp(value: string) {
  return new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}
