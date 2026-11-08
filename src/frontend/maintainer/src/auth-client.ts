import { QueryError } from "./domain";

export interface AuthActions {
  signIn(
    username: string,
    password: string,
    signal: AbortSignal,
  ): Promise<void>;
  signOut(signal: AbortSignal): Promise<void>;
}

const CSRF_PATH = "/api/auth/csrf";
const LOGIN_PATH = "/api/auth/login";
const LOGOUT_PATH = "/api/auth/logout";
const TIMEOUT_MS = 10_000;
const MAX_CSRF_BYTES = 8_192;

type Csrf = { token: string; headerName: "X-CSRF-TOKEN" };

export function createHttpAuthActions(
  fetcher: typeof fetch = fetch,
): AuthActions {
  async function request(
    path: string,
    callerSignal: AbortSignal,
    init: Omit<RequestInit, "signal" | "credentials" | "cache"> = {},
  ): Promise<Response> {
    if (callerSignal.aborted) throw new QueryError("cancelled");
    const controller = new AbortController();
    const abort = () => controller.abort();
    callerSignal.addEventListener("abort", abort, { once: true });
    const timer = globalThis.setTimeout(abort, TIMEOUT_MS);
    try {
      const response = await fetcher(path, {
        ...init,
        credentials: "same-origin",
        cache: "no-store",
        signal: controller.signal,
      });
      if (callerSignal.aborted) throw new QueryError("cancelled");
      if (!response.ok) {
        throw new QueryError(
          response.status === 401
            ? "session"
            : response.status === 403
              ? "denied"
              : "network",
        );
      }
      return response;
    } catch (error) {
      if (callerSignal.aborted) throw new QueryError("cancelled");
      if (error instanceof QueryError) throw error;
      throw new QueryError("network");
    } finally {
      globalThis.clearTimeout(timer);
      callerSignal.removeEventListener("abort", abort);
    }
  }

  async function csrf(signal: AbortSignal): Promise<Csrf> {
    const response = await request(CSRF_PATH, signal, {
      method: "GET",
      headers: { Accept: "application/json" },
    });
    const raw = await readBounded(response, signal);
    let value: unknown;
    try {
      value = JSON.parse(raw);
    } catch {
      throw new QueryError("contract");
    }
    if (!value || typeof value !== "object" || Array.isArray(value))
      throw new QueryError("contract");
    const candidate = value as Record<string, unknown>;
    if (
      typeof candidate.token !== "string" ||
      candidate.token.length === 0 ||
      candidate.token.length > 2048 ||
      candidate.headerName !== "X-CSRF-TOKEN"
    )
      throw new QueryError("contract");
    return { token: candidate.token, headerName: candidate.headerName };
  }

  return {
    async signIn(username, password, signal) {
      await boundedAction(signal, async (boundedSignal) => {
        const token = await csrf(boundedSignal);
        await request(LOGIN_PATH, boundedSignal, {
          method: "POST",
          headers: {
            Accept: "application/json",
            "Content-Type": "application/x-www-form-urlencoded",
            [token.headerName]: token.token,
          },
          body: new URLSearchParams({ username, password }).toString(),
        });
      });
    },
    async signOut(signal) {
      await boundedAction(signal, async (boundedSignal) => {
        const token = await csrf(boundedSignal);
        await request(LOGOUT_PATH, boundedSignal, {
          method: "POST",
          headers: {
            Accept: "application/json",
            [token.headerName]: token.token,
          },
        });
      });
    },
  };
}

async function boundedAction(
  callerSignal: AbortSignal,
  operation: (signal: AbortSignal) => Promise<void>,
): Promise<void> {
  if (callerSignal.aborted) throw new QueryError("cancelled");
  const controller = new AbortController();
  const abort = () => controller.abort();
  callerSignal.addEventListener("abort", abort, { once: true });
  const timer = globalThis.setTimeout(abort, TIMEOUT_MS);
  try {
    await operation(controller.signal);
  } catch (error) {
    if (callerSignal.aborted) throw new QueryError("cancelled");
    if (controller.signal.aborted) throw new QueryError("network");
    throw error;
  } finally {
    globalThis.clearTimeout(timer);
    callerSignal.removeEventListener("abort", abort);
  }
}

async function readBounded(
  response: Response,
  signal: AbortSignal,
): Promise<string> {
  if (!response.body) throw new QueryError("contract");
  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8", { fatal: true });
  let size = 0;
  let text = "";
  const cancel = () => void reader.cancel().catch(() => undefined);
  signal.addEventListener("abort", cancel, { once: true });
  try {
    while (true) {
      if (signal.aborted) throw new QueryError("cancelled");
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_CSRF_BYTES) {
        await reader.cancel();
        throw new QueryError("contract");
      }
      text += decoder.decode(value, { stream: true });
    }
    if (signal.aborted) throw new QueryError("cancelled");
    return text + decoder.decode();
  } catch (error) {
    if (error instanceof QueryError) throw error;
    throw new QueryError("contract");
  } finally {
    signal.removeEventListener("abort", cancel);
    reader.releaseLock();
  }
}
