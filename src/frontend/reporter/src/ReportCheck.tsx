import type { ReportCheck } from "./api";
import { ApiError } from "./api";
import { errorMessage } from "./format";

/**
 * Results of Check my report (RC-04..RC-06). Every model-produced string is a
 * React text node: never Markdown, HTML or a link. The panel has no access to
 * the draft, so it cannot change it.
 */
export function ReportCheckPanel({
  state,
  onDismiss,
}: {
  state: CheckState;
  onDismiss: () => void;
}) {
  if (state.kind === "idle") return null;

  if (state.kind === "checking") {
    return (
      <div className="check-panel" role="status" aria-live="polite">
        <span className="check-spinner" aria-hidden="true" />
        Checking your report… this can take up to a minute.
      </div>
    );
  }

  if (state.kind === "error") {
    return (
      <div className="check-panel" role="alert">
        <p className="check-error">{state.message}</p>
        <button type="button" className="btn btn-small" onClick={onDismiss}>
          Dismiss
        </button>
      </div>
    );
  }

  const { unclear, missing } = state.result;
  const empty = unclear.length === 0 && missing.length === 0;
  return (
    <section className="check-panel" aria-label="Report check results">
      <div className="check-header">
        <h2 className="check-title">Questions about your report</h2>
        <button type="button" className="btn btn-small" onClick={onDismiss}>
          Dismiss
        </button>
      </div>
      {empty ? (
        <p className="muted">No questions found. Your report looks clear.</p>
      ) : (
        <>
          <p className="muted small">
            Suggestions from AI. Your report is not changed: edit it yourself if a question helps.
          </p>
          {unclear.length > 0 && (
            <>
              <h3 className="check-subtitle">Unclear parts</h3>
              <ul className="check-list">
                {unclear.map((item) => (
                  <li key={item.quote}>
                    <blockquote className="check-quote">{item.quote}</blockquote>
                    <p className="check-question">{item.question}</p>
                  </li>
                ))}
              </ul>
            </>
          )}
          {missing.length > 0 && (
            <>
              <h3 className="check-subtitle">Questions by topic</h3>
              <ul className="check-list">
                {missing.map((item) => (
                  <li key={item.category}>
                    <span className="check-category">{item.category}</span>
                    <p className="check-question">{item.question}</p>
                  </li>
                ))}
              </ul>
            </>
          )}
        </>
      )}
    </section>
  );
}

export type CheckState =
  | { kind: "idle" }
  | { kind: "checking" }
  | { kind: "done"; result: ReportCheck }
  | { kind: "error"; message: string };

/** Fixed messages; never echoes the draft or model output (RC-08). */
export function checkErrorMessage(cause: unknown): string {
  if (cause instanceof ApiError) {
    switch (cause.code) {
      case "AI_RATE_LIMITED":
      case "RATE_LIMITED":
        return `You've checked too often. Try again in ${cause.retryAfterSeconds ?? 60} seconds.`;
      case "AI_BUSY":
        return "A check is already running. Wait for it to finish, then try again.";
      case "AI_TIMEOUT":
        return "The check took too long. Try again later.";
      case "AI_UNAVAILABLE":
        return "Checking isn't available right now. You can still submit your report.";
      case "AI_INVALID_OUTPUT":
      case "BAD_RESPONSE":
        return "Couldn't check your report. Try again.";
    }
  }
  return errorMessage(cause, "check your report");
}
