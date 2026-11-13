import { useEffect, useRef, useState } from "react";

type AccessLoss = "session" | "report";
type VersionChange = { previous: number; current: number };
type Draft = { text: string; draftRevision: number; reportVersion: number };

export function DeveloperSharedNotes(props: {
  reportId: string;
  status: string;
  onAccessLost?: (reason: AccessLoss, reportId: string) => void;
  startedVersion?: VersionChange;
  onReportVersionChange?: (previous: number, current: number) => void;
}) {
  return <SharedNotesForReport key={props.reportId} {...props} />;
}

function SharedNotesForReport({ reportId, status, onAccessLost, startedVersion, onReportVersionChange }: {
  reportId: string;
  status: string;
  onAccessLost?: (reason: AccessLoss, reportId: string) => void;
  startedVersion?: VersionChange;
  onReportVersionChange?: (previous: number, current: number) => void;
}) {
  const [text, setText] = useState("");
  const [savedText, setSavedText] = useState("");
  const [draftRevision, setDraftRevision] = useState(0);
  const [reportVersion, setReportVersion] = useState(0);
  const [loaded, setLoaded] = useState(false);
  const [loading, setLoading] = useState(true);
  const [pending, setPending] = useState(false);
  const [conflicted, setConflicted] = useState(false);
  const [mustSaveBeforeShare, setMustSaveBeforeShare] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const action = useRef<AbortController | null>(null);
  const transition = useRef(startedVersion);
  transition.current = startedVersion;
  const canEdit = ["ASSIGNED", "IN_PROGRESS", "RESOLUTION_PROPOSED"].includes(status);
  const path = `/api/developer/reports/${encodeURIComponent(reportId)}/shared-notes`;

  useEffect(() => {
    if (startedVersion) setReportVersion((version) => version === startedVersion.previous ? startedVersion.current : version);
  }, [startedVersion]);
  useEffect(() => () => { action.current?.abort(); }, []);

  async function denied(response: Response, signal: AbortSignal) {
    if (signal.aborted) return true;
    if (response.status === 401) { onAccessLost?.("session", reportId); return true; }
    if (response.status === 404) { onAccessLost?.("report", reportId); return true; }
    if (response.status === 403) {
      try {
        const value: unknown = await response.clone().json();
        if (signal.aborted) return true;
        if (isRecord(value) && value.code === "FORBIDDEN") { onAccessLost?.("report", reportId); return true; }
      } catch { /* Other denials are displayed as errors. */ }
    }
    return false;
  }

  async function readDraft(signal: AbortSignal): Promise<Draft | null> {
    const response = await fetch(`${path}/draft`, { method: "GET", credentials: "same-origin", cache: "no-store", headers: { Accept: "application/json" }, signal });
    if (await denied(response, signal)) return null;
    if (!response.ok) throw new Error("Unable to load shared notes.");
    const value: unknown = await response.json();
    if (!isDraft(value)) throw new Error("Unable to load shared notes.");
    return value;
  }

  useEffect(() => {
    const controller = new AbortController();
    void readDraft(controller.signal).then((value) => {
      if (!value || controller.signal.aborted) return;
      setText(value.text); setSavedText(value.text); setDraftRevision(value.draftRevision);
      const change = transition.current;
      setReportVersion(change && value.reportVersion === change.previous ? change.current : value.reportVersion);
      setLoaded(true);
    }).catch(() => { if (!controller.signal.aborted) setError("Unable to load shared notes."); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [reportId]);

  async function csrf(signal: AbortSignal) {
    const response = await fetch("/api/auth/csrf", { method: "GET", credentials: "same-origin", cache: "no-store", headers: { Accept: "application/json" }, signal });
    if (await denied(response, signal)) return null;
    if (!response.ok) throw new Error("Unable to verify this request.");
    const value: unknown = await response.json();
    if (!isRecord(value) || typeof value.token !== "string" || !value.token || value.headerName !== "X-CSRF-TOKEN") throw new Error("Unable to verify this request.");
    return value.token;
  }

  async function save() {
    const controller = new AbortController(); action.current = controller;
    setPending(true); setError(""); setMessage("");
    try {
      const token = await csrf(controller.signal); if (!token || controller.signal.aborted) return;
      const response = await fetch(`${path}/draft`, { method: "PUT", credentials: "same-origin", cache: "no-store", headers: { Accept: "application/json", "Content-Type": "application/json", "X-CSRF-TOKEN": token }, body: JSON.stringify({ text, expectedDraftRevision: draftRevision }), signal: controller.signal });
      if (await denied(response, controller.signal)) return;
      if (response.status === 409) { setConflicted(true); throw new Error("The note changed or can no longer be edited; your text is still here."); }
      if (!response.ok) throw new Error("Unable to save shared notes; your text is still here.");
      const value: unknown = await response.json();
      if (!isRecord(value) || !isRevision(value.draftRevision)) throw new Error("Unable to save shared notes; your text is still here.");
      if (controller.signal.aborted) return;
      setDraftRevision(value.draftRevision); setSavedText(text); setConflicted(false); setMustSaveBeforeShare(false); setMessage("Shared note draft saved privately.");
    } catch (cause) { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : "Unable to save shared notes; your text is still here."); }
    finally { if (!controller.signal.aborted) setPending(false); }
  }

  async function share() {
    setError(""); setMessage("");
    if (!savedText.trim()) { setMessage("Save a nonempty shared note before sharing."); return; }
    const controller = new AbortController(); action.current = controller;
    setPending(true); setError(""); setMessage("");
    try {
      const token = await csrf(controller.signal); if (!token || controller.signal.aborted) return;
      const response = await fetch(`${path}/share`, { method: "POST", credentials: "same-origin", cache: "no-store", headers: { Accept: "application/json", "Content-Type": "application/json", "X-CSRF-TOKEN": token }, body: JSON.stringify({ expectedDraftRevision: draftRevision, expectedReportVersion: reportVersion }), signal: controller.signal });
      if (await denied(response, controller.signal)) return;
      if (response.status === 409) { setConflicted(true); throw new Error("The note or report changed; your text is still here. Refresh the saved revision before trying again."); }
      if (!response.ok) throw new Error("Unable to share the saved note; your text is still here.");
      const value: unknown = await response.json();
      if (!isRecord(value) || !isRevision(value.reportVersion) || typeof value.changed !== "boolean") throw new Error("Unable to share the saved note; your text is still here.");
      if (controller.signal.aborted) return;
      setReportVersion(value.reportVersion); setConflicted(false);
      setMessage(value.changed ? "Saved note shared with Maintainers." : "The saved note is already shared.");
      onReportVersionChange?.(reportVersion, value.reportVersion);
    } catch (cause) { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : "Unable to share the saved note; your text is still here."); }
    finally { if (!controller.signal.aborted) setPending(false); }
  }

  async function refresh() {
    const controller = new AbortController(); action.current = controller;
    setPending(true); setError(""); setMessage("");
    try {
      const value = await readDraft(controller.signal); if (!value || controller.signal.aborted) return;
      setDraftRevision(value.draftRevision); setReportVersion(value.reportVersion); setSavedText(value.text); setConflicted(false); setMustSaveBeforeShare(true);
      if (reportVersion !== value.reportVersion) onReportVersionChange?.(reportVersion, value.reportVersion);
      setMessage("Saved revision refreshed. Your unsaved text is still here; save it before sharing.");
    } catch { if (!controller.signal.aborted) setError("Unable to refresh shared notes; your text is still here."); }
    finally { if (!controller.signal.aborted) setPending(false); }
  }

  return <section className="developer-private-notes" aria-label="Shared investigation notes">
    <h3>Shared investigation notes</h3>
    <p>Save a separate draft, then share the last saved version with Maintainers. Private notes are never shared.</p>
    {status === "RESOLUTION_PROPOSED" && <p>Sharing a changed note returns this report to In progress.</p>}
    {loading ? <p role="status">Loading shared notes…</p> : loaded && <>
      <label htmlFor="shared-note-draft">Shared investigation note draft</label>
      <textarea id="shared-note-draft" value={text} maxLength={10000} disabled={!canEdit || pending} onChange={(event) => setText(event.target.value)} />
      {canEdit && <><button type="button" onClick={() => void save()} disabled={pending || conflicted}>Save shared draft</button>{" "}
        <button type="button" onClick={() => void share()} disabled={pending || conflicted || mustSaveBeforeShare}>Share saved note</button></>}
      {conflicted && <button type="button" onClick={() => void refresh()} disabled={pending}>Refresh saved revision</button>}
    </>}
    {error && <p role="alert">{error}</p>}
    {message && <p role="status">{message}</p>}
  </section>;
}

function isRecord(value: unknown): value is Record<string, unknown> { return typeof value === "object" && value !== null && !Array.isArray(value); }
function isRevision(value: unknown): value is number { return typeof value === "number" && Number.isSafeInteger(value) && value >= 0; }
function isDraft(value: unknown): value is Draft { return isRecord(value) && typeof value.text === "string" && isRevision(value.draftRevision) && isRevision(value.reportVersion); }
