import type { Locator } from '@playwright/test';

/**
 * Picks an option of an smt-select by its value, whatever the language. The
 * trigger is the element a label names (its `role="combobox"` button, found by
 * `getByLabel` or its id); the options open in a CDK overlay outside the
 * control, each carrying its value in `data-value`.
 */
export async function chooseOption(trigger: Locator, value: string): Promise<void> {
  await trigger.click();
  const listbox = trigger.page().locator('.cdk-overlay-container [role="listbox"]').last();
  await listbox.locator(`[role="option"][data-value="${value}"]`).click();
}
