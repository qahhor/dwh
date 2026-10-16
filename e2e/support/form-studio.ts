import { AxeBuilder } from "@axe-core/playwright";
import { expect, type Page } from "@playwright/test";

export const declaration = {
  formatVersion: 1,
  kind: "form-layout",
  entityCode: "example.products",
  sections: [{ id: "main", fields: ["code", "name", "unit", "price"].map((fieldId) => ({ fieldId })) }],
};
export const meta = {
  code: "example.products",
  titleKey: "nav.example_products",
  listCode: "example.products",
  fields: [
    {
      key: "code",
      labelKey: "example.products.col.code",
      type: "text",
      required: true,
      readonlyOnUpdate: true,
      maxLength: 64,
    },
    { key: "name", labelKey: "example.products.col.name", type: "text", required: true, maxLength: 255 },
    {
      key: "unit",
      labelKey: "example.products.col.unit",
      type: "select",
      required: true,
      options: ["pcs", "kg", "l"],
      optionLabelPrefix: "example.products.unit.",
    },
    {
      key: "price",
      labelKey: "example.products.col.price",
      type: "money",
      required: false,
      currencies: ["UZS", "USD"],
    },
    {
      key: "modifiedAt",
      labelKey: "example.products.col.modified_at",
      type: "datetime",
      required: false,
      readonly: true,
    },
  ],
  layout: [{ key: "main", labelKey: "entity.section.main", fields: ["code", "name", "unit", "price"] }],
  actions: ["create", "update", "delete"],
  capabilities: ["history", "export"],
};

export async function audit(page: Page) {
  // Audit the settled state, not interpolated foreground/background colors during a finite CSS transition.
  await page.getByTestId("form-studio").evaluate(async (element) => {
    await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
    const animations = element
      .getAnimations({ subtree: true })
      .filter((animation) => animation.effect?.getTiming().iterations !== Infinity);
    await Promise.allSettled(animations.map((animation) => animation.finished));
  });
  const result = await new AxeBuilder({ page })
    .include('[data-testid="form-studio"]')
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .analyze();
  expect(
    result.violations.map(({ id, impact, nodes }) => ({
      id,
      impact,
      nodes: nodes.map((node) => ({ target: node.target, summary: node.failureSummary })),
    })),
  ).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
}
