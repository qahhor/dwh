import { expect, type Locator } from '@playwright/test';

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

/**
 * Picks a period of an smt-date-range-picker, whatever the language: opens the
 * trigger, moves the calendar to each day (every day cell carries its ISO date
 * in `data-date`), picks the first and the last day and applies.
 */
export async function choosePeriod(trigger: Locator, from: string, to: string): Promise<void> {
  await trigger.click();
  const popup = trigger.page().locator('.cdk-overlay-container .smt-date-popup--range').last();
  const firstDay = popup.locator('td[data-date]').first();
  // The previous and next month buttons are the first and the last of the calendar header.
  const navigation = popup.locator('.smt-calendar__nav');
  for (const day of [from, to]) {
    const month = day.slice(0, 7);
    for (let step = 0; step < 36; step++) {
      const shown = ((await firstDay.getAttribute('data-date')) ?? '').slice(0, 7);
      if (shown === month) break;
      await (shown > month ? navigation.first() : navigation.last()).click();
      // Wait for the grid of the next month before judging again.
      await expect(firstDay).not.toHaveAttribute('data-date', new RegExp(`^${shown}`));
    }
    await popup.locator(`td[data-date="${day}"]`).click();
  }
  await popup.locator('.smt-date-popup__button--primary').click();
}
