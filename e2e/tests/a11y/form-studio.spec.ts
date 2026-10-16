import { expect, test } from "@playwright/test";
import { readFile } from "node:fs/promises";
import { audit, declaration, meta } from "../../support/form-studio.js";

for (const theme of ["light", "dark"] as const) {
  test.describe(`form studio in ${theme}`, () => {
    test.beforeEach(async ({ page }) => {
      await page.setViewportSize({ width: 360, height: 800 });
      await page.addInitScript((value) => localStorage.setItem("smc_theme", value), theme);
      await page.route("**/api/v1/form-studio/drafts/example.products", (route) =>
        route.fulfill({ json: { draft: null, meta } }),
      );
      await page.route("**/api/v1/form-studio/drafts/example.products/versions?*", (route) =>
        route.fulfill({ json: { items: [], nextCursor: null, hasMore: false, totalReturned: 0 } }),
      );
      await page.route("**/api/v1/form-studio/prototype", (route) => route.fulfill({ json: { declaration, meta } }));
      await page.route("**/api/v1/form-studio/preview", (route) =>
        route.fulfill({
          json: { declaration: route.request().postDataJSON(), meta },
        }),
      );
    });

    test("edits, reorders and round-trips a declaration without saving a product", async ({ page }) => {
      const writes: string[] = [];
      page.on("request", (request) => {
        if (request.url().includes("/entities/") && ["POST", "PUT", "PATCH", "DELETE"].includes(request.method())) {
          writes.push(request.url());
        }
      });
      await page.goto("/settings/form-studio");
      const editor = page.getByTestId("form-studio-editor");
      const preview = page.getByTestId("form-studio-preview");
      await expect(editor).toBeVisible();
      await preview.locator('[data-field="name"] input').fill("Sample product");
      const name = page.getByTestId("studio-field-name");
      await name.getByRole("textbox", { name: "Русский", exact: true }).fill("Наименование");
      await name.getByRole("textbox", { name: "Узбекский", exact: true }).fill("Mahsulot nomi");
      await name.getByRole("textbox", { name: "Английский", exact: true }).fill("Product name");
      await expect(page.getByRole("button", { name: "Сохранить JSON", exact: true })).toBeDisabled();
      const down = page.getByTestId("studio-field-code").getByRole("button", { name: /Опустить/ });
      await down.focus();
      await page.keyboard.press("Enter");
      await expect(down).toBeFocused();
      await page.getByRole("button", { name: "Проверить и показать", exact: true }).click();
      await expect(preview.locator('[data-field="name"] input')).toHaveValue("Sample product");
      await expect(preview.getByRole("textbox", { name: "Наименование", exact: true })).toBeVisible();
      await expect(preview.locator("[data-field]").first()).toHaveAttribute("data-field", "name");
      await audit(page);
      await page.screenshot({ path: `../.local/studio-01-${theme}-mobile.png`, fullPage: true });
      await page.setViewportSize({ width: 1440, height: 1000 });
      await audit(page);
      await page.screenshot({ path: `../.local/studio-01-${theme}-desktop.png`, fullPage: true });
      await page.getByRole("link", { name: "К настройкам", exact: true }).click();
      await expect(page.getByRole("alertdialog")).toBeVisible();
      await page.getByRole("button", { name: "Продолжить редактирование", exact: true }).click();
      await expect(page).toHaveURL(/\/settings\/form-studio$/);
      const downloadEvent = page.waitForEvent("download");
      await page.getByRole("button", { name: "Сохранить JSON", exact: true }).click();
      const download = await downloadEvent;
      const saved = JSON.parse(await readFile((await download.path())!, "utf8"));
      expect(
        saved.sections.flatMap((section: { fields: { fieldId: string }[] }) =>
          section.fields.map((field) => field.fieldId),
        ),
      ).toEqual(["name", "code", "unit", "price"]);
      expect(saved.entityCode).toBe("example.products");
      expect(saved.sections[0].fields[0].labels.ru).toBe("Наименование");
      await page.getByTestId("studio-import").setInputFiles({
        name: "layout.json",
        mimeType: "application/json",
        buffer: Buffer.from(JSON.stringify(saved)),
      });
      await page.getByRole("button", { name: "Не сохранять", exact: true }).click();
      await expect(preview.getByRole("textbox", { name: "Наименование", exact: true })).toBeVisible();
      expect(writes).toEqual([]);
    });

    test("failed import preserves edits and allows validation again", async ({ page }) => {
      await page.goto("/settings/form-studio");
      await expect(page.getByTestId("studio-field-name")).toBeVisible();
      await page.getByRole("button", { name: "Добавить секцию", exact: true }).click();
      await expect(page.getByTestId("form-studio-editor").locator('[data-testid^="studio-section-"]')).toHaveCount(2);
      const labels = page.getByTestId("studio-section-section_1").locator(":scope > app-form-studio-labels");
      await labels.getByRole("textbox", { name: "Русский", exact: true }).fill("Цена");
      await labels.getByRole("textbox", { name: "Узбекский", exact: true }).fill("Narx");
      await labels.getByRole("textbox", { name: "Английский", exact: true }).fill("Price");
      await page.route("**/api/v1/form-studio/preview", (route) =>
        route.fulfill({
          status: 422,
          json: {
            title: "Validation failed",
            code: "validation_failed",
            detail: "Unsupported declaration",
          },
        }),
      );
      await page
        .getByTestId("studio-import")
        .setInputFiles({ name: "bad.json", mimeType: "application/json", buffer: Buffer.from('{"kind":"sql"}') });
      await page.getByRole("button", { name: "Не сохранять", exact: true }).click();
      await expect(page.getByTestId("form-studio").getByRole("alert")).toContainText("Unsupported declaration");
      await expect(page.getByTestId("form-studio-editor").locator('[data-testid^="studio-section-"]')).toHaveCount(2);
      await expect(page.getByRole("button", { name: "Сохранить JSON", exact: true })).toBeDisabled();
      await audit(page);
      await page.unroute("**/api/v1/form-studio/preview");
      await page.route("**/api/v1/form-studio/preview", (route) =>
        route.fulfill({ json: { declaration: route.request().postDataJSON(), meta } }),
      );
      await page.getByRole("button", { name: "Проверить и показать", exact: true }).click();
      await expect(page.getByRole("button", { name: "Сохранить JSON", exact: true })).toBeEnabled();
      await audit(page);
    });

    test("unavailable prototype exposes retry without an empty editor", async ({ page }) => {
      await page.route("**/api/v1/form-studio/prototype", (route) =>
        route.fulfill({
          status: 404,
          json: {
            title: "Not found",
            code: "not_found",
            detail: "Prototype unavailable",
          },
        }),
      );
      await page.goto("/settings/form-studio");
      await expect(page.getByTestId("form-studio").getByRole("alert")).toContainText("Prototype unavailable");
      await expect(page.getByTestId("form-studio-editor")).toHaveCount(0);
      await audit(page);
      await page.unroute("**/api/v1/form-studio/prototype");
      await page.route("**/api/v1/form-studio/prototype", (route) => route.fulfill({ json: { declaration, meta } }));
      const retry = page.getByRole("button", { name: "Повторить", exact: true });
      await retry.focus();
      await page.keyboard.press("Enter");
      await expect(page.getByTestId("form-studio-editor")).toBeVisible();
      await audit(page);
    });
  });
}
