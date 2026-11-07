import { useState } from "react";

type Role = "Developer" | "Reporter" | "Maintainer";

export function ChecklistDemo() {
  const [role, setRole] = useState<Role>("Developer");
  const [editor, setEditor] = useState("");
  const [draft, setDraft] = useState<string[]>([]);
  const [shared, setShared] = useState<string[]>([]);
  const [proposalPending, setProposalPending] = useState(true);
  const [message, setMessage] = useState("");

  function save() {
    const steps = editor.split("\n").map((step) => step.trim()).filter(Boolean);
    setDraft(steps);
    setMessage("Private draft saved.");
  }

  function share() {
    if (draft.length === 0) {
      setMessage("Add at least one step before sharing.");
      return;
    }
    if (draft.length === shared.length && draft.every((step, i) => step === shared[i])) {
      setMessage("The saved steps are already shared.");
      return;
    }
    setShared(draft);
    setProposalPending(false);
    setMessage("Saved steps shared.");
  }

  return (
    <main>
      <p className="demo">Local demo · Synthetic report · Resets on reload</p>
      <h1>Investigation checklist</h1>
      <p>Example report: Export stops before the download starts</p>
      <p>Report status: {proposalPending ? "Resolution proposed" : "In progress"}</p>
      <label htmlFor="preview">Preview as</label>
      <select
        id="preview"
        value={role}
        onChange={(event) => {
          const next = event.target.value as Role;
          setRole(next);
          setMessage("");
          if (next === "Developer") setEditor(draft.join("\n"));
        }}
      >
        <option>Developer</option>
        <option>Reporter</option>
        <option>Maintainer</option>
      </select>

      {role === "Developer" && (
        <section>
          <h2>Private draft</h2>
          <label htmlFor="steps">Investigation steps (one per line)</label>
          <textarea id="steps" rows={6} value={editor} onChange={(event) => setEditor(event.target.value)} />
          <p>Save stores the private draft. Share publishes the last saved steps.</p>
          <button onClick={save}>Save</button>
          <button onClick={share}>Share</button>
          <p role="status">{message}</p>
        </section>
      )}

      <section aria-label="Shared checklist">
        <h2>Shared checklist</h2>
        {shared.length > 0 ? (
          <ol>{shared.map((step, index) => <li key={index}>{step}</li>)}</ol>
        ) : (
          <p>No steps have been shared.</p>
        )}
      </section>
    </main>
  );
}
