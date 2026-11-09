import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ComponentType } from "react";
import { App } from "./App";
import { QueryError, type Queries } from "./domain";
import { createDemoQueries } from "./demo";

afterEach(cleanup);

type TestAuthActions = {
  signIn(username: string, password: string, signal: AbortSignal): Promise<void>;
  signOut(signal: AbortSignal): Promise<void>;
};
type AuthAppProps = React.ComponentProps<typeof App> & { authActions: TestAuthActions };
const AuthApp = App as unknown as ComponentType<AuthAppProps>;
const unauthenticated = (): Partial<Queries> => ({
  getCurrentUser: async () => { throw new QueryError("session"); },
});
const actions = (overrides: Partial<TestAuthActions> = {}): TestAuthActions => ({
  signIn: vi.fn(async () => undefined),
  signOut: vi.fn(async () => undefined),
  ...overrides,
});

describe("Maintainer sign-in and sign-out", () => {
  it("shows a sign-in form on an unauthenticated session without protected reads", async () => {
    const list = vi.fn();
    render(<AuthApp queries={{ ...createDemoQueries(), ...unauthenticated(), listReports: list }} authActions={actions()} />);
    await screen.findByText("Please sign in to continue.");
    expect(screen.getByLabelText("Username")).toBeTruthy();
    expect(screen.getByLabelText("Password")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Sign in" })).toBeTruthy();
    expect(screen.queryByLabelText("Role")).toBeNull();
    expect(list).not.toHaveBeenCalled();
  });

  it("submits credentials, blocks duplicates, then trusts refreshed identity before queue reads", async () => {
    const list = vi.fn(createDemoQueries().listReports);
    let finishSignIn!: () => void;
    const pending = new Promise<void>((resolve) => { finishSignIn = resolve; });
    const signIn = vi.fn(async () => { await pending; });
    let signedIn = false;
    const identity = vi.fn(async (signal: AbortSignal) => {
      if (!signedIn) throw new QueryError("session");
      return createDemoQueries().getCurrentUser(signal);
    });
    render(<AuthApp queries={{ ...createDemoQueries(), getCurrentUser: identity, listReports: list }} authActions={actions({ signIn })} />);
    await screen.findByText("Please sign in to continue.");
    fireEvent.change(screen.getByLabelText("Username"), { target: { value: "maintainer.one" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "secret-value" } });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    await waitFor(() => expect(signIn).toHaveBeenCalledTimes(1));
    expect(signIn).toHaveBeenCalledWith("maintainer.one", "secret-value", expect.any(AbortSignal));
    expect((screen.getByRole("button", { name: "Signing in…" }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "Signing in…" }));
    expect(signIn).toHaveBeenCalledTimes(1);
    expect(list).not.toHaveBeenCalled();
    signedIn = true;
    await act(async () => finishSignIn());
    await screen.findByText("63 matching reports");
    expect(identity).toHaveBeenCalledTimes(2);
    expect(list).toHaveBeenCalled();
  });

  it("denies a wrong-role identity access to the queue while allowing sign-out", async () => {
    const list = vi.fn();
    const queries = {
      ...createDemoQueries(),
      getCurrentUser: async () => ({ id: "00000000-0000-4000-8000-000000000001", displayName: "Dev", active: true, role: "DEVELOPER" as const }),
      listReports: list,
    };
    render(<AuthApp queries={queries} authActions={actions()} />);
    await screen.findByText("Active Maintainer access is required.");
    expect(list).not.toHaveBeenCalled();
    expect(screen.queryByText("63 matching reports")).toBeNull();
    expect(screen.getByRole("button", { name: "Sign out" })).toBeTruthy();
  });

  it("keeps username, clears password, and shows a safe recoverable error", async () => {
    const signIn = vi.fn(async () => { throw new Error("password/private detail"); });
    render(<AuthApp queries={{ ...createDemoQueries(), ...unauthenticated() }} authActions={actions({ signIn })} />);
    await screen.findByText("Please sign in to continue.");
    fireEvent.change(screen.getByLabelText("Username"), { target: { value: "maintainer.one" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "canary-secret" } });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    expect((await screen.findByRole("alert")).textContent).toContain("The service is unavailable. Please retry.");
    expect(screen.queryByText(/canary-secret|password\/private detail/)).toBeNull();
    expect((screen.getByLabelText("Username") as HTMLInputElement).value).toBe("maintainer.one");
    expect((screen.getByLabelText("Password") as HTMLInputElement).value).toBe("");
  });

  it("clears queue during sign-out and blocks sign-in until logout settles", async () => {
    let finishSignOut!: () => void;
    const pending = new Promise<void>((resolve) => { finishSignOut = resolve; });
    const signOut = vi.fn(() => pending);
    const signIn = vi.fn(async () => undefined);
    render(<AuthApp queries={createDemoQueries()} authActions={actions({ signIn, signOut })} />);
    await screen.findByText("63 matching reports");
    fireEvent.click(screen.getByRole("button", { name: "Sign out" }));
    expect(screen.queryByText("63 matching reports")).toBeNull();
    expect(await screen.findByLabelText("Username")).toBeTruthy();
    const loginButton = screen.getByRole("button", { name: "Sign in" }) as HTMLButtonElement;
    expect(loginButton.disabled).toBe(true);
    fireEvent.click(loginButton);
    expect(signIn).not.toHaveBeenCalled();
    expect(signOut).toHaveBeenCalledTimes(1);
    await act(async () => finishSignOut());
    await waitFor(() => expect((screen.getByRole("button", { name: "Sign in" }) as HTMLButtonElement).disabled).toBe(false));
    expect(screen.getByLabelText("Username")).toBeTruthy();
  });
});
