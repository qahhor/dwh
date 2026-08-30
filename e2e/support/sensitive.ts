import { expect, type Locator } from '@playwright/test';

/**
 * Dismisses a secret-bearing region and removes any surviving DOM before a failure
 * reporter can serialize its text.
 */
export async function dismissSensitiveStatus(
  container: Locator,
  dismissButton: Locator,
): Promise<void> {
  try {
    await dismissButton.click();
  } finally {
    if (await container.count().catch(() => 0) > 0) {
      await container.evaluate((element) => element.remove());
    }
  }

  await expect(dismissButton).toBeHidden();
}
