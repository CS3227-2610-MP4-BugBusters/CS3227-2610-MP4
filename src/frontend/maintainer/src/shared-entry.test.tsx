import { afterEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ComponentType } from "react";
import { App } from "./App";
import { createDemoQueries } from "./demo";
import { QueryError } from "./domain";

afterEach(cleanup);

type SharedEntryProps = React.ComponentProps<typeof App> & {
  sharedEntry: boolean;
  navigate: (path: string) => void;
};
const SharedEntry = App as unknown as ComponentType<SharedEntryProps>;

it.each([
  ["DEVELOPER", "/developer/"],
  ["REPORTER", "/reporter/"],
] as const)("routes an active %s to its role workspace after sign in", async (role, path) => {
  let signedIn = false;
  const navigate = vi.fn();
  const listReports = vi.fn();
  const queries = {
    ...createDemoQueries(),
    getCurrentUser: async () => {
      if (!signedIn) throw new QueryError("session");
      return { id: "00000000-0000-4000-8000-000000000001", displayName: "User", role, active: true };
    },
    listReports,
  };
  const authActions = { signIn: vi.fn(async () => { signedIn = true; }), signOut: vi.fn() };
  render(<SharedEntry queries={queries} authActions={authActions} sharedEntry navigate={navigate} />);
  await screen.findByLabelText("Username");
  fireEvent.change(screen.getByLabelText("Username"), { target: { value: "user" } });
  fireEvent.change(screen.getByLabelText("Password"), { target: { value: "password" } });
  fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
  await waitFor(() => expect(navigate).toHaveBeenCalledWith(path));
  expect(listReports).not.toHaveBeenCalled();
});

it("blocks inactive accounts without redirecting or reading reports", async () => {
  const navigate = vi.fn();
  const listReports = vi.fn();
  render(<SharedEntry sharedEntry navigate={navigate} queries={{ ...createDemoQueries(), listReports,
    getCurrentUser: async () => ({ id: "00000000-0000-4000-8000-000000000001", displayName: "User", role: "DEVELOPER", active: false }) }} />);
  await screen.findByText("Active Maintainer access is required.");
  expect(navigate).not.toHaveBeenCalled();
  expect(listReports).not.toHaveBeenCalled();
});

it("keeps Maintainer on the root queue", async () => {
  const navigate = vi.fn();
  render(<SharedEntry sharedEntry navigate={navigate} queries={createDemoQueries()} />);
  await screen.findByText("63 matching reports");
  expect(navigate).not.toHaveBeenCalled();
});

it("routes an existing Developer session without another login", async () => {
  const navigate = vi.fn();
  const listReports = vi.fn();
  render(<SharedEntry sharedEntry navigate={navigate} queries={{ ...createDemoQueries(), listReports,
    getCurrentUser: async () => ({ id: "00000000-0000-4000-8000-000000000001", displayName: "User", role: "DEVELOPER", active: true }) }} />);
  await waitFor(() => expect(navigate).toHaveBeenCalledWith("/developer/"));
  expect(listReports).not.toHaveBeenCalled();
});
