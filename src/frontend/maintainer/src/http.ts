import {
  checkedFilters,
  parseDetail,
  parseDevelopers,
  parsePage,
  parseUser,
  QueryError,
  uuid,
  type Queries,
} from "./domain";
export function createHttpQueries(fetcher: typeof fetch = fetch): Queries {
  async function get<T>(
    path: string,
    signal: AbortSignal,
    parse: (v: unknown) => T,
    detail = false,
  ): Promise<T> {
    try {
      const r = await fetcher(path, {
        method: "GET",
        credentials: "same-origin",
        cache: "no-store",
        headers: { Accept: "application/json" },
        signal,
      });
      if (!r.ok)
        throw new QueryError(
          r.status === 401
            ? "session"
            : r.status === 403
              ? "denied"
              : r.status === 404 && detail
                ? "missing"
                : "network",
        );
      const raw = await boundedBody(r, signal);
      let value: unknown;
      try {
        value = JSON.parse(raw);
      } catch {
        throw new QueryError("contract");
      }
      return parse(value);
    } catch (e) {
      if (
        signal.aborted ||
        (e instanceof DOMException && e.name === "AbortError")
      )
        throw new QueryError("cancelled");
      throw e instanceof QueryError ? e : new QueryError("network");
    }
  }
  return {
    getCurrentUser: (s) => get("/api/auth/me", s, parseUser),
    listDevelopers: (s) =>
      get("/api/maintainer/developers", s, parseDevelopers),
    listReports: (f, s) => {
      const p = new URLSearchParams();
      for (const [k, v] of Object.entries(checkedFilters(f)))
        p.set(k, String(v));
      return get(`/api/maintainer/reports?${p}`, s, parsePage);
    },
    getReport: (id, s) => {
      if (!uuid.test(id)) return Promise.reject(new QueryError("contract"));
      return get(
        `/api/maintainer/reports/${encodeURIComponent(id)}`,
        s,
        parseDetail,
        true,
      );
    },
  };
}
async function boundedBody(
  response: Response,
  signal: AbortSignal,
): Promise<string> {
  if (!response.body) throw new QueryError("contract");
  const reader = response.body.getReader(),
    decoder = new TextDecoder("utf-8", { fatal: true });
  let size = 0,
    text = "";
  const cancel = () => {
    void reader.cancel().catch(() => undefined);
  };
  signal.addEventListener("abort", cancel, { once: true });
  try {
    while (true) {
      if (signal.aborted) throw new QueryError("cancelled");
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > 2_000_000) {
        await reader.cancel();
        throw new QueryError("contract");
      }
      text += decoder.decode(value, { stream: true });
    }
    if (signal.aborted) throw new QueryError("cancelled");
    return text + decoder.decode();
  } catch (e) {
    if (e instanceof QueryError) throw e;
    throw new QueryError("contract");
  } finally {
    signal.removeEventListener("abort", cancel);
    reader.releaseLock();
  }
}
