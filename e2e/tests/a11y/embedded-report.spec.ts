import { AxeBuilder } from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

for (const theme of ['light', 'dark'] as const) {
  test(`embedded report fullscreen fits a short mobile viewport in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 640 });
    await page.addInitScript(value => localStorage.setItem('smc_theme', value), theme);
    await page.route('**/api/v1/navigation/items/by-code/a11y-report', route => route.fulfill({
      json: {
        id: 1, code: 'a11y-report', title: 'Test report', sectionId: 'workspace',
        icon: 'assessment', targetType: 'EMBEDDED_IFRAME', url: '/a11y-report-frame.html',
        openInIframe: true, sortOrder: 0, state: 'A', revision: 1,
      },
    }));
    await page.route('**/a11y-report-frame.html', route => route.fulfill({
      contentType: 'text/html',
      body: '<!doctype html><html lang="en"><head><title>Report fixture</title></head><body><main><h1>Report content</h1></main></body></html>',
    }));
    await page.goto('/embed/a11y-report');
    const report = page.locator('.embedded-report-container');
    await expect(report.getByRole('heading', { name: 'Test report', exact: true })).toBeVisible();
    await expect(report.locator('.report-loading')).toHaveCount(0);
    await report.locator('.report-toolbar button[aria-pressed]').click();
    await expect(report).toHaveClass(/fullscreen/);
    await expect.poll(() => report.evaluate(container => {
      const selectors = ['.report-toolbar', '.report-iframe', '.report-fallback-bar'];
      return selectors.flatMap(selector => {
        const element = container.querySelector(selector);
        if (!element) return [{ selector, missing: true }];
        const rect = element.getBoundingClientRect();
        const fits = rect.width > 0 && rect.height > 0 && rect.top >= -1 && rect.left >= -1
          && rect.bottom <= window.innerHeight + 1 && rect.right <= window.innerWidth + 1;
        return fits ? [] : [{ selector, missing: false }];
      });
    })).toEqual([]);
    const result = await new AxeBuilder({ page }).include('.embedded-report-container')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
    expect(result.violations.map(({ id, impact }) => ({ id, impact }))).toEqual([]);
    await report.getByRole('button', { name: 'Закрыть', exact: true }).click();
    await expect(report.locator('.report-fallback-bar')).toHaveCount(0);
    await report.locator('.report-toolbar button[aria-pressed]').click();
    await expect(report).not.toHaveClass(/fullscreen/);
  });
}
