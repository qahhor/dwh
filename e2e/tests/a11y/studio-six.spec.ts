import { expect, test } from "@playwright/test";
import { chooseOption } from "../../support/select.js";
import { auditPanels } from "../../support/named-dashboards.js";
import { auditSimpleForm, fillSimpleForm } from "../../support/new-form-studio.js";
import { auditStudio06, proposedForm, reportStudioServer, studioDataset } from "../../support/studio-six.js";

for (const theme of ["light", "dark"] as const) {
  test.describe(`studio six ${theme}`, () => {
    let errors: string[];
    test.beforeEach(async ({ page }) => {
      errors = [];
      page.on("pageerror", (error) => errors.push(error.message));
      page.on("console", (message) => {
        if (message.type() === "error" && !message.text().startsWith("Failed to load resource:"))
          errors.push(message.text());
      });
      await page.setViewportSize({ width: 360, height: 740 });
      await page.addInitScript((value) => localStorage.setItem("smc_theme", value), theme);
    });
    test.afterEach(() => expect(errors).toEqual([]));

    test("selects a dataset and keeps its metadata, chart and export together, then fails closed", async ({ page }) => {
      const state = await reportStudioServer(page);
      await page.goto("/e/ms.tasks");
      await page.getByTestId("entity-mode").getByRole("radio", { name: "Отчёт", exact: true }).click();
      const builder = page.getByTestId("report-builder");
      await chooseOption(builder.getByTestId("report-dataset").getByRole("combobox"), studioDataset);
      await chooseOption(builder.getByLabel("Группировать по", { exact: true }), "priority");
      const horizontal = builder.getByRole("radio", { name: "Горизонтальные столбцы", exact: true });
      await expect(horizontal).toBeEnabled();
      await horizontal.focus();
      await page.keyboard.press("Space");
      await expect(horizontal).toBeChecked();
      await expect(builder.getByTestId("report-horizontal-bar")).toBeVisible();
      await expect(builder.getByTestId("report-table").locator("tbody tr")).toHaveCount(4);
      expect(state.queries.at(-1)).toContain("dataset=ms.tasks.projects");
      await expect(builder.getByTestId("report-export")).toBeEnabled();
      const download = page.waitForEvent("download");
      await builder.getByTestId("report-export").click();
      await download;
      expect(state.exports[0]).toContain("dataset=ms.tasks.projects");
      await auditStudio06(page, '[data-testid="report-builder"]');
      await builder.getByTestId("report-horizontal-bar").locator("..").scrollIntoViewIfNeeded();
      await expect(builder.getByTestId("report-horizontal-bar")).toBeInViewport({ ratio: 1 });
      await page.screenshot({ path: `../.local/studio-06-${theme}-dataset-mobile.png`, fullPage: false });
      await builder.getByTestId("report-table").scrollIntoViewIfNeeded();
      await expect(builder.getByTestId("report-table")).toBeInViewport({ ratio: 1 });
      await page.screenshot({ path: `../.local/studio-06-${theme}-dataset-table-mobile.png`, fullPage: false });
      await chooseOption(builder.getByLabel("Группировать по", { exact: true }), "projectCreatedAt");
      await chooseOption(builder.getByLabel("Период", { exact: true }), "day");
      await builder.getByRole("radio", { name: "Линия", exact: true }).click();
      await expect(builder.getByTestId("report-line")).toBeVisible();
      await expect(builder.getByTestId("line-point")).toHaveCount(4);
      await expect(builder.getByTestId("line-segment")).toHaveCount(2);
      await builder.getByTestId("report-line").locator("..").scrollIntoViewIfNeeded();
      await expect(builder.getByTestId("report-line")).toBeInViewport({ ratio: 1 });
      await page.screenshot({ path: `../.local/studio-06-${theme}-dataset-line-mobile.png`, fullPage: false });
      await page.setViewportSize({ width: 1440, height: 1000 });
      await auditStudio06(page, '[data-testid="report-builder"]');
      await page.screenshot({ path: `../.local/studio-06-${theme}-dataset-desktop.png`, fullPage: true });
      await chooseOption(builder.getByTestId("report-dataset").getByRole("combobox"), "");
      await expect(builder.getByTestId("report-export")).toBeEnabled();
      await expect(builder.getByTestId("report-line")).toHaveCount(0);
      state.allowed = false;
      const before = state.queries.length;
      await chooseOption(builder.getByTestId("report-dataset").getByRole("combobox"), studioDataset);
      await expect(builder.getByRole("alert").filter({ hasText: "Dataset access revoked" }).first()).toBeVisible();
      await expect(builder.getByTestId("report-export")).toBeDisabled();
      await expect(builder.getByTestId("report-line")).toHaveCount(0);
      expect(state.queries.length).toBe(before);
    });

    test("renders both charts and exact values on named panels without overlapping actions", async ({ page }) => {
      await reportStudioServer(page);
      await page.goto("/analytics?dashboard=6");
      await expect(page.getByTestId("report-line")).toBeVisible();
      await expect(page.getByTestId("report-horizontal-bar")).toBeVisible();
      await expect(page.getByTestId("report-table")).toHaveCount(2);
      await auditPanels(page);
      await page.getByTestId("report-line").locator("..").scrollIntoViewIfNeeded();
      await expect(page.getByTestId("report-line")).toBeInViewport({ ratio: 1 });
      await page.screenshot({ path: `../.local/studio-06-${theme}-panels-mobile.png`, fullPage: false });
      await page.getByTestId("report-horizontal-bar").locator("..").scrollIntoViewIfNeeded();
      await expect(page.getByTestId("report-horizontal-bar")).toBeInViewport({ ratio: 1 });
      await page.screenshot({ path: `../.local/studio-06-${theme}-panels-horizontal-mobile.png`, fullPage: false });
      for (const index of [0, 1]) {
        const table = page.getByTestId("report-table").nth(index);
        await table.scrollIntoViewIfNeeded();
        await expect(table).toBeInViewport({ ratio: 1 });
        await page.screenshot({ path: `../.local/studio-06-${theme}-panels-table-${index}-mobile.png`, fullPage: false });
      }
      await page.setViewportSize({ width: 1440, height: 1000 });
      await auditPanels(page);
      await page.screenshot({ path: `../.local/studio-06-${theme}-panels-desktop.png`, fullPage: true });
    });

    test("shows a separate proposal, preserves dirty input on Cancel and applies via keyboard without publishing", async ({
      page,
    }) => {
      const writes: string[] = [];
      await page.route("**/api/v1/studio/assistant", (route) => route.fulfill({ json: { available: true } }));
      await page.route("**/api/v1/studio/assistant/drafts", (route) => {
        expect(route.request().headers()["idempotency-key"]).toBeUndefined();
        writes.push(route.request().url());
        return route.fulfill({ json: proposedForm });
      });
      await page.route("**/api/v1/studio/forms", (route) => {
        writes.push(route.request().url());
        return route.fulfill({ status: 500, json: {} });
      });
      await page.goto("/settings/form-studio/new");
      await fillSimpleForm(page);
      const assistant = page.getByTestId("new-form-assistant");
      await assistant.getByRole("textbox").fill("Подготовь форму помещений: название обязательно.");
      await expect(assistant.getByTestId("assistant-generate")).toBeEnabled();
      await assistant.getByTestId("assistant-generate").focus();
      await page.keyboard.press("Enter");
      await expect(assistant.getByTestId("assistant-proposal")).toContainText("Помещения");
      await expect(page.getByTestId("new-entity-name")).toHaveValue("locations");
      await auditSimpleForm(page);
      await auditStudio06(page, '[data-testid="new-form-assistant"]');
      await page.screenshot({ path: `../.local/studio-06-${theme}-assistant-mobile.png`, fullPage: true });
      await assistant.getByTestId("assistant-apply").click();
      await expect(page.getByRole("alertdialog")).toBeVisible();
      await page.keyboard.press("Escape");
      await expect(page.getByRole("alertdialog")).toHaveCount(0);
      await expect(assistant.getByTestId("assistant-apply")).toBeEnabled();
      await expect(page.getByTestId("new-entity-name")).toHaveValue("locations");
      await assistant.getByTestId("assistant-apply").focus();
      await page.keyboard.press("Enter");
      await expect(page.getByRole("alertdialog")).toBeVisible();
      await page.getByRole("alertdialog").getByRole("button", { name: "Не сохранять", exact: true }).focus();
      await page.keyboard.press("Enter");
      await expect(page.getByTestId("new-entity-name")).toHaveValue("rooms");
      await expect(page.getByTestId("new-module-code")).toBeFocused();
      await expect(page.getByTestId("new-form-delivery")).toContainText("CLI");
      await expect(page.getByTestId("new-form-download")).toBeDisabled();
      expect(writes).toHaveLength(1);
      await page.setViewportSize({ width: 1440, height: 1000 });
      await auditSimpleForm(page);
      await page.screenshot({ path: `../.local/studio-06-${theme}-assistant-desktop.png`, fullPage: true });
    });
  });
}

