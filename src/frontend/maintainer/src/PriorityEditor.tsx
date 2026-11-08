import { useEffect, useRef, useState } from "react";
import { priorities, QueryError, type Detail, type Summary } from "./domain";

type Priority = Summary["priority"];

export function PriorityEditor({
  report,
  onSave,
}: {
  report: Detail;
  onSave(priority: Priority): Promise<void>;
}) {
  const [draft, setDraft] = useState<Priority>(report.priority);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();
  const savingRef = useRef(false);

  useEffect(() => {
    setDraft(report.priority);
    setError(undefined);
  }, [report.id, report.version, report.priority]);

  const save = async () => {
    if (savingRef.current || draft === report.priority) return;
    savingRef.current = true;
    setSaving(true);
    setError(undefined);
    try {
      await onSave(draft);
    } catch (cause) {
      if (
        cause instanceof QueryError &&
        (cause.code === "session" || cause.code === "denied")
      ) {
        setError(cause.message);
      } else if (
        cause !== null &&
        typeof cause === "object" &&
        ((cause as { status?: unknown }).status === 409 ||
          (cause as { code?: unknown }).code === "STALE_VERSION")
      ) {
        setError(
          "This report changed before your update was saved. Reload the report and reconcile your change.",
        );
      } else {
        setError(
          "Priority could not be saved. Your selection is still available; retry when ready.",
        );
      }
    } finally {
      savingRef.current = false;
      setSaving(false);
    }
  };

  return (
    <section aria-label="Priority controls" className="priority-editor">
      <h3>Report priority</h3>
      <label>
        Report priority
        <select
          value={draft}
          disabled={saving}
          onChange={(event) => setDraft(event.target.value as Priority)}
        >
          {priorities.map((priority) => (
            <option key={priority} value={priority}>
              {priority}
            </option>
          ))}
        </select>
      </label>
      <button type="button" disabled={saving} onClick={() => void save()}>
        {saving ? "Saving priority…" : "Save priority"}
      </button>
      {error && <p role="alert">{error}</p>}
    </section>
  );
}
