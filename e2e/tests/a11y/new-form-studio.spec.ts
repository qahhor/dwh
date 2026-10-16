import { expect, test } from "@playwright/test";
import { readFile } from "node:fs/promises";
import { auditSimpleForm, fillSimpleForm } from "../../support/new-form-studio.js";

for (const theme of ["light", "dark"] as const) {
  test.describe(`new form studio ${theme}`, () => {
    let errors: string[];
    test.beforeEach(async ({ page }) => {
      errors = [];
      page.on("pageerror", (error) => errors.push(error.message));
      await page.setViewportSize({ width: 360, height: 740 });
      await page.addInitScript((value) => localStorage.setItem("smc_theme", value), theme);
      await page.route("**/api/v1/modules/active", (route) => route.fulfill({ json: [] }));
      await page.route("**/api/v1/form-studio/new-forms/prepare", (route) =>
        route.fulfill({
          json: {
            formatVersion: 1,
            kind: "simple-form-package",
            declaration: route.request().postDataJSON(),
            checksum: "a".repeat(64),
          },
        }),
      );
    });
    test.beforeEach(async ({ page }) => {
      await page.route("**/api/v1/form-studio/new-forms/check-draft", (route) =>
        route.fulfill({ json: route.request().postDataJSON() }),
      );
    });
    test.afterEach(() => expect(errors).toEqual([]));
    test("creates, orders and downloads a new form independently from example products", async ({ page }) => {
      const requests: string[] = [];
      page.on("request", (request) => {
        if (request.url().includes("/api/v1/form-studio/") || request.url().includes("/api/v1/entities/"))
          requests.push(`${request.method()} ${new URL(request.url()).pathname}`);
      });
      await page.route("**/api/v1/form-studio/prototype", (route) =>
        route.fulfill({ status: 404, json: { detail: "Example module disabled" } }),
      );
      await page.goto("/settings/form-studio/new");
      await expect(page.getByTestId("new-form-studio")).toBeVisible();
      await fillSimpleForm(page);
      const add = page.getByTestId("new-form-add");
      await add.focus();
      await page.keyboard.press("Enter");
      const first = page.getByTestId("new-field-0");
      await expect(first.getByRole("textbox", { name: "Идентификатор поля", exact: true })).toBeFocused();
      await first.getByRole("textbox", { name: "Идентификатор поля", exact: true }).fill("aisle");
      await first.getByRole("textbox", { name: "Русский", exact: true }).fill("Ряд");
      await first.getByRole("textbox", { name: "Узбекский", exact: true }).fill("Qator");
      await first.getByRole("textbox", { name: "Английский", exact: true }).fill("Aisle");
      await add.click();
      const second = page.getByTestId("new-field-1");
      await second.getByRole("textbox", { name: "Русский", exact: true }).fill("Зона");
      await second.getByRole("textbox", { name: "Узбекский", exact: true }).fill("Zona");
      await second.getByRole("textbox", { name: "Английский", exact: true }).fill("Zone");
      await second.getByRole("button", { name: "Поднять поле 2", exact: true }).focus();
      await page.keyboard.press("Enter");
      await expect(first.getByRole("textbox", { name: "Идентификатор поля", exact: true })).toHaveValue("field1");
      await expect(first.getByRole("textbox", { name: "Идентификатор поля", exact: true })).toBeFocused();
      await first.getByRole("checkbox", { name: "Обязательное", exact: true }).focus();
      await page.keyboard.press("Space");
      await page.getByTestId("new-form-prepare").click();
      await expect(page.locator('smt-entity-form [data-field="field1"]')).toContainText("Зона");
      await expect(page.getByTestId("new-form-download")).toBeEnabled();
      await auditSimpleForm(page);
      await page.screenshot({ path: `../.local/studio-04-${theme}-mobile.png` });
      await page.locator("smt-entity-form").scrollIntoViewIfNeeded();
      await page.screenshot({ path: `../.local/studio-04-${theme}-mobile-preview.png` });
      await page.setViewportSize({ width: 1440, height: 1000 });
      await auditSimpleForm(page);
      await page.getByTestId("new-module-code").scrollIntoViewIfNeeded();
      await page.screenshot({ path: `../.local/studio-04-${theme}-desktop.png` });
      await page.getByRole("link", { name: "К Студии форм", exact: true }).click();
      await expect(page.getByRole("alertdialog")).toBeVisible();
      await page.getByRole("button", { name: "Продолжить редактирование", exact: true }).click();
      const nextDownload = page.waitForEvent("download");
      await page.getByTestId("new-form-download").click();
      const download = await nextDownload;
      const saved = JSON.parse(await readFile((await download.path())!, "utf8"));
      expect(saved.kind).toBe("simple-form-package");
      expect(saved.declaration.fields.map((field: { id: string }) => field.id)).toEqual(["field1", "aisle"]);
      expect(saved.declaration.fields[0].required).toBe(true);
      expect(saved.declaration.module.code).toBe("inventory");
      await first.getByRole("textbox", { name: "Русский", exact: true }).fill("Новая зона");
      await expect(page.getByTestId("new-form-download")).toBeDisabled();
      expect(requests).toEqual(["GET /api/v1/entities/menu", "POST /api/v1/form-studio/new-forms/prepare"]);
    });
    test("retains invalid imported input, exposes errors and recovers after retry", async ({ page }) => {
      await page.goto("/settings/form-studio/new");
      await fillSimpleForm(page);
      await page.route("**/api/v1/form-studio/new-forms/check-draft", (route) =>
        route.fulfill({
          status: 422,
          json: {
            detail: "Unsupported declaration",
            invalid_params: [{ name: "module.code", reason: "Invalid identifier" }],
          },
        }),
      );
      await page
        .getByTestId("new-form-import")
        .setInputFiles({ name: "bad.json", mimeType: "application/json", buffer: Buffer.from('{"kind":"sql"}') });
      await page.getByRole("button", { name: "Не сохранять", exact: true }).click();
      await expect(page.getByTestId("new-form-error")).toContainText("Unsupported declaration");
      await expect(page.getByTestId("new-module-code")).toHaveValue("inventory");
      await expect(page.getByTestId("new-form-download")).toBeDisabled();
      await auditSimpleForm(page);
      await page.unroute("**/api/v1/form-studio/new-forms/check-draft");
      await page.getByTestId("new-form-prepare").click();
      await expect(page.getByTestId("new-form-error")).toHaveCount(0);
      await expect(page.getByTestId("new-form-download")).toBeEnabled();
    });
    if (theme === "light") {
      test("types and edits 80 Unicode code points through the native input and saves their exact value", async ({
        page,
      }) => {
        await page.goto("/settings/form-studio/new");
        const label = page
          .getByRole("group", { name: "Новый модуль", exact: true })
          .getByRole("textbox", { name: "Русский", exact: true });
        await label.focus();
        const original = "😀".repeat(80);
        await page.keyboard.insertText(original);
        await expect(label).toHaveValue(original);
        await page.keyboard.press("End");
        await page.keyboard.press("Backspace");
        await page.keyboard.insertText("🚀");
        const edited = "😀".repeat(79) + "🚀";
        await expect(label).toHaveValue(edited);
        await page.keyboard.insertText("!");
        await expect(label).toHaveValue(edited);
        const nextDownload = page.waitForEvent("download");
        await page.getByRole("button", { name: "Сохранить черновик JSON", exact: true }).click();
        const download = await nextDownload;
        const saved = JSON.parse(await readFile((await download.path())!, "utf8"));
        expect(saved.module.title.ru).toBe(edited);
        expect([...saved.module.title.ru]).toHaveLength(80);
      });
    }
    test("round-trips an unfinished local draft without preparing or previewing it", async ({ page }) => {
      await page.goto("/settings/form-studio/new");
      await page.getByTestId("new-module-code").fill("unfinished");
      await page.getByTestId("new-form-add").click();
      const nextDownload = page.waitForEvent("download");
      await page.getByRole("button", { name: "Сохранить черновик JSON", exact: true }).click();
      const download = await nextDownload;
      const raw = await readFile((await download.path())!, "utf8");
      expect(JSON.parse(raw).entity.name).toBe("");
      await page.getByTestId("new-module-code").fill("changed");
      await page
        .getByTestId("new-form-import")
        .setInputFiles({ name: "draft.json", mimeType: "application/json", buffer: Buffer.from(raw) });
      await page.getByRole("button", { name: "Не сохранять", exact: true }).click();
      await expect(page.getByTestId("new-module-code")).toHaveValue("unfinished");
      await expect(page.getByTestId("new-entity-name")).toHaveValue("");
      await expect(page.getByTestId("new-field-0")).toBeVisible();
      await expect(page.getByTestId("new-form-download")).toBeDisabled();
      await expect(page.locator("smt-entity-form")).toHaveCount(0);
    });
  });
}
