import { afterEach, describe, expect, it, vi } from "vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { App } from "./App";
import { createDemoQueries } from "./demo";
import { QueryError } from "./domain";

afterEach(cleanup);

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

describe("authentication action cancellation", () => {
  it("recovers sign-in controls after refresh and ignores a late login result", async () => {
    const pending = deferred<void>();
    const signIn = vi.fn(
      (_u: string, _p: string, _signal: AbortSignal) => pending.promise,
    );
    const listReports = vi.fn(createDemoQueries().listReports);
    const queries = {
      ...createDemoQueries(),
      getCurrentUser: vi.fn(async () => {
        throw new QueryError("session");
      }),
      listReports,
    };
    render(
      <App
        queries={queries}
        authActions={{ signIn, signOut: vi.fn(async () => undefined) }}
      />,
    );
    await screen.findByText("Please sign in to continue.");
    fireEvent.change(screen.getByLabelText("Username"), {
      target: { value: "maintainer.one" },
    });
    fireEvent.change(screen.getByLabelText("Password"), {
      target: { value: "secret" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    await waitFor(() => expect(signIn).toHaveBeenCalledOnce());
    const signal = signIn.mock.calls[0][2];

    fireEvent.click(
      screen.getByRole("button", { name: "Refresh session and queue" }),
    );
    await screen.findByText("Please sign in to continue.");
    expect(signal.aborted).toBe(true);
    expect(
      (screen.getByRole("button", { name: "Sign in" }) as HTMLButtonElement)
        .disabled,
    ).toBe(false);
    pending.resolve();
    await waitFor(() =>
      expect(
        (screen.getByRole("button", { name: "Sign in" }) as HTMLButtonElement)
          .disabled,
      ).toBe(false),
    );
    expect(listReports).not.toHaveBeenCalled();
    expect(screen.queryByText("63 matching reports")).toBeNull();
  });

  it("recovers sign-out controls after refresh and ignores a late logout result", async () => {
    const pending = deferred<void>();
    const signOut = vi.fn((_signal: AbortSignal) => pending.promise);
    const listReports = vi.fn(createDemoQueries().listReports);
    let identityCalls = 0;
    const queries = {
      ...createDemoQueries(),
      getCurrentUser: async (signal: AbortSignal) => {
        identityCalls++;
        if (identityCalls > 1) throw new QueryError("session");
        return createDemoQueries().getCurrentUser(signal);
      },
      listReports,
    };
    render(
      <App
        queries={queries}
        authActions={{ signIn: vi.fn(async () => undefined), signOut }}
      />,
    );
    await screen.findByText("63 matching reports");
    fireEvent.click(screen.getByRole("button", { name: "Sign out" }));
    expect(screen.queryByText("63 matching reports")).toBeNull();
    const signal = signOut.mock.calls[0][0];

    fireEvent.click(
      screen.getByRole("button", { name: "Refresh session and queue" }),
    );
    await screen.findByLabelText("Username");
    expect(signal.aborted).toBe(true);
    pending.resolve();
    await waitFor(() =>
      expect(
        (screen.getByRole("button", { name: "Sign in" }) as HTMLButtonElement)
          .disabled,
      ).toBe(false),
    );
    expect(screen.queryByText("63 matching reports")).toBeNull();
    expect(listReports).toHaveBeenCalledTimes(1);
  });
});
