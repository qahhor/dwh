import { AxeBuilder } from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

import { chooseOption } from '../../support/select.js';

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
  // The users on the general entity screen (ADR-0032 8): the list, the form with the manager picker open, the
  // record with its sessions, and its roles and rights with the division assignments.
  {
    name: 'user list',
    path: '/e/md.users',
    open: async page => {
      await expect(page.getByRole('table', { name: 'Пользователи' })).toBeVisible();
      await expect(page.getByRole('link', { name: 'Сотрудник 2', exact: true })).toBeVisible();
    },
  },
  {
    name: 'user edit form with the manager picker open',
    path: '/e/md.users/2/edit',
    open: async page => {
      await page.getByRole('combobox', { name: 'Руководитель', exact: true }).click();
      await expect(page.getByRole('listbox', { name: 'Руководитель' })).toBeVisible();
      // Not /Сотрудник 3/: once the manager search lands it also matches Сотрудник 30–39.
      await expect(page.getByRole('option', { name: /Сотрудник 3 @user3$/ })).toBeVisible();
    },
  },
  {
    name: 'user record with its sessions',
    path: '/e/md.users/2',
    open: async page => {
      await expect(page.getByRole('heading', { name: 'Сотрудник 2' })).toBeVisible();
      await page.getByRole('tab', { name: 'Сессии и безопасность' }).click();
      await expect(page.getByText('10.0.0.2').first()).toBeVisible();
    },
  },
  {
    name: 'user card division assignments',
    path: '/e/md.users/2',
    open: async page => {
      await page.getByRole('tab', { name: 'Роли и права' }).click();
      await expect(page.getByRole('treegrid').first()).toBeVisible();
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
      // The list opens sorted by name, ascending (ms.projects); one click turns the whole list on the server.
      const nameHeader = table.getByRole('columnheader', { name: /Проект/ });
      await nameHeader.click();
      await expect(nameHeader).toHaveAttribute('aria-sort', 'descending');
    },
  },
  {
    name: 'task list with the responsible lookup open',
    path: '/tasks',
    open: async page => {
      await page.locator('button.smt-button').filter({ hasText: /Новая задача|Создать задачу/ }).first().click();
      await page.getByRole('combobox', { name: 'Ответственный' }).first().click();
      await expect(page.getByRole('listbox').first()).toBeVisible();
    },
  },
  {
    // Plan 10/10, item 3.5: the project filter searches the paged project list on the server.
    name: 'task list with the project filter open',
    path: '/tasks',
    open: async page => {
      await page.getByRole('combobox', { name: 'Фильтр по проекту' }).click();
      await expect(page.getByRole('option', { name: 'Выкладка в сети 1', exact: true })).toBeVisible();
    },
  },
  {
    name: 'notes board',
    path: '/notes',
    open: async page => {
      await expect(page.getByText('Планёрка филиала 6', { exact: true })).toBeVisible();
    },
  },
  {
    name: 'note edit form with its history',
    path: '/notes',
    open: async page => {
      await page.locator('app-note-card').first().getByRole('button', { name: 'Редактировать' }).click();
      const dialog = page.getByRole('dialog');
      await expect(dialog.getByRole('textbox').first()).toHaveValue('Планёрка филиала 1');
      await dialog.getByTestId('record-history-toggle').click();
      await expect(dialog.getByText('Иван Петров').first()).toBeVisible();
    },
  },
  // ADR-0032 7.1: the general entity screen, drawn from metadata alone, here for the notes.
  {
    name: 'general entity list',
    path: '/e/ms.notes',
    open: async page => {
      await expect(page.getByRole('table', { name: 'Заметки' })).toBeVisible();
      await expect(page.getByRole('link', { name: 'Планёрка филиала 1', exact: true })).toBeVisible();
    },
  },
  {
    name: 'general entity form',
    path: '/e/ms.notes/new',
    open: async page => {
      await expect(page.getByRole('textbox', { name: 'Заголовок' })).toBeVisible();
      // A new record is created: the primary button of the form says so (forms standard, section 6).
      await expect(page.getByRole('button', { name: 'Создать', exact: true })).toBeVisible();
    },
  },
  {
    name: 'general entity record with its history',
    path: '/e/ms.notes/1',
    open: async page => {
      await expect(page.getByRole('heading', { name: 'Планёрка филиала 1' })).toBeVisible();
      await page.getByRole('tab', { name: 'История' }).click();
      await expect(page.getByText('Иван Петров').first()).toBeVisible();
    },
  },
  // ADR-0032 9.4: the reference document with lines and statuses on the general screen.
  {
    name: 'document list',
    path: '/e/example.orders',
    open: async page => {
      await expect(page.getByRole('table', { name: 'Заказы (эталон)' })).toBeVisible();
      await expect(page.getByRole('link', { name: 'ORD-000006', exact: true })).toBeVisible();
    },
  },
  // ADR-0032 10.1: the import dialog of the general list.
  {
    name: 'document import dialog',
    path: '/e/example.orders',
    open: async page => {
      await page.getByTestId('entity-import').click();
      const dialog = page.getByRole('dialog', { name: 'Импорт из файла' });
      await expect(dialog).toBeVisible();
      await expect(dialog.getByRole('button', { name: 'Проверить' })).toBeDisabled();
    },
  },
  {
    name: 'document form with its lines',
    path: '/e/example.orders/1/edit',
    open: async page => {
      await expect(page.getByRole('textbox', { name: 'Клиент' })).toHaveValue('Магазин «Ассорти» 1');
      await expect(page.getByRole('group', { name: 'Строка 3' })).toBeVisible();
      await expect(page.getByRole('button', { name: 'Добавить строку' })).toBeVisible();
    },
  },
  {
    name: 'document record with its lines and history',
    path: '/e/example.orders/1',
    open: async page => {
      await expect(page.getByRole('heading', { name: 'ORD-000001' })).toBeVisible();
      await expect(page.getByRole('button', { name: 'Провести' })).toBeVisible();
      await page.getByRole('tab', { name: 'Строки' }).click();
      await expect(page.getByRole('cell', { name: 'Сахар, 25 кг' })).toBeVisible();
      await page.getByRole('tab', { name: 'История' }).click();
      await expect(page.getByText('Иван Петров').first()).toBeVisible();
    },
  },
  // ADR-0032 10.2: a report built on the list without code, and the viewer's widget on the dashboard.
  {
    name: 'document report with its chart and table',
    path: '/e/example.orders',
    open: async page => {
      await page.getByTestId('entity-mode').getByRole('radio', { name: 'Отчёт' }).click();
      await chooseOption(page.getByLabel('Группировать по'), 'status');
      await expect(page.getByTestId('report-chart')).toBeVisible();
      await expect(page.getByRole('table', { name: 'Отчёт: Заказы (эталон)', exact: true })).toBeVisible();
    },
  },
  {
    name: 'analytics dashboard with a widget',
    path: '/analytics',
    open: async page => {
      const widget = page.getByTestId('analytics-widget');
      await expect(widget.getByRole('heading', { name: 'Заказы по статусам' })).toBeVisible();
      await expect(widget.getByTestId('bar-chart-bar').first()).toBeVisible();
    },
  },
];

for (const theme of ['light', 'dark'] as const) {
  test.describe(`${theme} theme`, () => {
    for (const screen of screens) {
      test(`${screen.name} has no WCAG 2.1 AA violations`, async ({ page }) => {
        await page.addInitScript(value => localStorage.setItem('smc_theme', value), theme);
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
