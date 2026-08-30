import { expect, test } from '@playwright/test';

import { loginToControlPlane } from '../../../support/auth.js';
import { collectPageErrors, uniqueRunName } from '../../../support/diagnostics.js';
import { dismissSensitiveStatus } from '../../../support/sensitive.js';

test.use({ screenshot: 'off', trace: 'off', video: 'off' });

test('registers an isolated client and instance without retaining its token', async ({ page }) => {
  const clientCode = uniqueRunName('e2e').toLowerCase().replace(/[^a-z0-9-]/gu, '-');
  const clientName = uniqueRunName('E2E client');

  await loginToControlPlane(page);
  const assertNoPageErrors = collectPageErrors(page);
  await page.goto('/clients');
  await page.getByLabel('Код').fill(clientCode);
  await page.getByLabel('Название').fill(clientName);
  await page.getByRole('button', { name: 'Создать', exact: true }).click();
  await expect(page.getByRole('table', { name: 'Список клиентов' })).toContainText(clientCode);

  await page.getByLabel('Клиент', { exact: true }).selectOption(clientCode);
  await page.getByLabel('Адрес').fill(`https://${clientCode}.e2e.invalid`);
  await page.getByRole('button', { name: 'Зарегистрировать' }).click();
  const tokenStatus = page.getByRole('status');
  await dismissSensitiveStatus(
    tokenStatus,
    tokenStatus.getByRole('button', { name: 'Скрыть токен' }),
  );

  await page.goto('/fleet');
  await expect(page.getByRole('table', { name: 'Флот экземпляров' })).toContainText(clientName);
  assertNoPageErrors();
});
