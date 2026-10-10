import { of, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';
import type { FormMeta } from '@core/models/form-meta.models';
import { formField } from '@testing/form-meta';
import { metaField, problem, queryMetaFixture, renderEntityScreen } from '@testing/entity-page';
import type { ReportResult } from './entity-reports';

/*
 * The report tab of the general entity list (ADR-0032 10.2; plan 10/10, item 5.8): a report built and saved without
 * code, on an entity no web code knows — the groupings and measures it offers come from `query-meta` alone.
 */
const CODE = 'test.orders';

const META: FormMeta = {
  code: CODE,
  listCode: CODE,
  fields: [formField('number', 'text', { label: 'Номер', labelKey: '' })],
  layout: [{ key: 'main', labelKey: 'entity.section.main', fields: ['number'] }],
  actions: ['create', 'update'],
  capabilities: ['saved_views'],
};

const LIST = queryMetaFixture(CODE, [
  metaField('number', '', 'text', { label: 'Номер', sortable: true }),
  metaField('status', '', 'enum', { label: 'Статус', enumValues: ['draft', 'posted'] }),
  metaField('orderDate', '', 'date', { label: 'Дата' }),
  metaField('total', '', 'number', { label: 'Сумма заказа', format: 'money' }),
  metaField('customer', '', 'text', { label: 'Клиент' }),
]);

function answer(params: unknown) {
  const grouped = JSON.parse((params as { groupBy: string }).groupBy) as unknown[];
  const result: ReportResult = grouped.length
    ? {
        groups: [{ field: 'status', implicit: false }],
        measures: [{ op: 'count' }],
        rows: [
          { groups: ['draft'], values: [2] },
          { groups: ['posted'], values: [1] },
        ],
        truncated: false,
      }
    : { groups: [], measures: [{ op: 'count' }], rows: [{ groups: [], values: [3] }], truncated: false };
  return of(result);
}

async function render() {
  return renderEntityScreen(`/e/${CODE}`, {
    meta: META,
    list: LIST,
    answers: { [`/entities/${CODE}/report`]: answer },
    changes: {
      [`POST /list-views/${CODE}`]: (body) =>
        of({ id: 7, kind: 'widget', lockVersion: 0, ...(body as Record<string, unknown>) }),
    },
  });
}

async function afterDebounce(settle: () => Promise<void>) {
  await new Promise((resolve) => setTimeout(resolve, 300));
  await settle();
}

function select(root: ParentNode, label: string): HTMLElement {
  const found = Array.from(root.querySelectorAll<HTMLLabelElement>('label')).find(
    (candidate) => candidate.textContent?.trim() === label,
  );
  if (!found) throw new Error(`no field "${label}"`);
  return root.querySelector<HTMLElement>(`#${found.htmlFor}`)!;
}

async function choose(trigger: HTMLElement, option: string, settle: () => Promise<void>) {
  trigger.click();
  await settle();
  const item = Array.from(document.querySelectorAll<HTMLElement>('.smt-select__option')).find(
    (candidate) => candidate.querySelector('.smt-select__option-label')?.textContent?.trim() === option,
  );
  if (!item) throw new Error(`no option "${option}"`);
  item.click();
}

describe('the report tab of the general entity list', () => {
  it('counts the list at once, then groups it as the person chooses, without code', async () => {
    const { root, api, settle } = await render();
    const tabs = Array.from(root.querySelectorAll<HTMLElement>('[data-testid="entity-mode"] [role="radio"]'));
    expect(tabs.map((tab) => tab.textContent?.trim())).toEqual([
      expect.stringContaining('Список'),
      expect.stringContaining('Отчёт'),
    ]);
    tabs[1].click();
    await afterDebounce(settle);

    expect(api.get).toHaveBeenCalledWith(
      `/entities/${CODE}/report`,
      { groupBy: '[]', measures: '[{"op":"count"}]', filter: undefined },
      { notifyError: false },
    );
    expect(root.querySelector('[data-testid="report-kpis"]')?.textContent).toContain('3');

    await choose(select(root, 'Группировать по'), 'Статус', settle);
    await afterDebounce(settle);

    expect(api.get).toHaveBeenLastCalledWith(
      `/entities/${CODE}/report`,
      { groupBy: '[{"field":"status"}]', measures: '[{"op":"count"}]', filter: undefined },
      { notifyError: false },
    );
    const table = root.querySelector('[data-testid="report-table"]') as HTMLTableElement;
    expect(Array.from(table.querySelectorAll('thead th')).map((cell) => cell.textContent?.trim())).toEqual([
      'Статус',
      'Количество',
    ]);
    expect(Array.from(table.querySelectorAll('tbody th')).map((cell) => cell.textContent?.trim())).toEqual([
      'draft',
      'posted',
    ]);
    expect(root.querySelector('[data-testid="report-chart"]')).not.toBeNull();
    // Only a choice, a yes/no, a reference or a date groups; a text field is not offered.
    select(root, 'Затем по').click();
    await settle();
    const offered = Array.from(document.querySelectorAll('.smt-select__option-label')).map((item) =>
      item.textContent?.trim(),
    );
    expect(offered).toEqual(expect.arrayContaining(['Статус', 'Дата']));
    expect(offered).not.toContain('Клиент');
  });

  it('saves the report as a widget of the dashboard', async () => {
    const { root, api, settle, screen } = await render();
    root.querySelectorAll<HTMLElement>('[data-testid="entity-mode"] [role="radio"]')[1].click();
    await afterDebounce(settle);
    await choose(select(root, 'Группировать по'), 'Статус', settle);
    await afterDebounce(settle);

    (root.querySelector('[data-testid="report-save-as"]') as HTMLButtonElement).click();
    await settle();
    const name = screen.querySelector('[data-testid="report-name"]') as HTMLInputElement;
    name.value = 'Заказы по статусам';
    name.dispatchEvent(new Event('input'));
    (screen.querySelector('[data-testid="report-widget"] [role="checkbox"]') as HTMLElement).click();
    await settle();
    (screen.querySelector('[data-testid="form-submit"]') as HTMLButtonElement).click();
    await settle();

    expect(api.post).toHaveBeenCalledWith(
      `/list-views/${CODE}`,
      {
        name: 'Заказы по статусам',
        kind: 'widget',
        state: { groupBy: [{ field: 'status' }], measures: [{ op: 'count' }], filter: [], chart: 'bar' },
        isDefault: false,
      },
      { notifyError: false },
    );
  });

  it('shows why the server refused a report', async () => {
    const { root, settle } = await renderEntityScreen(`/e/${CODE}`, {
      meta: META,
      list: LIST,
      answers: {
        [`/entities/${CODE}/report`]: () => throwError(() => problem(422, { detail: 'Отчёт строится дольше 5 с' })),
      },
    });
    root.querySelectorAll<HTMLElement>('[data-testid="entity-mode"] [role="radio"]')[1].click();
    await afterDebounce(settle);

    expect(root.querySelector('[data-testid="report-error"]')?.textContent?.trim()).toBe('Отчёт строится дольше 5 с');
  });
});
