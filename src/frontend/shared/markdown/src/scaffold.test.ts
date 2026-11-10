import { describe, expect, it } from "vitest";

describe("package scaffold", () => {
  it("runs vitest in jsdom", () => {
    expect(typeof document.createElement).toBe("function");
  });
});
