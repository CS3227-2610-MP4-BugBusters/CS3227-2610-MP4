const { test, expect } = require("@playwright/test");

test("invalid credentials cannot sign in", async ({ page }) => {
  await page.goto("/developer/");
  await page.getByLabel("Username").fill("not-a-real-developer");
  await page.getByLabel("Password").fill("incorrect-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByText("Sign in failed. Check your credentials and try again.")).toBeVisible();
  await expect(page.getByRole("region", { name: "Assigned reports" })).toHaveCount(0);
});

test("an unassigned Developer cannot read another Developer's checklist", async ({ page }) => {
  const { MP4_BROWSER_OTHER_USERNAME: username, MP4_BROWSER_OTHER_PASSWORD: password,
    MP4_BROWSER_REPORT_ID: reportId } = process.env;
  if (!username || !password || !reportId) throw new Error("Run through run-journey.py.");
  await page.goto("/developer/");
  await page.getByLabel("Username").fill(username);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByText("No assigned reports.")).toBeVisible();
  const response = await page.request.get(`/api/reports/${reportId}/checklist/draft`);
  expect(response.status()).toBe(403);
  expect((await response.json()).code).toBe("FORBIDDEN");
});

test("assigned Developer saves and shares a checklist and proposes a resolution", async ({ page }) => {
  const { MP4_BROWSER_USERNAME: username, MP4_BROWSER_PASSWORD: password,
    MP4_BROWSER_REPORT_ID: reportId } = process.env;
  if (!username || !password || !reportId || !process.env.MP4_BROWSER_ORIGIN) {
    throw new Error("Run this test through run-journey.py with a disposable fixture.");
  }

  await page.goto("/developer/");
  await page.getByLabel("Username").fill(username);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.getByRole("button", { name: /Packaged journey report/ }).click();
  const report = page.getByRole("region", { name: "Selected report" });
  await expect(report.getByText("Status: Assigned")).toBeVisible();
  await report.getByRole("button", { name: "Start investigation" }).click();
  await expect(report.getByText("Status: In Progress")).toBeVisible();

  const checklist = page.getByRole("region", { name: "Investigation checklist" });
  await checklist.getByLabel("Step 1", { exact: true }).fill("Reproduce the export failure");
  await checklist.getByRole("button", { name: "Save", exact: true }).click();
  await expect.poll(async () => {
    const response = await page.request.get(`/api/reports/${reportId}/checklist/draft`);
    return response.ok() ? (await response.json()).steps : null;
  }).toEqual(["Reproduce the export failure"]);
  let published = await page.request.get(`/api/reports/${reportId}/checklist/shared`);
  expect((await published.json()).steps).toEqual([]);

  await checklist.getByRole("button", { name: "Share", exact: true }).click();
  await expect.poll(async () => {
    const response = await page.request.get(`/api/reports/${reportId}/checklist/shared`);
    return response.ok() ? (await response.json()).steps : null;
  }).toEqual(["Reproduce the export failure"]);

  await expect(checklist.getByText("Saved steps shared.")).toBeVisible();
  await checklist.getByLabel("Step 1", { exact: true }).fill("");
  await checklist.getByRole("button", { name: "Save", exact: true }).click();
  await expect.poll(async () => {
    const response = await page.request.get(`/api/reports/${reportId}/checklist/draft`);
    return response.ok() ? (await response.json()).steps : null;
  }).toEqual([]);
  await checklist.getByRole("button", { name: "Share", exact: true }).click();
  await expect(checklist.getByText("Add at least one step before sharing.")).toBeVisible();
  const draft = await (await page.request.get(`/api/reports/${reportId}/checklist/draft`)).json();
  const csrf = await (await page.request.get("/api/auth/csrf")).json();
  const rejected = await page.request.post(`/api/reports/${reportId}/checklist/share`, {
    headers: { [csrf.headerName]: csrf.token },
    data: { expectedDraftRevision: draft.draftRevision, expectedReportVersion: draft.reportVersion },
  });
  expect(rejected.status()).toBe(409);
  expect((await rejected.json()).code).toBe("EMPTY_DRAFT");
  published = await page.request.get(`/api/reports/${reportId}/checklist/shared`);
  expect((await published.json()).steps).toEqual(["Reproduce the export failure"]);

  await report.getByLabel("Resolution", { exact: true }).fill("Fixed the export request handler.");
  await report.getByRole("button", { name: "Submit resolution" }).click();
  await expect(report.getByText("Status: Resolution Proposed")).toBeVisible();
});
