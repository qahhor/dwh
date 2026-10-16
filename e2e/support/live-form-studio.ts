import { expect, type Page } from "@playwright/test";

export async function chooseLiveFormDelivery(page: Page) {
  const delivery = page.getByTestId("new-form-delivery").getByRole("combobox");
  await delivery.focus();
  await page.keyboard.press("Enter");
  const search = page.locator('input[role="combobox"][aria-autocomplete="list"]');
  await expect(search).toBeFocused();
  await search.press("ArrowDown");
  await search.press("Enter");
  await expect(delivery).toContainText("Активировать в этой установке");
  await expect(page.getByTestId("new-module-code")).toHaveValue("studio");
}

export async function mockSimpleFormPreparation(page: Page) {
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
  await page.route("**/api/v1/studio/activation", (route) =>
    route.fulfill({
      json: {
        enabled: true,
        namespace: "studio",
        maxForms: 100,
      },
    }),
  );
}
