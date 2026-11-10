import { MarkdownEditor, MarkdownView } from "@mp4/markdown";
import { useEffect, useState, type FormEvent } from "react";
import {
  ApiError,
  editReport,
  listComments,
  postComment,
  readReport,
  type Comment,
  type ReportDetail,
  type User,
} from "./api";
import { absoluteTime, errorMessage, relativeTime, roleLabel, shortId, textProblem, titleProblem } from "./format";
import { CommentIcon, PencilIcon } from "./icons";
import { Breadcrumbs } from "./Breadcrumbs";
import { Avatar, StateBadge, StatusLabel } from "./ui";

type Load = { report: ReportDetail; comments: Comment[]; totalComments: number; commentPage: number };

export function ReportView({
  id,
  user,
  backHref,
  onSignedOut,
}: {
  id: string;
  user: User;
  backHref: string;
  onSignedOut: () => void;
}) {
  const [state, setState] = useState<Load | null>(null);
  const [error, setError] = useState("");
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setState(null);
    setError("");
    Promise.all([readReport(id, controller.signal), listComments(id, 1, controller.signal)])
      .then(([report, page]) => {
        if (controller.signal.aborted) return;
        setState({ report, comments: page.items, totalComments: page.totalItems, commentPage: 1 });
      })
      .catch((cause: unknown) => {
        if (controller.signal.aborted) return;
        if (cause instanceof ApiError && cause.status === 401) onSignedOut();
        else setError(errorMessage(cause, "load this report"));
      });
    return () => controller.abort();
  }, [id, attempt]);

  if (error) {
    return (
      <div className="container">
        <Breadcrumbs backHref={backHref} current={`#${shortId(id)}`} />
        <p role="alert" className="flash flash-error">{error}</p>
        <p>
          <a className="btn" href={backHref}>Back to reports</a>{" "}
          <button type="button" className="btn" onClick={() => setAttempt((n) => n + 1)}>Retry</button>
        </p>
      </div>
    );
  }
  if (!state) {
    return (
      <div className="container">
        <Breadcrumbs backHref={backHref} current={`#${shortId(id)}`} />
        <p className="muted" role="status">Loading report…</p>
      </div>
    );
  }

  const { report, comments, totalComments } = state;

  async function loadMore() {
    if (!state) return;
    try {
      const next = await listComments(id, state.commentPage + 1);
      setState({ ...state, comments: [...state.comments, ...next.items], totalComments: next.totalItems, commentPage: state.commentPage + 1 });
    } catch (cause) {
      setError(errorMessage(cause, "load more comments"));
    }
  }

  return (
    <div className="container">
      <Breadcrumbs backHref={backHref} current={report.title} />
      <ReportHeader
        report={report}
        user={user}
        onSaved={(updated) => setState({ ...state, report: updated })}
        commentCount={totalComments}
      />
      <div className="layout">
        <div className="timeline">
          <DescriptionItem report={report} user={user} onSaved={(updated) => setState({ ...state, report: updated })} />
          {comments.map((comment) => (
            <CommentItem key={comment.id} comment={comment} isMe={comment.author.id === user.id} />
          ))}
          {comments.length < totalComments && (
            <div className="timeline-more">
              <button type="button" className="btn" onClick={() => void loadMore()}>
                Load {totalComments - comments.length} more comments
              </button>
            </div>
          )}
          <div className="timeline-divider" />
          <Composer
            reportId={id}
            user={user}
            closed={report.status === "CLOSED"}
            onPosted={(comment) =>
              setState((current) =>
                current
                  ? { ...current, comments: [...current.comments, comment], totalComments: current.totalComments + 1 }
                  : current,
              )
            }
          />
        </div>
        <aside className="sidebar" aria-label="Details">
          <div className="sidebar-item">
            <h3>Status</h3>
            <StatusLabel status={report.status} />
          </div>
          <div className="sidebar-item">
            <h3>Reported</h3>
            <time dateTime={report.createdAt} title={absoluteTime(report.createdAt)}>{relativeTime(report.createdAt)}</time>
          </div>
          <div className="sidebar-item">
            <h3>Last updated</h3>
            <time dateTime={report.updatedAt} title={absoluteTime(report.updatedAt)}>{relativeTime(report.updatedAt)}</time>
          </div>
          <div className="sidebar-item">
            <h3>Reference</h3>
            <code className="ref" title={report.id}>#{shortId(report.id)}</code>
          </div>
        </aside>
      </div>
      <nav className="page-footer-nav" aria-label="Report navigation">
        <a className="btn" href={backHref}>
          <span aria-hidden="true">←</span> Back to your reports
        </a>
        <a className="btn btn-link" href="#top" onClick={(event) => {
          event.preventDefault();
          document.documentElement.scrollTop = 0;
        }}>
          Back to top
        </a>
      </nav>
    </div>
  );
}

