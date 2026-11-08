import { describe, expect, it, vi } from "vitest";
import { createHttpAuthActions } from "./auth-client";

const csrfResponse = () =>
  new Response(
    JSON.stringify({ token: "csrf-canary", headerName: "X-CSRF-TOKEN" }),
    {
      status: 200,
      headers: { "Content-Type": "application/json" },
    },
  );

describe("HTTP authentication actions", () => {
  it("fetches a fresh CSRF token and posts encoded credentials with same-origin session settings", async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(csrfResponse())
      .mockResolvedValueOnce(new Response(null, { status: 200 }));
    const actions = createHttpAuthActions(fetcher);
    await actions.signIn(
      "maintainer one",
      "p&ss word",
      new AbortController().signal,
    );

    expect(fetcher).toHaveBeenNthCalledWith(
      1,
      "/api/auth/csrf",
      expect.objectContaining({
        method: "GET",
        credentials: "same-origin",
        cache: "no-store",
        headers: { Accept: "application/json" },
      }),
    );
    expect(fetcher).toHaveBeenNthCalledWith(
      2,
      "/api/auth/login",
      expect.objectContaining({
        method: "POST",
        credentials: "same-origin",
        cache: "no-store",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/x-www-form-urlencoded",
          "X-CSRF-TOKEN": "csrf-canary",
        },
        body: "username=maintainer+one&password=p%26ss+word",
      }),
    );
  });

  it("fetches a fresh CSRF token before an empty-body logout", async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(csrfResponse())
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    await createHttpAuthActions(fetcher).signOut(new AbortController().signal);
    expect(fetcher).toHaveBeenNthCalledWith(
      2,
      "/api/auth/logout",
      expect.objectContaining({
        method: "POST",
        credentials: "same-origin",
        cache: "no-store",
        headers: { Accept: "application/json", "X-CSRF-TOKEN": "csrf-canary" },
      }),
    );
    expect("body" in fetcher.mock.calls[1][1]!).toBe(false);
  });

  it("maps upstream failure to a fixed error and honors an already-aborted caller", async () => {
    const failing = vi
      .fn<typeof fetch>()
      .mockResolvedValue(
        new Response("private upstream detail", { status: 401 }),
      );
    await expect(
      createHttpAuthActions(failing).signOut(new AbortController().signal),
    ).rejects.toMatchObject({
      code: "session",
      message: "Please sign in to continue.",
    });
    expect((await failing.mock.results[0].value).bodyUsed).toBe(false);

    const controller = new AbortController();
    controller.abort();
    const untouched = vi.fn<typeof fetch>();
    await expect(
      createHttpAuthActions(untouched).signIn("a", "b", controller.signal),
    ).rejects.toMatchObject({ code: "cancelled" });
    expect(untouched).not.toHaveBeenCalled();
  });
});
