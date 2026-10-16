import { expect, test } from "@playwright/test";
import { dashboardServer, auditPanels } from "../../support/named-dashboards.js";
import { chooseOption } from "../../support/select.js";

for (const theme of ["light", "dark"] as const) {
  test.describe(`named dashboards ${theme}`, () => {
    let runtimeErrors: string[];
    test.afterEach(() => expect(runtimeErrors).toEqual([]));
    test.beforeEach(async ({ page }) => {
      runtimeErrors = [];
      page.on("pageerror", (error) => runtimeErrors.push(error.message));
      await page.setViewportSize({ width: 360, height: 740 });
      await page.addInitScript((value) => localStorage.setItem("smc_theme", value), theme);
    });
    test("isolates unmapped and failed widgets and exports only the filtered accepted query", async ({ page }) => {
      const state = await dashboardServer(page);
      await page.goto("/analytics?dashboard=9&from=2026-10-01&to=2026-10-15");
      const cards = page.getByTestId("dashboard-widget");
      await expect(cards).toHaveCount(2);
      await expect(cards.first().getByTestId("report-kpis")).toContainText("41");
      await expect(cards.last().getByRole("alert")).toContainText("выберите поле даты");
      expect(state.results).toHaveLength(1);
      expect(state.results[0]).toContain("from=2026-10-01&to=2026-10-15");
      expect(state.results[0]).toContain("/7/result");
      state.denyExport = true;
      await cards.first().getByTestId("report-export").click();
      await expect(cards.first().getByRole("alert")).toContainText("Export access revoked");
      expect(state.exports[0]).toContain("from=2026-10-01&to=2026-10-15");
      await auditPanels(page);
      await page.screenshot({ path: `../.local/studio-03-${theme}-mobile.png`, fullPage: true });
      await page.setViewportSize({ width: 1440, height: 1000 });
      await auditPanels(page);
      await page.screenshot({ path: `../.local/studio-03-${theme}-desktop.png`, fullPage: true });
      state.failWidget = true;
      await page.goto("/analytics?dashboard=9");
      await expect(cards.first().getByTestId("report-kpis")).toContainText("41");
      await expect(cards.last().getByRole("alert")).toContainText("Field access revoked");
    });
    test("configures date mapping and keyboard order and preserves changes on conflict", async ({ page }) => {
      const state = await dashboardServer(page);
      await page.goto("/analytics?dashboard=9");
      const panels = page.getByTestId("dashboards");
      await panels.getByRole("button", { name: "Настроить панель", exact: true }).click();
      const first = page.getByTestId("dashboard-edit-widget-7");
      await chooseOption(page.getByTestId("dashboard-edit-widget-8").getByLabel("Поле общего периода"), "orderDate");
      const move = first.getByRole("button", { name: "Опустить Продажи", exact: true });
      await move.focus();
      await page.keyboard.press("Enter");
      await expect(panels.locator('[data-testid^="dashboard-edit-widget-"]').first()).toHaveAttribute(
        "data-testid",
        "dashboard-edit-widget-8",
      );
      state.conflict = true;
      await panels.getByRole("button", { name: "Сохранить", exact: true }).click();
      await expect(panels.getByRole("alert")).toContainText("Ваши изменения сохранены");
      await expect(panels.locator('[data-testid^="dashboard-edit-widget-"]').first()).toHaveAttribute(
        "data-testid",
        "dashboard-edit-widget-8",
      );
      await panels.getByRole("button", { name: "Перечитать панель", exact: true }).click();
      await page.getByRole("button", { name: "Продолжить редактирование", exact: true }).click();
      await expect(panels.locator('[data-testid^="dashboard-edit-widget-"]').first()).toHaveAttribute(
        "data-testid",
        "dashboard-edit-widget-8",
      );
      await auditPanels(page);
      await panels.getByRole("button", { name: "Перечитать панель", exact: true }).click();
      await page.getByRole("button", { name: "Не сохранять", exact: true }).click();
      await expect(panels.getByRole("button", { name: "Настроить панель", exact: true })).toBeVisible();
    });
    test("creates, renames and shares a panel through standard accessible dialogs", async ({ page }) => {
      const state = await dashboardServer(page);
      await page.goto("/analytics");
      const panels = page.getByTestId("dashboards");
      await panels.getByTestId("dashboard-create").click();
      let dialog = page.getByRole("dialog");
      await dialog.getByLabel("Название панели").fill("Новая панель");
      await dialog.getByTestId("form-submit").click();
      await expect(page).toHaveURL(/dashboard=9/u);
      await panels.getByRole("button", { name: "Переименовать", exact: true }).click();
      dialog = page.getByRole("dialog");
      await dialog.getByLabel("Название панели").fill("Общая панель");
      await dialog.getByTestId("form-submit").click();
      await expect(dialog).toHaveCount(0);
      expect(state.name).toBe("Общая панель");
      await panels.getByTestId("report-share").click();
      dialog = page.getByRole("dialog");
      await expect(dialog.getByText("Аналитики", { exact: true })).toBeVisible();
      await auditPanels(page);
      await dialog.getByTestId("form-submit").click();
      await expect(dialog).toHaveCount(0);
      expect(state.writes).toContain("POST /api/v1/dashboards");
      expect(state.writes).toContain("PUT /api/v1/dashboards/9/sharing");
    });
  });
}
