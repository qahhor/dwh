import { expect, test } from "@playwright/test";
import { AxeBuilder } from "@axe-core/playwright";
import { auditSimpleForm, fillSimpleForm } from "../../support/new-form-studio.js";
import { chooseLiveFormDelivery, mockSimpleFormPreparation } from "../../support/live-form-studio.js";

for (const theme of ["light", "dark"] as const) {
  test.describe(`live form studio ${theme}`, () => {
    let errors: string[];
    test.beforeEach(async ({ page }) => {
      errors = [];
      page.on("pageerror", (error) => errors.push(error.message));
      await page.setViewportSize({ width: 360, height: 740 });
      await page.addInitScript((value) => localStorage.setItem("smc_theme", value), theme);
      await mockSimpleFormPreparation(page);
    });
    test.afterEach(() => expect(errors).toEqual([]));
    test("activates only after confirmation, then presents the form link with keyboard and mobile access", async ({
      page,
    }) => {
      const packages: { declaration: { module: { code: string }; entity: { name: string } }; checksum: string }[] = [];
      await page.route("**/api/v1/studio/forms", (route) => {
        expect(route.request().headers()["idempotency-key"]).toBeUndefined();
        packages.push(route.request().postDataJSON());
        return route.fulfill({ status: 201, json: { entityCode: "studio.locations", checksum: "b".repeat(64) } });
      });
      await page.goto("/settings/form-studio/new");
      await expect(page.getByTestId("new-form-studio")).toBeVisible();
      await fillSimpleForm(page);
      await chooseLiveFormDelivery(page);
      await page.getByTestId("new-form-prepare").click();
      const activate = page.getByTestId("new-form-activate");
      await expect(activate).toBeEnabled();
      await activate.focus();
      await page.keyboard.press("Enter");
      const dialog = page.getByRole("alertdialog");
      await expect(dialog).toContainText("studio.locations");
      await expect(dialog).toContainText("поля и типы будут зафиксированы");
      expect(packages).toHaveLength(0);
      const dialogAudit = await new AxeBuilder({ page })
        .include('[role="alertdialog"]')
        .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
        .analyze();
      expect(dialogAudit.violations).toEqual([]);
      await page.keyboard.press("Escape");
      await expect(dialog).not.toBeVisible();
      await activate.focus();
      await page.keyboard.press("Enter");
      await dialog.getByRole("button", { name: "Активировать форму", exact: true }).focus();
      await page.keyboard.press("Enter");
      await expect(page.getByTestId("new-form-activated")).toContainText("studio.locations");
      await expect(page.getByRole("link", { name: "Открыть форму", exact: true })).toHaveAttribute(
        "href",
        "/e/studio.locations",
      );
      await expect(page).toHaveURL(/\/settings\/form-studio\/new$/);
      await expect(activate).toBeDisabled();
      expect(packages).toHaveLength(1);
      expect(packages[0].declaration.module.code).toBe("studio");
      expect(packages[0].declaration.entity.name).toBe("locations");
      await auditSimpleForm(page);
      await page.getByTestId("new-form-activated").scrollIntoViewIfNeeded();
      await page.screenshot({ path: `../.local/studio-05-${theme}-mobile.png` });
      await page.setViewportSize({ width: 1440, height: 1000 });
      await auditSimpleForm(page);
      await page.getByTestId("new-form-delivery").scrollIntoViewIfNeeded();
      await page.screenshot({ path: `../.local/studio-05-${theme}-desktop.png` });
    });
    test("preserves a refused package and retries activation without changing the declaration", async ({ page }) => {
      const sent: unknown[] = [];
      await page.route("**/api/v1/studio/forms", (route) => {
        sent.push(route.request().postDataJSON());
        return route.fulfill(
          sent.length === 1
            ? { status: 409, json: { detail: "Activation conflict" } }
            : { status: 201, json: { entityCode: "studio.locations", checksum: "b".repeat(64) } },
        );
      });
      await page.goto("/settings/form-studio/new");
      await fillSimpleForm(page);
      await chooseLiveFormDelivery(page);
      await page.getByTestId("new-form-prepare").click();
      await page.getByTestId("new-form-activate").click();
      await page.getByRole("alertdialog").getByRole("button", { name: "Активировать форму", exact: true }).click();
      await expect(page.getByTestId("new-form-activation-error")).toContainText("Activation conflict");
      await expect(page.getByTestId("new-entity-name")).toHaveValue("locations");
      await expect(page.getByTestId("new-form-download")).toBeEnabled();
      await auditSimpleForm(page);
      await page.getByTestId("new-form-activate").click();
      await page.getByRole("alertdialog").getByRole("button", { name: "Активировать форму", exact: true }).click();
      await expect(page.getByTestId("new-form-activated")).toBeVisible();
      expect(sent).toHaveLength(2);
      expect(sent[0]).toEqual(sent[1]);
      await expect(page.getByTestId("new-form-activation-error")).not.toBeVisible();
    });
  });
}

test("author without publish permission retains CLI delivery and sees an explicit activation explanation", async ({
  page,
}) => {
  const requests: string[] = [];
  page.on("request", (request) => {
    if (request.url().includes("/api/v1/studio/")) requests.push(request.url());
  });
  await page.route("**/api/v1/auth/me", async (route) => {
    const body = await (await route.fetch()).json();
    await route.fulfill({ json: { ...body, permissions: ["md.profile.view", "md.settings.update"] } });
  });
  await page.goto("/settings/form-studio/new");
  await fillSimpleForm(page);
  await chooseLiveFormDelivery(page);
  await expect(page.getByTestId("new-form-activation-denied")).toBeVisible();
  await expect(page.getByTestId("new-form-activate")).not.toBeVisible();
  expect(requests).toEqual([]);
  await page.getByTestId("new-form-delivery").getByRole("combobox").click();
  await page.getByRole("option", { name: "Пакет для разработчика (CLI)", exact: true }).click();
  await expect(page.getByTestId("new-module-code")).toHaveValue("inventory");
  await expect(page.getByTestId("new-entity-name")).toHaveValue("locations");
});

test("disabled activation can be checked again without losing draft fields", async ({ page }) => {
  let checks = 0;
  await mockSimpleFormPreparation(page);
  await page.route("**/api/v1/studio/activation", (route) =>
    route.fulfill({
      json: {
        enabled: ++checks > 1,
        namespace: "studio",
        maxForms: 100,
      },
    }),
  );
  await page.goto("/settings/form-studio/new");
  await fillSimpleForm(page);
  await chooseLiveFormDelivery(page);
  await expect(page.getByTestId("new-form-studio")).toContainText("Активация форм выключена");
  await page.getByTestId("new-form-prepare").click();
  await expect(page.getByTestId("new-form-activate")).toBeDisabled();
  await page.getByRole("button", { name: "Проверить доступность снова", exact: true }).click();
  await expect(page.getByTestId("new-form-activate")).toBeEnabled();
  await expect(page.getByTestId("new-entity-name")).toHaveValue("locations");
});
