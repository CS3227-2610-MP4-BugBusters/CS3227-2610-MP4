import { useEffect, useRef, useState } from "react";

type DraftResponse = {
  steps: string[];
  draftRevision: number;
  reportVersion: number;
};

type CsrfResponse = { token: string; headerName: "X-CSRF-TOKEN" };

const jsonHeaders = { Accept: "application/json" };

export function DeveloperChecklist({ reportId }: { reportId: string }) {
  return <ChecklistForReport key={reportId} reportId={reportId} />;
}

function ChecklistForReport({ reportId }: { reportId: string }) {
  const [editor, setEditor] = useState("");
  const [savedSteps, setSavedSteps] = useState<string[]>([]);
  const [draftRevision, setDraftRevision] = useState(0);
  const [reportVersion, setReportVersion] = useState(0);
  const [loading, setLoading] = useState(true);
  const [loaded, setLoaded] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const actionController = useRef<AbortController | null>(null);

  useEffect(() => () => actionController.current?.abort(), []);

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
        if (!response.ok) throw new Error("Unable to load the checklist.");
        const value: unknown = await response.json();
        if (controller.signal.aborted) return;
        if (!isDraftResponse(value)) throw new Error("Unable to load the checklist.");
        setEditor(value.steps.join("\n"));
        setSavedSteps(value.steps);
        setDraftRevision(value.draftRevision);
        setReportVersion(value.reportVersion);
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

  async function csrf(signal: AbortSignal): Promise<CsrfResponse> {
    const response = await fetch("/api/auth/csrf", {
      method: "GET",
      credentials: "same-origin",
      cache: "no-store",
      headers: jsonHeaders,
      signal,
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

  async function save() {
    const controller = new AbortController();
    actionController.current = controller;
    setPending(true);
    setError("");
    setMessage("");
    try {
      const steps = editor.split("\n").map((step) => step.trim()).filter(Boolean);
      const token = await csrf(controller.signal);
      if (controller.signal.aborted) return;
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
      if (controller.signal.aborted) return;
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
      if (!response.ok) throw new Error("Unable to share. Refresh the checklist and try again.");
      const value: unknown = await response.json();
      if (controller.signal.aborted) return;
      if (!isRecord(value) || !isRevision(value.reportVersion) || typeof value.changed !== "boolean") {
        throw new Error("Unable to share. Refresh the checklist and try again.");
      }
      setReportVersion(value.reportVersion);
      setMessage(value.changed ? "Saved steps shared." : "The saved steps are already shared.");
    } catch (cause) {
      if (!controller.signal.aborted) {
        setError(cause instanceof Error ? cause.message : "Unable to share. Refresh the checklist and try again.");
      }
    } finally {
      if (!controller.signal.aborted) setPending(false);
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
            disabled={pending}
            onChange={(event) => setEditor(event.target.value)}
          />
          <p>Save stores the private draft. Share publishes the last saved steps.</p>
          <button type="button" onClick={() => void save()} disabled={pending}>Save</button>
          <button type="button" onClick={() => void share()} disabled={pending}>Share</button>
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
