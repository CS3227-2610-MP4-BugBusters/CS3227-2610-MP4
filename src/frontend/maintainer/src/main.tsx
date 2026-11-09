import { createRoot } from "react-dom/client";
import { App } from "./App";
import { createHttpQueries } from "./http";
import { createHttpAuthActions } from "./auth-client";
import { withHttpActions } from "./action-http";
import "./style.css";
async function start() {
  const synthetic = import.meta.env.DEV && import.meta.env.MODE === "demo";
  const queries = synthetic
    ? (await import("./demo")).createDemoQueries(undefined, true)
    : withHttpActions(createHttpQueries());
  createRoot(document.getElementById("root")!).render(
    <App
      queries={queries}
      synthetic={synthetic}
      authActions={synthetic ? undefined : createHttpAuthActions()}
    />,
  );
}
void start();
