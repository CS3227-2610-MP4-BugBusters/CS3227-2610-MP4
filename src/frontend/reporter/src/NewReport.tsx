import { MarkdownEditor } from "@mp4/markdown";
import { useState, type FormEvent } from "react";
import { createReport } from "./api";
import { errorMessage, textProblem, TEXT_MAX, titleProblem } from "./format";
import { Breadcrumbs } from "./Breadcrumbs";
import { Avatar } from "./ui";

const TEMPLATE = "### What happened?\n\n\n### Steps to reproduce\n1. \n\n### What did you expect?\n\n";

export function NewReport({
  user,
  backHref,
  onCreated,
}: {
  user: { id: string; displayName: string };
  backHref: string;
  onCreated: (id: string) => void;
}) {
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState(TEMPLATE);
  const [problem, setProblem] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const local = titleProblem(title) ?? textProblem(description, "description");
    if (local) {
      setProblem(local);
      return;
    }
    setBusy(true);
    setProblem("");
    try {
      const created = await createReport(title, description);
      onCreated(created.id);
    } catch (cause) {
      // The draft stays in the form so nothing typed is lost.
      setProblem(errorMessage(cause, "submit the report"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="container">
      <Breadcrumbs backHref={backHref} current="New report" />
      <h1 className="page-title">New report</h1>
      <form className="timeline-item" onSubmit={(event) => void submit(event)} noValidate>
        <Avatar name={user.displayName} id={user.id} size={40} />
        <div className="box comment-box editor-box">
          <div className="box-body">
            <label className="field-label" htmlFor="report-title">
              Title
            </label>
            <input
              id="report-title"
              className="input input-large"
              placeholder="A short summary of the problem"
              value={title}
              onChange={(event) => setTitle(event.target.value.replace(/[\r\n]/g, " "))}
              autoFocus
            />
            <span className="field-label">Description</span>
            <MarkdownEditor value={description} onChange={setDescription} label="Description" />
            <p className="muted small editor-hint">
              Markdown is supported. {description.length.toLocaleString()} / {TEXT_MAX.toLocaleString()} characters.
            </p>
            {problem && (
              <p role="alert" className="flash flash-error">
                {problem}
              </p>
            )}
          </div>
          <div className="box-footer">
            <a className="btn" href={backHref}>
              Cancel
            </a>
            <button type="submit" className="btn btn-primary" disabled={busy}>
              {busy ? "Submitting…" : "Submit report"}
            </button>
          </div>
        </div>
      </form>
    </div>
  );
}
