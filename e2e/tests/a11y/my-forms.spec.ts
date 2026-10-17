import { expect, test, type Page } from "@playwright/test";
import { AxeBuilder } from "@axe-core/playwright";

const labels = (text: string) => ({ ru: text, uz: text, en: text });

function summary(code: string, change: Record<string, unknown> = {}) {
  return {
    entityCode: code,
    title: labels("Визиты"),
    status: "active",
    activeVersion: 2,
    versions: 2,
    records: 12,
    quarantined: false,
    broken: null,
    revision: 4,
    createdAt: "2026-10-17T08:00:00Z",
    modifiedAt: "2026-10-17T09:30:00Z",
    ...change,
  };
}

function detail() {
  return {
    form: summary("studio.visits"),
    declaration: {
      formatVersion: 2,
      kind: "simple-form",
      module: { code: "studio", title: labels("Созданные формы") },
      entity: { name: "visits", title: labels("Визиты") },
      fields: [
        { id: "client", type: "text", labels: labels("Клиент"), required: true, listed: true, maxLength: 100 },
        { id: "note", type: "textarea", labels: labels("Комментарий"), required: false, listed: false, maxLength: 500 },
      ],
    },
    checksum: "a".repeat(64),
    versions: [
      { version: 1, checksum: "c".repeat(64), createdAt: "2026-10-16T08:00:00Z", createdBy: 1 },
      { version: 2, checksum: "a".repeat(64), createdAt: "2026-10-17T08:00:00Z", createdBy: 1 },
    ],
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
  const sent: { url: string; ifMatch?: string }[] = [];
  await page.route("**/api/v1/modules/active", async (route) => {
    const modules = await (await route.fetch()).json();
    await route.fulfill({
      json: [
        ...modules,
        { code: "studio", name: "Созданные формы", status: "ACTIVE", isActive: true, isSystem: false },
      ],
    });
  });
  await page.route(
    (url) => url.pathname === "/api/v1/studio/forms",
    (route) =>
      route.fulfill({
        json: {
          items: [
            summary("studio.visits"),
            summary("studio.requests", { title: labels("Заявки"), status: "archived", revision: 2 }),
            summary("studio.held", { title: labels("Проверка"), quarantined: true, broken: "declaration" }),
          ],
          hasMore: false,
        },
      }),
  );
  await page.route("**/api/v1/studio/forms/studio.visits", (route) => route.fulfill({ json: detail() }));
  await page.route("**/api/v1/studio/forms/studio.visits/**", (route) => {
    const request = route.request();
    sent.push({ url: new URL(request.url()).pathname, ifMatch: request.headers()["if-match"] });
    expect(request.headers()["idempotency-key"]).toBeUndefined();
    if (request.url().endsWith("/archive")) {
      return route.fulfill({ json: summary("studio.visits", { status: "archived", revision: 5 }) });
    }
    return route.fulfill({ status: 201, json: { ...detail(), form: summary("studio.visits", { activeVersion: 3 }) } });
  });
  await page.route("**/api/v1/form-studio/new-forms/prepare", (route) =>
    route.fulfill({
      json: {
        formatVersion: 1,
        kind: "simple-form-package",
        declaration: route.request().postDataJSON(),
        checksum: "b".repeat(64),
      },
    }),
  );
  return sent;
}

for (const theme of ["light", "dark"] as const) {
  test.describe(`my forms ${theme}`, () => {
    let errors: string[];
    test.beforeEach(async ({ page }) => {
      errors = [];
      page.on("pageerror", (error) => errors.push(error.message));
      await page.addInitScript((value) => localStorage.setItem("smc_theme", value), theme);
    });
    test.afterEach(() => expect(errors).toEqual([]));

    test("lists the forms with their states and archives one by keyboard after the confirmation", async ({ page }) => {
      const sent = await api(page);
      await page.setViewportSize({ width: 360, height: 740 });
      await page.goto("/settings/form-studio/forms");
      await expect(page.getByTestId("my-forms")).toBeVisible();
      await expect(page.getByTestId("my-forms-status-studio.requests")).toContainText("В архиве");
      await expect(page.getByTestId("my-forms-status-studio.held")).toContainText("Выведена из работы");
      await audit(page);
      await page.setViewportSize({ width: 1440, height: 1000 });
      await audit(page);
      const archive = page.getByTestId("my-forms-archive-studio.visits");
      await archive.focus();
      await page.keyboard.press("Enter");
      const dialog = page.getByRole("alertdialog");
      await expect(dialog).toContainText("Визиты");
      await audit(page, '[role="alertdialog"]');
      await page.keyboard.press("Escape");
      await expect(dialog).not.toBeVisible();
      expect(sent).toEqual([]);
      await archive.focus();
      await page.keyboard.press("Enter");
      await dialog.getByRole("button", { name: "В архив", exact: true }).click();
      await expect(page.getByTestId("my-forms-status-studio.visits")).toContainText("В архиве");
      expect(sent).toEqual([{ url: "/api/v1/studio/forms/studio.visits/archive", ifMatch: '"4"' }]);
      await page.screenshot({ path: `../.local/studio-forms-${theme}-desktop.png` });
    });

    test("edits a new version with locked stored fields and activates it after the confirmation", async ({ page }) => {
      const sent = await api(page);
      await page.setViewportSize({ width: 360, height: 740 });
      await page.goto("/settings/form-studio/forms/studio.visits/version");
      await expect(page.getByTestId("form-version")).toBeVisible();
      await expect(page.getByTestId("new-field-0").getByRole("textbox").first()).toBeDisabled();
      await audit(page);
      await page.getByTestId("version-hide-note").focus();
      await page.keyboard.press("Enter");
      await expect(page.getByTestId("new-field-1")).toContainText("скрыто");
      await page.getByTestId("form-version-prepare").click();
      await expect(page.getByTestId("form-version-activate")).toBeEnabled();
      await page.getByTestId("form-version-activate").click();
      const dialog = page.getByRole("alertdialog");
      await expect(dialog).toContainText("studio.visits");
      await audit(page, '[role="alertdialog"]');
      await dialog.getByRole("button", { name: "Активировать версию", exact: true }).click();
      await expect(page.getByTestId("form-version-activated")).toContainText("3");
      expect(sent).toEqual([{ url: "/api/v1/studio/forms/studio.visits/versions", ifMatch: '"4"' }]);
      await page.setViewportSize({ width: 1440, height: 1000 });
      await audit(page);
      await page.screenshot({ path: `../.local/studio-version-${theme}-desktop.png` });
    });
  });
}
