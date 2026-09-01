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
  await page.getByRole('button', { name: 'Новый клиент' }).click();
  const createDialog = page.getByRole('dialog', { name: 'Создание новой организации' });
  await createDialog.getByLabel('Название компании').fill(clientName);
  await createDialog.getByLabel('Системный код').fill(clientCode);
  await createDialog.getByRole('button', { name: 'Создать организацию' }).click();
  await expect(page.getByRole('table', { name: 'Список клиентов' })).toContainText(clientCode);

  await page.getByRole('button', { name: 'Зарегистрировать экземпляр' }).click();
  const registerDialog = page.getByRole('dialog', { name: 'Регистрация экземпляра клиента' });
  await registerDialog.getByLabel('Клиент (организация)').selectOption(clientCode);
  await registerDialog.getByLabel('Режим размещения').selectOption('MANAGED_CLOUD');
  await expect(registerDialog.getByLabel('Облачный провайдер')).toHaveValue('HETZNER');
  await expect(registerDialog.getByLabel('Объектное хранилище')).toHaveValue('CLOUDFLARE_R2');
  await expect(registerDialog.getByLabel('Edge и защита')).toHaveValue('CLOUDFLARE');
  await registerDialog.getByLabel('URL экземпляра').fill(`https://${clientCode}.e2e.invalid`);
  await registerDialog.getByRole('button', { name: 'Создать enrollment' }).click();
  const tokenStatus = page.getByRole('status').filter({ hasText: 'Одноразовый enrollment-токен' });
  await expect(tokenStatus).toContainText('Действует до');
  await dismissSensitiveStatus(
    tokenStatus,
    tokenStatus.getByRole('button', { name: 'Закрыть' }),
  );

  await page.goto('/fleet');
  await expect(page.getByRole('table', { name: 'Флот экземпляров' })).toContainText(clientName);
  assertNoPageErrors();
});
