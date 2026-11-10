import { statusInfo } from "./format";
import { ClosedIcon, OpenIcon } from "./icons";

const AVATAR_HUES = [210, 140, 30, 280, 350, 180, 50, 100];

/** Initials on a colour picked from the account id, so a person keeps one colour. */
export function Avatar({ name, id, size = 32 }: { name: string | null; id: string; size?: number }) {
  let hash = 0;
  for (const ch of id) hash = (hash * 31 + ch.charCodeAt(0)) >>> 0;
  const hue = AVATAR_HUES[hash % AVATAR_HUES.length];
  const initials = (name ?? "?")
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]!.toUpperCase())
    .join("") || "?";
  return (
    <span
      className="avatar"
      aria-hidden="true"
      style={{ width: size, height: size, fontSize: size * 0.4, background: `hsl(${hue} 55% 45%)` }}
    >
      {initials}
    </span>
  );
}

export function StateBadge({ status }: { status: string }) {
  const { open } = statusInfo(status);
  return (
    <span className={`state ${open ? "state-open" : "state-closed"}`}>
      {open ? <OpenIcon /> : <ClosedIcon />}
      {open ? "Open" : "Closed"}
    </span>
  );
}

export function StatusLabel({ status }: { status: string }) {
  const { label } = statusInfo(status);
  return <span className={`label label-${status.toLowerCase().replace(/_/g, "-")}`}>{label}</span>;
}

export function StateIcon({ status }: { status: string }) {
  const { open } = statusInfo(status);
  return open ? (
    <OpenIcon className="icon-open" title="Open" />
  ) : (
    <ClosedIcon className="icon-closed" title="Closed" />
  );
}
