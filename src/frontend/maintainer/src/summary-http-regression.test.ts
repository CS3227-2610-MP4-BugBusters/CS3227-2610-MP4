import { expect, it, vi } from "vitest";
import { createHttpQueries } from "./http";
import { withHttpActions } from "./action-http";

it("rejects unknown summary request fields before fetch", async () => {
  const fetcher = vi.fn<typeof fetch>();
  const queries = withHttpActions(createHttpQueries(fetcher), fetcher);
  const summarize = queries.summarizeQueue;
  expect(typeof summarize).toBe("function");
  if (typeof summarize !== "function") throw new Error("summary HTTP transport is missing");

  await expect(
    summarize({ filters: {}, regenerate: false, actor: "forged" } as never),
  ).rejects.toMatchObject({ code: "validation" });
  expect(fetcher).not.toHaveBeenCalled();
});