test("assistant retains the prompt after 429 and makes no automatic retry", async ({ page }) => {
  let calls = 0;
  await page.route("**/api/v1/studio/assistant", (route) => route.fulfill({ json: { available: true } }));
  await page.route("**/api/v1/studio/assistant/drafts", (route) => {
    calls++;
    return route.fulfill({ status: 429, json: { detail: "Assistant busy" } });
  });
  await page.goto("/settings/form-studio/new");
  await page.getByTestId("assistant-prompt").getByRole("textbox").fill("A room form");
  await page.getByTestId("assistant-generate").click();
  await expect(page.getByTestId("new-form-assistant").getByRole("alert")).toContainText("Assistant busy");
  await expect(page.getByTestId("assistant-prompt").getByRole("textbox")).toHaveValue("A room form");
  expect(calls).toBe(1);
  await page.getByTestId("assistant-generate").click();
  await expect.poll(() => calls).toBe(2);
  await expect(page.getByTestId("assistant-proposal")).toHaveCount(0);
});

test("a late assistant response cannot replace newer manual input", async ({ page }) => {
  let release: (() => void) | undefined;
  const pending = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route("**/api/v1/studio/assistant", (route) => route.fulfill({ json: { available: true } }));
  await page.route("**/api/v1/studio/assistant/drafts", async (route) => {
    await pending;
    await route.fulfill({ json: proposedForm }).catch(() => undefined);
  });
  await page.goto("/settings/form-studio/new");
  await page.getByTestId("assistant-prompt").getByRole("textbox").fill("A room form");
  await page.getByTestId("assistant-generate").click();
  await page.getByTestId("new-entity-name").fill("manual");
  release!();
  await expect(page.getByTestId("assistant-generate")).toBeEnabled();
  await expect(page.getByTestId("assistant-proposal")).toHaveCount(0);
  await expect(page.getByTestId("new-entity-name")).toHaveValue("manual");
});
