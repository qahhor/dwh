import { type Locator } from '@playwright/test';

async function setNativeInputValue(locator: Locator, value: string): Promise<void> {
  await locator.evaluate((element, nextValue) => {
    if (!(element instanceof HTMLInputElement)) {
      throw new Error('Secret target must be an input element');
    }

    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
    if (!setter) throw new Error('Native input value setter is unavailable');

    setter.call(element, nextValue);
    element.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
    element.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
  }, value);
}

/**
 * Enters a credential without putting its value into Playwright API-step titles.
 * The artifact-security probe guards this property against reporter regressions.
 */
export async function fillSecret(locator: Locator, secret: string): Promise<void> {
  if (!secret) throw new Error('Secret value must not be empty');

  await setNativeInputValue(locator, secret);
}

/** Removes a credential from the DOM before Playwright captures failure context. */
export async function clearSecret(locator: Locator): Promise<void> {
  if (await locator.count().catch(() => 0) === 0) return;
  await setNativeInputValue(locator, '');
}
