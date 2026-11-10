import { ApiError } from "./api";

export const TITLE_MAX = 200;
export const TEXT_MAX = 20_000;

/** GitHub-style open/closed state plus the workflow stage shown as a label. */
export function statusInfo(status: string): { open: boolean; label: string } {
  switch (status) {
    case "SUBMITTED":
      return { open: true, label: "Submitted" };
    case "ASSIGNED":
      return { open: true, label: "Assigned" };
    case "IN_PROGRESS":
      return { open: true, label: "In progress" };
    case "RESOLUTION_PROPOSED":
      return { open: true, label: "Fix proposed" };
    case "CLOSED":
      return { open: false, label: "Closed" };
    default:
      return { open: true, label: status };
  }
}

/** The client mirrors the contract's field rules so most mistakes never leave the page. */
export function titleProblem(title: string): string | null {
  if (title.trim() === "") return "Add a title.";
  if (/[\r\n]/.test(title)) return "The title must be a single line.";
  if (title.length > TITLE_MAX) return `The title is ${title.length - TITLE_MAX} characters too long.`;
  return null;
}

export function textProblem(text: string, what: string): string | null {
  if (text.trim() === "") return `Add a ${what}.`;
  if (text.length > TEXT_MAX) return `The ${what} is ${text.length - TEXT_MAX} characters too long.`;
  if (text.includes("\u0000") || hasLoneSurrogate(text)) return `The ${what} contains characters that can't be saved.`;
  return null;
}

function hasLoneSurrogate(text: string): boolean {
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    if (c >= 0xd800 && c <= 0xdbff) {
      const next = text.charCodeAt(i + 1);
      if (next >= 0xdc00 && next <= 0xdfff) {
        i++;
        continue;
      }
      return true;
    }
    if (c >= 0xdc00 && c <= 0xdfff) return true;
  }
  return false;
}

/** A fixed, user-facing message per error. Never echoes submitted text. */
export function errorMessage(cause: unknown, action: string): string {
  if (cause instanceof ApiError) {
    switch (cause.code) {
      case "RATE_LIMITED":
        return `You're doing that too often. Try again in ${cause.retryAfterSeconds ?? 60} seconds.`;
      case "REPORT_LOCKED":
        return "This report can't be edited any more because a developer has picked it up.";
      case "VERSION_CONFLICT":
        return "This report changed since you opened it. Reload to see the latest version.";
      case "VALIDATION_FAILED":
        return `Couldn't ${action}: the server rejected the input.`;
      case "REPORT_NOT_FOUND":
        return "This report doesn't exist or isn't yours.";
      case "UNAUTHENTICATED":
        return "Your session has ended. Sign in again.";
      case "FORBIDDEN":
      case "CSRF_INVALID":
        return `Couldn't ${action}: access was denied. Reload the page and try again.`;
    }
  }
  return `Couldn't ${action}. Check your connection and try again.`;
}

const units: [Intl.RelativeTimeFormatUnit, number][] = [
  ["year", 365 * 24 * 3600],
  ["month", 30 * 24 * 3600],
  ["week", 7 * 24 * 3600],
  ["day", 24 * 3600],
  ["hour", 3600],
  ["minute", 60],
];

export function relativeTime(iso: string, now: number = Date.now()): string {
  const then = Date.parse(iso);
  if (Number.isNaN(then)) return iso;
  const seconds = Math.round((then - now) / 1000);
  const format = new Intl.RelativeTimeFormat("en", { numeric: "auto" });
  for (const [unit, size] of units) {
    if (Math.abs(seconds) >= size) return format.format(Math.trunc(seconds / size), unit);
  }
  return "just now";
}

export function absoluteTime(iso: string): string {
  const then = Date.parse(iso);
  return Number.isNaN(then) ? iso : new Date(then).toLocaleString();
}

export function roleLabel(role: string): string {
  switch (role) {
    case "REPORTER":
      return "Reporter";
    case "DEVELOPER":
      return "Developer";
    case "MAINTAINER":
      return "Maintainer";
    default:
      return role;
  }
}

/** Short reference shown like an issue number. */
export function shortId(id: string): string {
  return id.slice(0, 7);
}
