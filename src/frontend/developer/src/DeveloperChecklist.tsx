import { useEffect, useRef, useState } from "react";

type DraftResponse = {
  steps: string[];
  draftRevision: number;
  reportVersion: number;
};

type Suggestions = { steps: string[]; sourceVersion: number };
type CsrfResponse = { token: string; headerName: "X-CSRF-TOKEN" };

const jsonHeaders = { Accept: "application/json" };

type AccessLoss = "session" | "report";
type AccessLossHandler = (reason: AccessLoss, reportId: string) => void;

export function DeveloperChecklist({
  reportId,
  onAccessLost,
  startedVersion,
  onReportVersionChange,
}: {
  reportId: string;
  onAccessLost?: AccessLossHandler;
  startedVersion?: { previous: number; current: number };
  onReportVersionChange?: (previous: number, current: number) => void;
}) {
  return <ChecklistForReport key={reportId} reportId={reportId} onAccessLost={onAccessLost} startedVersion={startedVersion} onReportVersionChange={onReportVersionChange} />;
}

function ChecklistForReport({
  reportId,
  onAccessLost,
  startedVersion,
  onReportVersionChange,
}: {
  reportId: string;
  onAccessLost?: AccessLossHandler;
  startedVersion?: { previous: number; current: number };
  onReportVersionChange?: (previous: number, current: number) => void;
}) {
  const [editor, setEditor] = useState("");
  const [savedSteps, setSavedSteps] = useState<string[]>([]);
  const [draftRevision, setDraftRevision] = useState(0);
  const [reportVersion, setReportVersion] = useState(0);
  const [loading, setLoading] = useState(true);
  const [loaded, setLoaded] = useState(false);
  const [pending, setPending] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [applying, setApplying] = useState(false);
  const [suggestions, setSuggestions] = useState<Suggestions | null>(null);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const startedVersionRef = useRef(startedVersion);
  startedVersionRef.current = startedVersion;
  useEffect(() => {
    if (startedVersion) setReportVersion((version) => version === startedVersion.previous ? startedVersion.current : version);
  }, [startedVersion]);
  const actionController = useRef<AbortController | null>(null);
  const suggestionController = useRef<AbortController | null>(null);
  const applyController = useRef<AbortController | null>(null);

  useEffect(() => () => {
    actionController.current?.abort();
    suggestionController.current?.abort();
    applyController.current?.abort();
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError("");
    void fetch(`/api/reports/${encodeURIComponent(reportId)}/checklist/draft`, {
      method: "GET",
      credentials: "same-origin",
      cache: "no-store",
      headers: jsonHeaders,
      signal: controller.signal,
    })
      .then(async (response) => {
        if (controller.signal.aborted) return;
        if (await handleAccessLoss(response, controller.signal)) return;
        if (!response.ok) throw new Error("Unable to load the checklist.");
        const value: unknown = await response.json();
        if (controller.signal.aborted) return;
        if (!isDraftResponse(value)) throw new Error("Unable to load the checklist.");
        setEditor(value.steps.join("\n"));
        setSavedSteps(value.steps);
        setDraftRevision(value.draftRevision);
        const transition = startedVersionRef.current;
        setReportVersion(transition && value.reportVersion === transition.previous ? transition.current : value.reportVersion);
        setLoaded(true);
      })
      .catch((cause: unknown) => {
        if (!controller.signal.aborted) {
          setError(cause instanceof Error ? cause.message : "Unable to load the checklist.");
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [reportId]);

  async function handleAccessLoss(response: Response, signal: AbortSignal): Promise<boolean> {
    if (signal.aborted) return true;
    if (response.status === 401) {
      onAccessLost?.("session", reportId);
      return true;
    }
    if (response.status !== 403) return false;

    let value: unknown;
    try {
      value = await response.clone().json();
    } catch {
      return false;
    }
    if (signal.aborted) return true;
    if (isRecord(value) && value.code === "FORBIDDEN") {
      onAccessLost?.("report", reportId);
      return true;
    }
    return false;
  }

  async function csrf(signal: AbortSignal): Promise<CsrfResponse | null> {
    const response = await fetch("/api/auth/csrf", {
      method: "GET",
      credentials: "same-origin",
      cache: "no-store",
      headers: jsonHeaders,
      signal,
    });
    if (await handleAccessLoss(response, signal)) return null;
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

  async function save() {
    const controller = new AbortController();
    actionController.current = controller;
    setPending(true);
    setError("");
    setMessage("");
    try {
      const steps = editor.split("\n").map((step) => step.trim()).filter(Boolean);
      const token = await csrf(controller.signal);
      if (controller.signal.aborted || !token) return;
      const response = await fetch(
        `/api/reports/${encodeURIComponent(reportId)}/checklist/draft`,
        {
          method: "PUT",
          credentials: "same-origin",
          cache: "no-store",
          headers: {
            ...jsonHeaders,
            "Content-Type": "application/json",
            [token.headerName]: token.token,
          },
          body: JSON.stringify({ steps, expectedDraftRevision: draftRevision }),
          signal: controller.signal,
        },
      );
      if (controller.signal.aborted) return;
      if (await handleAccessLoss(response, controller.signal)) return;
      if (!response.ok) throw new Error("Unable to save. Your text is still here; reload the checklist and try again.");
      const value: unknown = await response.json();
      if (controller.signal.aborted) return;
      if (!isRecord(value) || !isRevision(value.draftRevision)) {
        throw new Error("Unable to save. Your text is still here; reload the checklist and try again.");
      }
      setDraftRevision(value.draftRevision);
      setSavedSteps(steps);
      setMessage("Private draft saved.");
    } catch (cause) {
      if (!controller.signal.aborted) {
        setError(cause instanceof Error ? cause.message : "Unable to save. Your text is still here; reload the checklist and try again.");
      }
    } finally {
      if (!controller.signal.aborted) setPending(false);
    }
  }

  async function share() {
    setError("");
    setMessage("");
    if (savedSteps.length === 0) {
      setMessage("Add at least one step before sharing.");
      return;
    }
    const controller = new AbortController();
    actionController.current = controller;
    setPending(true);
    try {
      const token = await csrf(controller.signal);
      if (controller.signal.aborted || !token) return;
      const response = await fetch(
        `/api/reports/${encodeURIComponent(reportId)}/checklist/share`,
        {
          method: "POST",
          credentials: "same-origin",
          cache: "no-store",
          headers: {
            ...jsonHeaders,
            "Content-Type": "application/json",
            [token.headerName]: token.token,
          },
          body: JSON.stringify({
            expectedDraftRevision: draftRevision,
            expectedReportVersion: reportVersion,
          }),
          signal: controller.signal,
        },
      );
      if (controller.signal.aborted) return;
      if (await handleAccessLoss(response, controller.signal)) return;
      if (!response.ok) throw new Error("Unable to share. Refresh the checklist and try again.");
      const value: unknown = await response.json();
      if (controller.signal.aborted) return;
      if (!isRecord(value) || !isRevision(value.reportVersion) || typeof value.changed !== "boolean") {
        throw new Error("Unable to share. Refresh the checklist and try again.");
      }
      setReportVersion(value.reportVersion);
      onReportVersionChange?.(reportVersion, value.reportVersion);
      setSuggestions(null);
      setMessage(value.changed ? "Saved steps shared." : "The saved steps are already shared.");
    } catch (cause) {
      if (!controller.signal.aborted) {
        setError(cause instanceof Error ? cause.message : "Unable to share. Refresh the checklist and try again.");
      }
    } finally {
      if (!controller.signal.aborted) setPending(false);
    }
  }

  async function generateSuggestions() {
    suggestionController.current?.abort();
    const controller = new AbortController();
    suggestionController.current = controller;
    setSuggestions(null);
    setError("");
    setMessage("");
    setGenerating(true);
    try {
      const token = await csrf(controller.signal);
      if (controller.signal.aborted || !token) return;
      const response = await fetch(
        `/api/reports/${encodeURIComponent(reportId)}/checklist/suggestions`,
        {
          method: "POST",
          credentials: "same-origin",
          cache: "no-store",
          headers: {
            ...jsonHeaders,
            "Content-Type": "application/json",
            [token.headerName]: token.token,
          },
          body: "{}",
          signal: controller.signal,
        },
      );
      if (controller.signal.aborted) return;
      if (await handleAccessLoss(response, controller.signal)) return;
      if (!response.ok) throw new Error("Unable to generate suggestions. Please try again.");
      const value: unknown = await response.json();
      if (controller.signal.aborted) return;
      if (!isSuggestions(value)) {
        throw new Error("Unable to generate suggestions. Please try again.");
      }
      setSuggestions(value);
    } catch {
      if (!controller.signal.aborted) {
        setSuggestions(null);
        setError("Unable to generate suggestions. Please try again.");
      }
    } finally {
      if (!controller.signal.aborted) setGenerating(false);
    }
  }

  async function applySuggestions() {
    if (!suggestions) return;
    const candidate = suggestions;
    const controller = new AbortController();
    applyController.current = controller;
    setApplying(true);
    setError("");
    setMessage("");
    try {
      const response = await fetch(
        `/api/reports/${encodeURIComponent(reportId)}/checklist/draft`,
        {
          method: "GET",
          credentials: "same-origin",
          cache: "no-store",
          headers: jsonHeaders,
          signal: controller.signal,
        },
      );
      if (controller.signal.aborted) return;
      if (await handleAccessLoss(response, controller.signal)) return;
      if (!response.ok) throw new Error("Unable to verify these suggestions. Generate them again.");
      const value: unknown = await response.json();
      if (controller.signal.aborted) return;
      if (!isDraftResponse(value)) {
        throw new Error("Unable to verify these suggestions. Generate them again.");
      }
      if (value.reportVersion !== candidate.sourceVersion) {
        setSuggestions(null);
        setError("These suggestions are out of date. Generate them again before applying.");
        return;
      }
      const existingText = editor.trim();
      setEditor(`${editor}${existingText ? "\n" : ""}${candidate.steps.join("\n")}`);
      setSuggestions(null);
      setMessage("Suggestions added to the draft. Save to keep them.");
    } catch {
      if (!controller.signal.aborted) {
        setSuggestions(null);
        setError("Unable to verify these suggestions. Generate them again.");
      }
    } finally {
      if (!controller.signal.aborted) setApplying(false);
    }
  }

  return (
    <section aria-label="Investigation checklist">
      <h2>Investigation checklist</h2>
      {loading ? (
        <p role="status">Loading checklist…</p>
      ) : loaded ? (
        <>
          <label htmlFor="investigation-steps">Investigation steps (one per line)</label>
          <textarea
            id="investigation-steps"
            rows={6}
            value={editor}
            disabled={pending || applying}
            onChange={(event) => setEditor(event.target.value)}
          />
          <p>Save stores the private draft. Share publishes the last saved steps.</p>
          <button type="button" onClick={() => void save()} disabled={pending || applying || generating}>Save</button>
          <button type="button" onClick={() => void share()} disabled={pending || applying || generating}>Share</button>
          <button
            type="button"
            onClick={() => void generateSuggestions()}
            disabled={pending || applying || generating}
          >
            {generating ? "Generating suggestions…" : "Generate suggestions"}
          </button>
          {suggestions && (
            <section aria-label="AI suggestions">
              <h3>AI-generated suggestions</h3>
              <p>Review these suggestions before applying them to your draft.</p>
              <ol>
                {suggestions.steps.map((step, index) => <li key={index}>{step}</li>)}
              </ol>
              <button
                type="button"
                onClick={() => void applySuggestions()}
                disabled={pending || applying || generating}
              >
                {applying ? "Checking report version…" : "Apply suggestions"}
              </button>
            </section>
          )}
          {message && <p role="status">{message}</p>}
          {error && <p role="alert">{error}</p>}
        </>
      ) : null}
      {!loading && !loaded && error && <p role="alert">{error}</p>}
    </section>
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isRevision(value: unknown): value is number {
  return Number.isSafeInteger(value) && (value as number) >= 0;
}

function isDraftResponse(value: unknown): value is DraftResponse {
  return (
    isRecord(value) &&
    Array.isArray(value.steps) &&
    value.steps.every((step) => typeof step === "string") &&
    isRevision(value.draftRevision) &&
    isRevision(value.reportVersion)
  );
}

function isSuggestions(value: unknown): value is Suggestions {
  return (
    isRecord(value) &&
    Array.isArray(value.steps) &&
    value.steps.length >= 1 &&
    value.steps.length <= 8 &&
    value.steps.every(isSuggestionStep) &&
    isRevision(value.sourceVersion)
  );
}

function isSuggestionStep(value: unknown): value is string {
  if (
    typeof value !== "string" ||
    value.trim().length === 0 ||
    value.includes("\0") ||
    value.includes("\r") ||
    value.includes("\n")
  ) {
    return false;
  }
  for (let i = 0; i < value.length; i++) {
    const code = value.charCodeAt(i);
    if (code >= 0xd800 && code <= 0xdbff) {
      if (i + 1 >= value.length) return false;
      const next = value.charCodeAt(i + 1);
      if (next < 0xdc00 || next > 0xdfff) return false;
      i++;
    } else if (code >= 0xdc00 && code <= 0xdfff) {
      return false;
    }
  }
  return new TextEncoder().encode(value).length <= 300;
}
