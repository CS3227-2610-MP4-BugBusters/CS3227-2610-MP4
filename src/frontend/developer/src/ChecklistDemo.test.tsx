import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { ChecklistDemo } from "./ChecklistDemo";

afterEach(cleanup);

function edit(value: string) {
  fireEvent.change(screen.getByLabelText(/investigation steps/i), { target: { value } });
}

function click(name: "Save" | "Share") {
  fireEvent.click(screen.getByRole("button", { name }));
}

function preview(value: string) {
  fireEvent.change(screen.getByLabelText("Preview as"), { target: { value } });
}

function published() {
  return within(screen.getByRole("region", { name: "Shared checklist" }))
    .queryAllByRole("listitem")
    .map((item) => item.textContent);
}

describe("synthetic checklist workflow", () => {
  it("publishes the saved steps and keeps the next private draft out of both previews", () => {
    render(<ChecklistDemo />);
    edit("Reproduce the export\nCheck the network response");
    click("Save");
    expect(screen.getByText(/report status: resolution proposed/i)).toBeTruthy();

    preview("Reporter");
    expect(published()).toEqual([]);
    preview("Developer");
    expect((screen.getByRole("textbox") as HTMLTextAreaElement).value).toContain("Reproduce the export");
    edit("Unsaved replacement");
    click("Share");
    expect(published()).toEqual(["Reproduce the export", "Check the network response"]);
    expect(screen.getByText(/report status: in progress/i)).toBeTruthy();

    edit("Private follow-up");
    click("Save");
    for (const role of ["Reporter", "Maintainer"]) {
      preview(role);
      expect(published()).toEqual(["Reproduce the export", "Check the network response"]);
      expect(screen.queryByRole("textbox")).toBeNull();
      expect(screen.queryByRole("button", { name: "Share" })).toBeNull();
      expect(screen.queryByText("Private follow-up")).toBeNull();
    }

    preview("Developer");
    click("Share");
    preview("Reporter");
    expect(published()).toEqual(["Private follow-up"]);
  });

  it("rejects sharing an empty saved draft and preserves the published steps", () => {
    render(<ChecklistDemo />);
    click("Share");
    expect(screen.getByRole("status").textContent).toBe("Add at least one step before sharing.");
    expect(screen.getByText(/report status: resolution proposed/i)).toBeTruthy();

    edit("Check the browser logs");
    click("Save");
    click("Share");
    edit("   \n ");
    click("Save");
    click("Share");
    expect(screen.getByRole("status").textContent).toBe("Add at least one step before sharing.");
    preview("Maintainer");
    expect(published()).toEqual(["Check the browser logs"]);
  });
});
