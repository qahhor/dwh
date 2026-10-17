import { expect, test, type Page } from "@playwright/test";
import { AxeBuilder } from "@axe-core/playwright";

const labels = (text: string) => ({ ru: text, uz: text, en: text });

function list(revision = 3) {
  return {
    code: "kinds",
    name: { ru: "Типы заявок", uz: "Ariza turlari", en: "Request types" },
    items: 2,
    activeItems: 1,
    revision,
    createdAt: "2026-10-17T08:00:00Z",
    modifiedAt: "2026-10-17T09:30:00Z",
  };
}

function item(code: string, name: string, active: boolean, revision = 2) {
  return { code, name: labels(name), sortOrder: active ? 10 : 20, active, revision, modifiedAt: "2026-10-17T09:30:00Z" };
}

function visits() {
  return {
    form: {
      entityCode: "studio.visits",
      title: labels("Визиты"),
      status: "active",
      activeVersion: 1,
      versions: 1,
      records: 3,
      quarantined: false,
      broken: null,
      revision: 1,
      createdAt: "2026-10-17T08:00:00Z",
      modifiedAt: "2026-10-17T08:00:00Z",
    },
    declaration: {
      formatVersion: 3,
      kind: "simple-form",
      module: { code: "studio", title: labels("Созданные формы") },
      entity: { name: "visits", title: labels("Визиты"), scope: "org_unit" },
      fields: [
        { id: "client", type: "ref", labels: labels("Клиент"), required: true, listed: true, target: { kind: "form", code: "studio.clients" } },
        { id: "kind", type: "ref", labels: labels("Тип"), required: false, listed: true, target: { kind: "list", code: "kinds" } },
        {
          id: "result",
          type: "select",
          labels: labels("Результат"),
          required: true,
          listed: true,
          options: [
            { code: "done", labels: labels("Выполнен") },
            { code: "later", labels: labels("Перенесён") },
          ],
        },
        { id: "owner", type: "user", labels: labels("Ответственный"), required: false, listed: true },
      ],
    },
    checksum: "a".repeat(64),
    versions: [{ version: 1, checksum: "a".repeat(64), createdAt: "2026-10-17T08:00:00Z", createdBy: 1 }],
  };
}

async function audit(page: Page, include?: string) {
  const builder = new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]);
  if (include) builder.include(include);
  const result = await builder.analyze();
  expect(
    result.violations.map((violation) => `${violation.id}: ${violation.nodes.map((node) => node.target)}`),
  ).toEqual([]);
}

async function api(page: Page) {
  const sent: { method: string; url: string; ifMatch?: string; body?: unknown }[] = [];
  await page.route("**/api/v1/modules/active", async (route) => {
    const modules = await (await route.fetch()).json();
    await route.fulfill({
      json: [...modules, { code: "studio", name: "Созданные формы", status: "ACTIVE", isActive: true, isSystem: false }],
    });
  });
  await page.route(
    (url) => url.pathname === "/api/v1/studio/lists",
    (route) => route.fulfill({ json: { items: [list()], hasMore: false } }),
  );
  await page.route("**/api/v1/studio/lists/kinds", (route) =>
    route.fulfill({ json: { list: list(), items: [item("repair", "Ремонт", true), item("old", "Старый", false)] } }),
  );
  await page.route("**/api/v1/studio/lists/kinds/items/**", (route) => {
    const request = route.request();
    sent.push({ method: request.method(), url: new URL(request.url()).pathname, ifMatch: request.headers()["if-match"], body: request.postDataJSON() });
    return route.fulfill({ json: item("old", "Старый", true, 3) });
  });
  await page.route(
    (url) => url.pathname === "/api/v1/studio/forms",
    (route) => route.fulfill({ json: { items: [{ ...visits().form, entityCode: "studio.clients", title: labels("Клиенты") }], hasMore: false } }),
  );
  await page.route("**/api/v1/studio/forms/studio.visits", (route) => route.fulfill({ json: visits() }));
  return sent;
}

for (const theme of ["light", "dark"] as const) {
  test.describe(`studio reference lists ${theme}`, () => {
    let errors: string[];
    test.beforeEach(async ({ page }) => {
      errors = [];
      page.on("pageerror", (error) => errors.push(error.message));
      await page.addInitScript((value) => localStorage.setItem("smc_theme", value), theme);
    });
    test.afterEach(() => expect(errors).toEqual([]));

    test("opens a list and switches a value back on by keyboard from its revision", async ({ page }) => {
      const sent = await api(page);
      await page.setViewportSize({ width: 360, height: 740 });
      await page.goto("/settings/form-studio/lists");
      await expect(page.getByTestId("studio-lists")).toContainText("Типы заявок");
      await audit(page);
      const open = page.getByTestId("studio-list-open-kinds");
      await open.focus();
      await page.keyboard.press("Enter");
      await expect(page.getByTestId("studio-list-open")).toContainText("Отключено");
      await audit(page);
      const edit = page.getByTestId("studio-list-edit-old");
      await edit.focus();
      await page.keyboard.press("Enter");
      const dialog = page.getByRole("dialog", { name: "Изменить значение" });
      await expect(dialog).toBeVisible();
      await audit(page, '[role="dialog"]');
      await dialog.getByTestId("studio-entry-active").click();
      await dialog.getByTestId("form-submit").click();
      await expect(dialog).toBeHidden();
      expect(sent).toEqual([
        {
          method: "PUT",
          url: "/api/v1/studio/lists/kinds/items/old",
          ifMatch: '"2"',
          body: { name: labels("Старый"), sortOrder: 20, active: true },
        },
      ]);
      await page.setViewportSize({ width: 1440, height: 1000 });
      await audit(page);
      await page.screenshot({ path: `../.local/studio-lists-${theme}-desktop.png` });
    });

    test("shows the choices, targets and scope of a format 3 form locked in its new version", async ({ page }) => {
      await api(page);
      await page.setViewportSize({ width: 360, height: 740 });
      await page.goto("/settings/form-studio/forms/studio.visits/version");
      await expect(page.getByTestId("form-version-scope")).toContainText("Подразделение и подчинённые");
      await expect(page.getByTestId("field-options-2")).toBeVisible();
      await expect(page.getByTestId("field-target-0").getByRole("combobox")).toBeDisabled();
      await audit(page);
      await page.getByTestId("field-add-option-2").click();
      await expect(page.getByTestId("field-option-2-2")).toBeVisible();
      await page.setViewportSize({ width: 1440, height: 1000 });
      await audit(page);
    });
  });
}
