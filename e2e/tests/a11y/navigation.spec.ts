import { AxeBuilder } from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

for (const theme of ['light', 'dark'] as const) {
  test.describe(`${theme} navigation`, () => {
    test.beforeEach(async ({ page }) => {
      await page.addInitScript(value => localStorage.setItem('smc_theme', value), theme);
      await page.goto('/notes');
      await expect(page.getByRole('heading', { name: 'Заметки', exact: true })).toBeVisible();
    });

    test('collapsed sections leave keyboard order and have distinct accessible names', async ({ page }) => {
      const header = page.locator('.nav-section-header').first();
      await expect(header).toHaveAccessibleName(/Рабочее пространство/);
      await header.click();
      await expect(header).toHaveAttribute('aria-expanded', 'false');
      await page.keyboard.press('Tab');
      await expect(page.locator('.nav-section-header').nth(1)).toBeFocused();
      await expect(page.getByRole('navigation', { name: 'Основная навигация' }).getByRole('link', { name: 'Задачи', exact: true })).toHaveCount(0);
      await header.click();
      await expect(header).toHaveAttribute('aria-expanded', 'true');
      await expect(page.locator('#section-content-workspace')).not.toHaveAttribute('inert');
      await page.keyboard.press('Tab');
      await expect(page.getByRole('link', { name: 'Задачи', exact: true })).toBeFocused();
    });

    test('compact navigation marks the current page', async ({ page }) => {
      await page.getByRole('button', { name: 'Свернуть навигацию', exact: true }).click();
      await page.getByRole('button', { name: 'Рабочее пространство', exact: true }).press('Enter');
      const current = page.locator('.rail-flyout-popover [aria-current="page"]');
      await expect(current).toHaveCount(1);
      await expect(current).toHaveAttribute('href', '/notes');
    });

    test('compact navigation supports keyboard entry, arrows and Escape', async ({ page }) => {
      await page.getByRole('button', { name: 'Свернуть навигацию', exact: true }).click();
      const opener = page.getByRole('button', { name: 'Рабочее пространство', exact: true });
      await opener.press('Enter');
      const menu = page.getByRole('menu', { name: 'Рабочее пространство', exact: true });
      await expect(menu.getByRole('menuitem', { name: 'Задачи', exact: true })).toBeFocused();
      await page.keyboard.press('ArrowDown');
      await expect(menu.getByRole('menuitem', { name: 'Проекты', exact: true })).toBeFocused();
      await page.keyboard.press('Escape');
      await expect(menu).toHaveCount(0);
      await expect(opener).toBeFocused();
      await expect(opener).toHaveAttribute('aria-expanded', 'false');
    });

    test('mobile drawer contains focus and returns it on dismissal or navigation', async ({ page }) => {
      await page.getByRole('button', { name: 'Свернуть навигацию', exact: true }).click();
      await page.setViewportSize({ width: 360, height: 800 });
      const opener = page.getByRole('button', { name: 'Открыть меню навигации', exact: true });
      await opener.click();
      const drawer = page.getByRole('dialog', { name: 'Основная навигация', exact: true });
      await expect(drawer).toBeVisible();
      await expect.poll(() => drawer.getByRole('link', { name: 'Заметки', exact: true })
        .evaluate(link => link.getBoundingClientRect().width)).toBeGreaterThan(200);
      await expect(drawer.getByRole('button', { name: 'Закрыть', exact: true })).toBeFocused();
      await page.keyboard.press('Shift+Tab');
      await expect(drawer.locator('.user-profile-btn')).toBeFocused();
      await page.keyboard.press('Tab');
      await expect(drawer.getByRole('button', { name: 'Закрыть', exact: true })).toBeFocused();
      const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
      expect(result.violations.map(({ id, impact }) => ({ id, impact }))).toEqual([]);
      await page.keyboard.press('Escape');
      await expect(drawer).toHaveCount(0);
      await expect(opener).toBeFocused();
      await opener.click();
      await drawer.getByRole('link', { name: 'Пользователи', exact: true }).click();
      await expect(page).toHaveURL(/\/e\/md.users$/);
      await expect(page.locator('main')).toBeFocused();
      await expect(drawer).toHaveCount(0);
    });

    for (const width of [768, 900, 1023]) {
      test(`navigation stays operable without covering content at ${width}px`, async ({ page }) => {
        await page.setViewportSize({ width, height: 900 });
        const navigation = page.getByRole('navigation', { name: 'Основная навигация' });
        await expect(navigation).toBeVisible();
        await expect(page.locator('aside')).not.toHaveAttribute('inert');
        await expect.poll(() => page.evaluate(() => {
          const sidebar = document.querySelector('aside')!.getBoundingClientRect();
          const content = document.querySelector('.main-wrapper')!.getBoundingClientRect();
          return sidebar.right <= content.left + 1;
        })).toBe(true);
        await navigation.getByRole('link', { name: 'Пользователи', exact: true }).click();
        await expect(page).toHaveURL(/\/e\/md.users$/);
      });
    }
  });
}
