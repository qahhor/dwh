import { expect, test, type Page } from "@playwright/test";
import { AxeBuilder } from "@axe-core/playwright";

// ADR-0041, stage 1, branch 3: the section tabs of a record form, the record title and layout editor of a live form and
// the reports catalog are read by assistive technology, work from the keyboard and fit a phone, in both themes.

async function audit(page: Page, include?: string) {
  const builder = new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]);
  if (include) builder.include(include);
  const result = await builder.analyze();
  expect(
    result.violations.map((violation) => `${violation.id}: ${violation.nodes.map((node) => node.target)}`),
  ).toEqual([]);
}

async function noHorizontalScroll(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
}

const tabbedForm = {
  code: "ms.notes",
  listCode: "ms.notes",
  titleField: "title",
  fields: [
    { key: "title", labelKey: "", label: "Название клиента", type: "text", required: true, maxLength: 200 },
    { key: "phone", labelKey: "", label: "Телефон", type: "text", required: false, maxLength: 30 },
    { key: "amount", labelKey: "", label: "Сумма заказов", type: "number", required: true, scale: 2 },
  ],
  layout: [
    { key: "general", labelKey: "", label: "Общее", fields: ["title", "phone"] },
    { key: "money", labelKey: "", label: "Финансы", fields: ["amount"] },
  ],
  tabs: [
    { key: "main", labelKey: "", label: "Основное", kind: "sections", sections: ["general"] },
    { key: "more", labelKey: "", label: "Деньги", kind: "sections", sections: ["money"] },
    { key: "history", labelKey: "ui.entity_page.tab_history", kind: "history" },
  ],
  actions: ["create"],
  capabilities: ["history"],
};

function report(id: number, owned: boolean) {
  return {
    id,
    entity: "ms.notes",
    listCode: "ms.notes",
    kind: "report",
    name: `По регионам ${id}`,
    state: { groupBy: [{ field: "color" }], measures: [{ op: "count" }], filter: [], chart: "bar", sort: { by: "measure", measure: 0, direction: "desc" }, limit: 5 },
    lockVersion: 2,
    modifiedAt: "2026-10-18T08:00:00Z",
    canEdit: owned,
    canShare: owned,
    shared: !owned,
    owned,
  };
}

for (const theme of ["light", "dark"] as const) {
  test.describe(`stage 1 layout and reports ${theme}`, () => {
    let errors: string[];
    test.beforeEach(async ({ page }) => {
      errors = [];
      page.on("pageerror", (error) => errors.push(error.message));
      await page.addInitScript((value) => localStorage.setItem("smc_theme", value), theme);
    });
    test.afterEach(() => expect(errors).toEqual([]));

    test("a record form on two tabs is reached by keyboard and fits a phone", async ({ page }) => {
      await page.route("**/api/v1/form-meta/ms.notes", (route) => route.fulfill({ json: tabbedForm }));
      await page.setViewportSize({ width: 360, height: 800 });
      await page.goto("/e/ms.notes/new");
      const tabs = page.locator("smt-entity-form .entity-form-tabs");
      await expect(tabs.getByRole("tab")).toHaveText(["Основное", "Деньги"]);
      await expect(page.locator('[data-field="amount"]')).toBeHidden();
      await audit(page);
      await noHorizontalScroll(page);
      await tabs.getByRole("tab", { name: "Основное" }).focus();
      await page.keyboard.press("ArrowRight");
      await expect(tabs.getByRole("tab", { name: "Деньги" })).toBeFocused();
      await expect(page.locator('[data-field="amount"]')).toBeVisible();
      await expect(page.locator('[data-field="title"]')).toBeHidden();
      await page.setViewportSize({ width: 1440, height: 900 });
      await audit(page);
    });

    test("the record title and the layout of a new live form are edited by keyboard on a phone", async ({ page }) => {
      await page.route("**/api/v1/studio/activation", (route) =>
        route.fulfill({ json: { enabled: true, namespace: "studio", maxForms: 100 } }),
      );
      await page.route("**/api/v1/modules/active", async (route) => {
        const modules = await (await route.fetch()).json();
        await route.fulfill({
          json: [...modules, { code: "studio", name: "Созданные формы", status: "ACTIVE", isActive: true, isSystem: false }],
        });
      });
      await page.route((url) => url.pathname === "/api/v1/studio/lists", (route) => route.fulfill({ json: { items: [], hasMore: false } }));
      await page.route((url) => url.pathname === "/api/v1/studio/forms", (route) => route.fulfill({ json: { items: [], hasMore: false } }));
      await page.setViewportSize({ width: 360, height: 800 });
      await page.goto("/settings/form-studio/new");
      const delivery = page.getByTestId("new-form-delivery").getByRole("combobox");
      await delivery.focus();
      await page.keyboard.press("Enter");
      const search = page.locator('input[role="combobox"][aria-autocomplete="list"]');
      await search.press("ArrowDown");
      await search.press("Enter");
      await page.getByTestId("new-form-add").click();
      const layout = page.getByTestId("form-layout");
      await expect(layout.getByTestId("form-title-field").getByRole("combobox")).toBeVisible();
      const toggle = layout.getByTestId("form-layout-toggle").getByRole("checkbox");
      await toggle.focus();
      await page.keyboard.press("Space");
      await expect(layout.getByTestId("form-section-0")).toBeVisible();
      await layout.getByTestId("form-section-add").click();
      await layout.getByTestId("form-tabs-toggle").getByRole("checkbox").click();
      await expect(layout.getByTestId("form-tab-1")).toBeVisible();
      await audit(page, '[data-testid="form-layout"]');
      await noHorizontalScroll(page);
      await page.setViewportSize({ width: 1440, height: 1000 });
      await audit(page, '[data-testid="form-layout"]');
    });

    test("the reports catalog lists, narrows and opens reports by keyboard", async ({ page }) => {
      const asked: string[] = [];
      await page.route(
        (url) => url.pathname === "/api/v1/report-views",
        (route) => {
          asked.push(new URL(route.request().url()).search);
          return route.fulfill({ json: { items: [report(1, true), report(2, false)], hasMore: false, nextCursor: null } });
        },
      );
      await page.setViewportSize({ width: 360, height: 800 });
      await page.goto("/reports");
      await expect(page.getByTestId("reports-item")).toHaveCount(2);
      await audit(page);
      await noHorizontalScroll(page);
      await page.getByTestId("reports-search").fill("регион");
      await expect.poll(() => asked.at(-1) ?? "").toContain("q=%D1%80%D0%B5%D0%B3%D0%B8%D0%BE%D0%BD");
      const open = page.getByTestId("reports-item").first().getByTestId("reports-open");
      await open.focus();
      await expect(open).toBeFocused();
      await expect(open).toHaveAttribute("href", "/e/ms.notes?mode=report&report=1");
      await page.setViewportSize({ width: 1440, height: 900 });
      await audit(page);
    });
  });
}
