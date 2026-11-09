import { priorities, QueryError, statuses, uuid } from "./domain";
import type { SummaryFilters, SummaryResult } from "./domain";

const fail = (): never => { throw new QueryError("contract"); };
type Obj = Record<string, unknown>;
function object(value: unknown, keys: string[]): Obj {
  if (!value || typeof value !== "object" || Array.isArray(value)) return fail();
  const record = value as Obj;
  if (Object.keys(record).some((key) => !keys.includes(key))) return fail();
  return record;
}
function string(value: unknown, max: number, nonblank = false): string {
  if (typeof value !== "string" || (nonblank && !value.trim())) return fail();
  let points = 0;
  for (let index = 0; index < value.length; points++) {
    if (points >= max) return fail();
    const point = value.codePointAt(index)!;
    index += point > 0xffff ? 2 : 1;
  }
  return value;
}
function identifier(value: unknown): string {
  const result = string(value, 36);
  return uuid.test(result) ? result : fail();
}
function version(value: unknown): number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value : fail();
}
function parseFilters(value: unknown): SummaryFilters {
  const filters = object(value, ["status", "priority", "assigneeId"]);
  if (filters.status !== undefined && !statuses.includes(filters.status as (typeof statuses)[number])) return fail();
  if (filters.priority !== undefined && !priorities.includes(filters.priority as (typeof priorities)[number])) return fail();
  if (filters.assigneeId !== undefined) identifier(filters.assigneeId);
  if (filters.status === "Closed") return fail();
  return {
    ...(filters.status === undefined ? {} : { status: filters.status as SummaryFilters["status"] }),
    ...(filters.priority === undefined ? {} : { priority: filters.priority as SummaryFilters["priority"] }),
    ...(filters.assigneeId === undefined ? {} : { assigneeId: filters.assigneeId as string }),
  };
}
function timestamp(value: unknown): string {
  const result = string(value, 40);
  return /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z$/.test(result) && Number.isFinite(Date.parse(result)) && new Date(result).toISOString().slice(0, 19) === result.slice(0, 19) ? result : fail();
}
function uniqueIds(value: unknown, max: number): string[] {
  if (!Array.isArray(value) || value.length > max) return fail();
  const result = value.map(identifier);
  if (new Set(result).size !== result.length) return fail();
  return result;
}
function findings(value: unknown, included: Set<string>): SummaryResult["findings"] {
  const input = object(value, ["overview", "blockers", "missingInformation"]);
  let totalText = 0;
  const group = (raw: unknown) => {
    if (!Array.isArray(raw) || raw.length > 8) return fail();
    return raw.map((item) => {
      const finding = object(item, ["text", "reportIds"]);
      const text = string(finding.text, 600, true);
      totalText += [...text].length;
      const reportIds = uniqueIds(finding.reportIds, 20);
      if (reportIds.length < 1 || reportIds.some((id) => !included.has(id))) return fail();
      return { text, reportIds };
    });
  };
  const result = {
    overview: group(input.overview),
    blockers: group(input.blockers),
    missingInformation: group(input.missingInformation),
  };
  if (totalText > 8000 || new TextEncoder().encode(JSON.stringify(result)).byteLength > 32768) return fail();
  return result;
}

/** Runtime validation for injected adapters as well as the HTTP boundary. */
export function parseSummaryResult(value: unknown): SummaryResult {
  const input = object(value, ["capturedAt", "filters", "included", "excluded", "findings", "requiresReview", "providerMode", "promptVersion"]);
  const filters = parseFilters(input.filters);
  if (!Array.isArray(input.included) || input.included.length > 20 || !Array.isArray(input.excluded) || input.excluded.length > 1000 || input.included.length + input.excluded.length > 1000) return fail();
  const included = input.included.map((entry) => {
    const item = object(entry, ["id", "version", "truncatedFields"]);
    if (!Array.isArray(item.truncatedFields) || item.truncatedFields.length > 7) return fail();
    const allowed = ["title", "description", "reproductionSteps", "expectedBehavior", "actualBehavior", "environment", "latestUpdates"];
    const truncatedFields = item.truncatedFields.map((field) => {
      if (typeof field !== "string" || !allowed.includes(field)) return fail();
      return field;
    });
    if (new Set(truncatedFields).size !== truncatedFields.length) return fail();
    return { id: identifier(item.id), version: version(item.version), truncatedFields };
  });
  const includedIds = new Set(included.map((item) => item.id));
  if (includedIds.size !== included.length) return fail();
  const excluded = input.excluded.map((entry) => {
    const item = object(entry, ["id", "reason"]);
    return { id: identifier(item.id), reason: string(item.reason, 100, true) };
  });
  const allIds = new Set([...includedIds, ...excluded.map((item) => item.id)]);
  if (allIds.size !== included.length + excluded.length) return fail();
  const mode = input.providerMode;
  if (mode !== "none" && mode !== "deterministic" && mode !== "soc") return fail();
  if (input.requiresReview !== true || input.promptVersion !== "MNT-SUMMARY-1") return fail();
  return {
    capturedAt: timestamp(input.capturedAt), filters, included, excluded,
    findings: findings(input.findings, includedIds), requiresReview: true,
    providerMode: mode, promptVersion: "MNT-SUMMARY-1",
  };
}