function canEdit(report: ReportDetail): boolean {
  // The server also requires no assignee; it answers 409 REPORT_LOCKED otherwise.
  return report.status === "SUBMITTED";
}

function ReportHeader({
  report,
  user,
  onSaved,
  commentCount,
}: {
  report: ReportDetail;
  user: User;
  onSaved: (report: ReportDetail) => void;
  commentCount: number;
}) {
  const [editing, setEditing] = useState(false);
  const [title, setTitle] = useState(report.title);
  const [problem, setProblem] = useState("");
  const [busy, setBusy] = useState(false);

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const local = titleProblem(title);
    if (local) return setProblem(local);
    setBusy(true);
    setProblem("");
    try {
      onSaved(await editReport(report.id, title, report.description, report.version));
      setEditing(false);
    } catch (cause) {
      setProblem(errorMessage(cause, "save the title"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <header className="report-header">
      {editing ? (
        <form className="title-edit" onSubmit={(event) => void save(event)} noValidate>
          <input
            className="input input-large"
            aria-label="Title"
            value={title}
            onChange={(event) => setTitle(event.target.value.replace(/[\r\n]/g, " "))}
            autoFocus
          />
          <button type="submit" className="btn" disabled={busy}>Save</button>
          <button
            type="button"
            className="btn btn-link"
            onClick={() => {
              setEditing(false);
              setTitle(report.title);
              setProblem("");
            }}
          >
            Cancel
          </button>
        </form>
      ) : (
        <div className="title-row">
          <h1 className="report-heading">
            <span className="report-heading-title">{report.title}</span>{" "}
            <span className="report-heading-ref">#{shortId(report.id)}</span>
          </h1>
          <div className="title-actions">
            {canEdit(report) && (
              <button
                type="button"
                className="btn btn-small"
                onClick={() => {
                  setTitle(report.title);
                  setEditing(true);
                }}
              >
                Edit
              </button>
            )}
            <a className="btn btn-primary btn-small" href="#/new">New report</a>
          </div>
        </div>
      )}
      {problem && <p role="alert" className="flash flash-error">{problem}</p>}
      <div className="report-meta">
        <StateBadge status={report.status} />
        <span className="muted">
          <strong>{user.displayName}</strong> opened this report{" "}
          <time dateTime={report.createdAt} title={absoluteTime(report.createdAt)}>{relativeTime(report.createdAt)}</time>
          {" · "}
          {commentCount} {commentCount === 1 ? "comment" : "comments"}
        </span>
      </div>
    </header>
  );
}

function DescriptionItem({ report, user, onSaved }: { report: ReportDetail; user: User; onSaved: (report: ReportDetail) => void }) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(report.description);
  const [problem, setProblem] = useState("");
  const [busy, setBusy] = useState(false);

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const local = textProblem(draft, "description");
    if (local) return setProblem(local);
    setBusy(true);
    setProblem("");
    try {
      onSaved(await editReport(report.id, report.title, draft, report.version));
      setEditing(false);
    } catch (cause) {
      setProblem(errorMessage(cause, "save the description"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <article className="timeline-item">
      <Avatar name={user.displayName} id={user.id} size={40} />
      <div className="box comment-box comment-box-author">
        <header className="box-header comment-header">
          <span>
            <strong>{user.displayName}</strong>{" "}
            <span className="muted">
              opened{" "}
              <time dateTime={report.createdAt} title={absoluteTime(report.createdAt)}>{relativeTime(report.createdAt)}</time>
              {report.version > 0 && " · edited"}
            </span>
          </span>
          <span className="comment-badges">
            <span className="badge">Author</span>
            {canEdit(report) && !editing && (
              <button
                type="button"
                className="btn-icon"
                aria-label="Edit description"
                title="Edit description"
                onClick={() => {
                  setDraft(report.description);
                  setEditing(true);
                }}
              >
                <PencilIcon />
              </button>
            )}
          </span>
        </header>
        {editing ? (
          <form onSubmit={(event) => void save(event)} noValidate>
            <div className="box-body">
              <MarkdownEditor value={draft} onChange={setDraft} label="Description" />
              {problem && <p role="alert" className="flash flash-error">{problem}</p>}
            </div>
            <div className="box-footer">
              <button
                type="button"
                className="btn"
                onClick={() => {
                  setEditing(false);
                  setProblem("");
                }}
              >
                Cancel
              </button>
              <button type="submit" className="btn btn-primary" disabled={busy}>
                {busy ? "Saving…" : "Update description"}
              </button>
            </div>
          </form>
        ) : (
          <div className="box-body markdown-body">
            <MarkdownView source={report.description} />
          </div>
        )}
      </div>
    </article>
  );
}

function CommentItem({ comment, isMe }: { comment: Comment; isMe: boolean }) {
  const name = comment.author.displayName ?? "Account no longer available";
  return (
    <article className="timeline-item">
      <Avatar name={comment.author.displayName} id={comment.author.id} size={40} />
      <div className={`box comment-box ${isMe ? "comment-box-author" : ""}`}>
        <header className="box-header comment-header">
          <span>
            <strong>{name}</strong>{" "}
            <span className="muted">
              commented{" "}
              <time dateTime={comment.createdAt} title={absoluteTime(comment.createdAt)}>{relativeTime(comment.createdAt)}</time>
            </span>
          </span>
          <span className="comment-badges">
            {isMe && <span className="badge">Author</span>}
            {comment.author.role !== "REPORTER" && <span className="badge">{roleLabel(comment.author.role)}</span>}
          </span>
        </header>
        <div className="box-body markdown-body">
          <MarkdownView source={comment.body} />
        </div>
      </div>
    </article>
  );
}

function Composer({
  reportId,
  user,
  closed,
  onPosted,
}: {
  reportId: string;
  user: User;
  closed: boolean;
  onPosted: (comment: Comment) => void;
}) {
  const [draft, setDraft] = useState("");
  const [problem, setProblem] = useState("");
  const [busy, setBusy] = useState(false);
  // Remount the editor after a post so it returns to the Write tab.
  const [editorKey, setEditorKey] = useState(0);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const local = textProblem(draft, "comment");
    if (local) return setProblem(local);
    setBusy(true);
    setProblem("");
    try {
      onPosted(await postComment(reportId, draft));
      setDraft("");
      setEditorKey((n) => n + 1);
    } catch (cause) {
      // Keep the draft on failure (design D8).
      setProblem(errorMessage(cause, "post your comment"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="timeline-item composer" onSubmit={(event) => void submit(event)} noValidate aria-label="Add a comment">
      <Avatar name={user.displayName} id={user.id} size={40} />
      <div className="box comment-box">
        <header className="box-header comment-header">
          <strong>Add a comment</strong>
          <CommentIcon className="muted" />
        </header>
        <div className="box-body">
          {closed && <p className="muted small">This report is closed. You can still add a comment.</p>}
          <MarkdownEditor key={editorKey} value={draft} onChange={setDraft} label="Comment" />
          <p className="muted small editor-hint">Markdown is supported.</p>
          {problem && <p role="alert" className="flash flash-error">{problem}</p>}
        </div>
        <div className="box-footer">
          <button type="submit" className="btn btn-primary" disabled={busy || draft.trim() === ""}>
            {busy ? "Commenting…" : "Comment"}
          </button>
        </div>
      </div>
    </form>
  );
}
