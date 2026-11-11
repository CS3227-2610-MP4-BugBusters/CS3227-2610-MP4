import { useEffect, useState } from "react";

type NotesResponse = { text: string; revision: number };
type AccessLoss = "session" | "report";

export function DeveloperPrivateNotes({
  reportId,
  status,
  onAccessLost,
}: {
  reportId: string;
  status: string;
  onAccessLost?: (reason: AccessLoss, reportId: string) => void;
}) {
  const [text, setText] = useState("");
  const [revision, setRevision] = useState(0);
  const [loading, setLoading] = useState(true);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [conflicted, setConflicted] = useState(false);
  const canEdit = ["ASSIGNED", "IN_PROGRESS", "RESOLUTION_PROPOSED"].includes(status);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError("");
    setText("");
    setRevision(0);
    void fetch(`/api/developer/reports/${encodeURIComponent(reportId)}/notes`, {
      method: "GET", credentials: "same-origin", cache: "no-store", headers: { Accept: "application/json" },
      signal: controller.signal,
    }).then(async (response) => {
      if (controller.signal.aborted) return;
      if (await handleAccessLoss(response)) return;
      if (response.status === 404) { onAccessLost?.("report", reportId); return; }
      if (!response.ok) throw new Error("Unable to load private notes.");
      const value: unknown = await response.json();
      if (!isNotes(value)) throw new Error("Unable to load private notes.");
      if (!controller.signal.aborted) { setText(value.text); setRevision(value.revision); }
    }).catch((cause: unknown) => {
      if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : "Unable to load private notes.");
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [reportId]);

  async function save() {
    setPending(true);
    setError("");
    setMessage("");
    try {
      const csrf = await fetch("/api/auth/csrf", {
        method: "GET", credentials: "same-origin", cache: "no-store", headers: { Accept: "application/json" },
      });
      if (csrf.status === 401) { onAccessLost?.("session", reportId); return; }
      if (!csrf.ok) throw new Error("Unable to verify this request.");
      const token: unknown = await csrf.json();
      if (!isCsrf(token)) throw new Error("Unable to verify this request.");
      const response = await fetch(`/api/developer/reports/${encodeURIComponent(reportId)}/notes`, {
        method: "PUT", credentials: "same-origin", cache: "no-store",
        headers: { Accept: "application/json", "Content-Type": "application/json", [token.headerName]: token.token },
        body: JSON.stringify({ text, expectedRevision: revision }),
      });
      if (await handleAccessLoss(response)) return;
      if (response.status === 404) { onAccessLost?.("report", reportId); return; }
      if (response.status === 409) {
        setConflicted(true);
        throw new Error("Notes changed or can no longer be edited. Refresh the saved revision before saving; your text is still here.");
      }
      if (!response.ok) throw new Error("Unable to save private notes. Your text is still here.");
      const value: unknown = await response.json();
      if (!isRevisionResponse(value)) throw new Error("Unable to save private notes. Your text is still here.");
      setRevision(value.revision);
      setConflicted(false);
      setError("");
      setMessage("Private notes saved.");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Unable to save private notes. Your text is still here.");
    } finally {
      setPending(false);
    }
  }

  async function refreshRevision() {
    setPending(true);
    setError("");
    try {
      const response = await fetch(`/api/developer/reports/${encodeURIComponent(reportId)}/notes`, {
        method: "GET", credentials: "same-origin", cache: "no-store", headers: { Accept: "application/json" },
      });
      if (await handleAccessLoss(response)) return;
      if (response.status === 404) { onAccessLost?.("report", reportId); return; }
      if (!response.ok) throw new Error("Unable to refresh private notes; your text is still here.");
      const value: unknown = await response.json();
      if (!isNotes(value)) throw new Error("Unable to refresh private notes; your text is still here.");
      setRevision(value.revision);
      setConflicted(false);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Unable to refresh private notes; your text is still here.");
    } finally {
      setPending(false);
    }
  }

  async function handleAccessLoss(response: Response): Promise<boolean> {
    if (response.status === 401) { onAccessLost?.("session", reportId); return true; }
    if (response.status !== 403) return false;
    try {
      const value: unknown = await response.clone().json();
      if (isRecord(value) && value.code === "FORBIDDEN") {
        onAccessLost?.("report", reportId);
        return true;
      }
    } catch { /* Other 403 responses, including CSRF errors, stay in the editor. */ }
    return false;
  }

  return <section className="developer-private-notes" aria-label="Private investigation notes">
    <h3>Private investigation notes</h3>
    {loading ? <p role="status">Loading private notes…</p> : <>
      <label htmlFor="private-notes">Notes (visible only to the assigned Developer)</label>
      <textarea id="private-notes" value={text} maxLength={10000} disabled={!canEdit || pending}
        onChange={(event) => setText(event.target.value)} />
      {canEdit && <button type="button" onClick={() => void save()} disabled={pending}>
        {pending ? "Saving…" : "Save notes"}
      </button>}
      {conflicted && <button type="button" onClick={() => void refreshRevision()} disabled={pending}>
        Refresh saved revision
      </button>}
    </>}
    {error && <p role="alert">{error}</p>}
    {message && <p role="status">{message}</p>}
  </section>;
}

function isNotes(value: unknown): value is NotesResponse {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    && typeof (value as NotesResponse).text === "string" && isRevision((value as NotesResponse).revision);
}

function isRevisionResponse(value: unknown): value is { revision: number } {
  return typeof value === "object" && value !== null && isRevision((value as { revision?: unknown }).revision);
}

function isRevision(value: unknown): value is number {
  return Number.isSafeInteger(value) && (value as number) >= 0;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isCsrf(value: unknown): value is { token: string; headerName: "X-CSRF-TOKEN" } {
  return typeof value === "object" && value !== null
    && typeof (value as { token?: unknown }).token === "string"
    && (value as { token: string }).token.length > 0
    && (value as { headerName?: unknown }).headerName === "X-CSRF-TOKEN";
}
