import { AxeBuilder } from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

/*
 * WCAG 2.1 A and AA through axe on the screens rebuilt on the shared table,
 * tree and server table, in both themes. The build is the production one and
 * the API is mocked (support/a11y-fixtures.mjs), so a regression in markup,
 * roles or contrast fails the frontend job before it reaches a deployment.
 */
const WCAG = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];

interface Screen {
  name: string;
  path: string;
  /** Brings the screen to the state worth checking once it has loaded. */
  open: (page: Page) => Promise<void>;
}

const screens: Screen[] = [
  {
    name: 'language list',
    path: '/settings',
    open: async page => {
      await page.getByRole('tab', { name: /Языки/ }).click();
      await expect(page.getByRole('table')).toBeVisible();
    },
  },
  {
    name: 'organizational structure',
    path: '/iam/org-units',
    open: async page => { await expect(page.getByRole('treegrid')).toBeVisible(); },
  },
  {
    name: 'audit change log, second page',
    path: '/audit',
    open: async page => {
      await expect(page.getByRole('table', { name: 'Журнал изменений данных' })).toBeVisible();
      await page.getByRole('button', { name: 'Следующая страница' }).click();
      await expect(page.getByText('#10', { exact: true })).toBeVisible();
    },
  },
  {
    name: 'audit security events',
    path: '/audit',
    open: async page => {
      await page.locator('#security-events-tab').click();
      await expect(page.getByRole('table', { name: 'События безопасности' })).toBeVisible();
    },
  },
  {
    name: 'user list',
    path: '/iam/users',
    open: async page => {
      await expect(page.getByRole('table', { name: 'Список пользователей' })).toBeVisible();
      await expect(page.getByText('Сотрудник 80', { exact: true }).first()).toBeVisible();
    },
  },
  {
    name: 'user edit form with the manager picker open',
    path: '/iam/users',
    open: async page => {
      await page.getByRole('button', { name: 'Редактировать пользователя Сотрудник 2', exact: true }).click();
      await page.getByRole('dialog').getByRole('button', { name: 'Руководитель', exact: true }).click();
      await expect(page.getByRole('listbox', { name: 'Руководитель' })).toBeVisible();
      // Not /Сотрудник 3/: once the manager search lands it also matches Сотрудник 30–39.
      await expect(page.getByRole('option', { name: /Сотрудник 3 @user3$/ })).toBeVisible();
    },
  },
  {
    name: 'user card division assignments',
    path: '/iam/users',
    open: async page => {
      await page.locator('button.user-identity').first().click();
      await page.getByRole('tab', { name: /Оргструктура/ }).click();
      await expect(page.getByRole('treegrid')).toBeVisible();
    },
  },
  {
    name: 'task list, second page',
    path: '/tasks',
    open: async page => {
      await expect(page.getByRole('table', { name: 'Список задач' })).toBeVisible();
      await page.getByRole('button', { name: 'Следующая страница' }).click();
      await expect(page.getByRole('button', { name: /^Открыть задачу #41: / })).toBeVisible();
    },
  },
  {
    name: 'project list sorted by name, descending',
    path: '/tasks/projects',
    open: async page => {
      const table = page.getByRole('table', { name: 'Список проектов' });
      await expect(table).toBeVisible();
      const nameHeader = table.getByRole('columnheader', { name: /Проект/ });
      await nameHeader.click();
      await nameHeader.click();
      await expect(nameHeader).toHaveAttribute('aria-sort', 'descending');
    },
  },
  {
    name: 'task list with the responsible lookup open',
    path: '/tasks',
    open: async page => {
      await page.locator('ui-button').filter({ hasText: /Новая задача|Создать задачу/ }).first().locator('button').click();
      await page.getByRole('button', { name: 'Ответственный' }).first().click();
      await expect(page.getByRole('listbox').first()).toBeVisible();
    },
  },
];

for (const theme of ['light', 'dark'] as const) {
  test.describe(`${theme} theme`, () => {
    for (const screen of screens) {
      test(`${screen.name} has no WCAG 2.1 AA violations`, async ({ page }) => {
        await page.addInitScript(value => localStorage.setItem('dwh_theme', value), theme);
        await page.goto(screen.path);
        await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
        await screen.open(page);

        const result = await new AxeBuilder({ page }).withTags(WCAG).analyze();
        const violations = result.violations.map(violation => ({
          id: violation.id,
          impact: violation.impact,
          help: violation.help,
          targets: violation.nodes.slice(0, 5).map(node => node.target.join(' ')),
        }));
        expect(violations, 'axe WCAG 2.1 AA violations').toEqual([]);

        // aria-label on an element whose role does not take a name is silently
        // dropped by screen readers; axe reports it as "needs review", so the
        // gate asserts it explicitly.
        const unnamed = result.incomplete
          .filter(check => check.id === 'aria-prohibited-attr')
          .flatMap(check => check.nodes.map(node => node.target.join(' ')));
        expect(unnamed, 'names a screen reader would drop').toEqual([]);
      });
    }
  });
}
