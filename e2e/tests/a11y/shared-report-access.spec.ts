import { AxeBuilder } from "@axe-core/playwright";
import { expect, test } from "@playwright/test";
import { reports } from "../../support/named-dashboards.js";
import { chooseOption } from "../../support/select.js";

for (const theme of ["light", "dark"] as const) {
  test(`shared saved report keeps its source and fails closed after revocation in ${theme}`, async ({ page }) => {
    const runtimeErrors: string[] = [];
    page.on("pageerror", (error) => runtimeErrors.push(error.message));
    await page.setViewportSize({ width: 360, height: 740 });
    await page.addInitScript((value) => localStorage.setItem("smc_theme", value), theme);
    const shared = { ...reports[0], canEdit: false, canShare: false, shared: true };
    await page.route("**/api/v1/report-views?**", (route) =>
      route.fulfill({ json: { items: [shared], hasMore: false, nextCursor: null } }),
    );
    let allowed = true;
    let adhoc = 0;
    let exports = 0;
    await page.route("**/api/v1/entities/example.orders/report?**", (route) => {
      adhoc++;
      return route.continue();
    });
    await page.route("**/api/v1/entities/example.orders/reports/7", (route) =>
      allowed
        ? route.continue()
        : route.fulfill({ status: 403, json: { status: 403, detail: "Shared access revoked" } }),
    );
    await page.route("**/api/v1/entities/example.orders/reports/7/export?**", (route) => {
      exports++;
      return route.fulfill({
        status: 403,
        contentType: "application/problem+json",
        json: { status: 403, detail: "Export access revoked" },
      });
    });
    await page.goto("/e/example.orders");
    await page.getByTestId("entity-mode").getByRole("radio", { name: "Отчёт", exact: true }).click();
    const builder = page.getByTestId("report-builder");
    await expect(builder.getByTestId("report-export")).toBeVisible();
    await chooseOption(page.getByTestId("report-saved").getByRole("combobox"), "7");
    await expect(builder.getByText("Сохранённый отчёт: Продажи", { exact: true })).toBeVisible();
    await expect(
      page.getByTestId("report-saved").getByRole("button", { name: "Переименовать отчёт", exact: true }),
    ).toHaveCount(0);
    await builder.getByTestId("report-export").click();
    await expect(builder.getByRole("alert")).toContainText("Export access revoked");
    expect(exports).toBe(1);
    const before = adhoc;
    allowed = false;
    await page.reload();
    await page.getByTestId("entity-mode").getByRole("radio", { name: "Отчёт", exact: true }).click();
    await expect(builder.getByTestId("report-export")).toBeVisible();
    await chooseOption(page.getByTestId("report-saved").getByRole("combobox"), "7");
    await expect(builder.getByTestId("report-error")).toHaveText("Shared access revoked");
    await expect(builder.getByTestId("report-export")).toBeDisabled();
    const selected = adhoc;
    await builder.getByRole("button", { name: "Повторить", exact: true }).click();
    await expect(builder.getByTestId("report-error")).toHaveText("Shared access revoked");
    expect(adhoc).toBe(selected);
    expect(selected).toBeGreaterThanOrEqual(before);
    const audit = await new AxeBuilder({ page })
      .include('[data-testid="report-builder"]')
      .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
      .analyze();
    expect(audit.violations).toEqual([]);
    await builder.getByRole("button", { name: "Изменить свой запрос", exact: true }).click();
    await expect(builder.getByTestId("report-export")).toBeEnabled();
    expect(runtimeErrors).toEqual([]);
  });
}
