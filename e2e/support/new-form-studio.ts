import { AxeBuilder } from "@axe-core/playwright";
import { expect, type Page } from "@playwright/test";

export async function fillSimpleForm(page: Page) {
  const module = page.getByRole("group", { name: "Новый модуль", exact: true });
  await module.getByTestId("new-module-code").fill("inventory");
  await module.getByRole("textbox", { name: "Русский", exact: true }).fill("Склад");
  await module.getByRole("textbox", { name: "Узбекский", exact: true }).fill("Ombor");
  await module.getByRole("textbox", { name: "Английский", exact: true }).fill("Inventory");
  const entity = page.getByRole("group", { name: "Новая форма", exact: true });
  await entity.getByTestId("new-entity-name").fill("locations");
  await entity.getByRole("textbox", { name: "Русский", exact: true }).fill("Места хранения");
  await entity.getByRole("textbox", { name: "Узбекский", exact: true }).fill("Joylar");
  await entity.getByRole("textbox", { name: "Английский", exact: true }).fill("Locations");
}
export async function auditSimpleForm(page: Page) {
  const region = page.getByTestId("new-form-studio");
  await region.evaluate(async (element) => {
    await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
    await Promise.allSettled(
      element
        .getAnimations({ subtree: true })
        .filter((animation) => animation.effect?.getTiming().iterations !== Infinity)
        .map((animation) => animation.finished),
    );
  });
  const result = await new AxeBuilder({ page })
    .include('[data-testid="new-form-studio"]')
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .analyze();
  expect(
    result.violations.map(({ id, nodes }) => ({
      id,
      nodes: nodes.map((node) => ({ target: node.target, summary: node.failureSummary })),
    })),
  ).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
}
