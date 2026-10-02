import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { describe, expect, it } from 'vitest';
import { metaField, queryMetaFixture } from '@testing/entity-page';
import { groupText, groupable, measurable, measureLabel, type ReportChart, type ReportResult } from './entity-reports';
import { SMTEntityReportComponent } from './smt-entity-report.component';

const META = queryMetaFixture('test.orders', [
  metaField('number', '', 'text', { label: 'Номер', sortable: true }),
  metaField('status', '', 'enum', {
    label: 'Статус',
    enumValues: ['draft', 'posted'],
    enumLabels: { draft: 'Черновик' },
  }),
  metaField('paid', '', 'boolean', { label: 'Оплачен' }),
  metaField('orderDate', '', 'date', { label: 'Дата' }),
  metaField('customerId', '', 'number', {
    label: 'Клиент',
    ref: { path: '/customers', labelField: 'name', keyField: 'id', paged: false },
  }),
  metaField('total', '', 'number', { label: 'Сумма', format: 'money' }),
  metaField('totalCurrency', '', 'enum', { label: 'Сумма', format: 'currency', enumValues: ['UZS', 'USD'] }),
  metaField('email', '', 'text', { label: 'Почта', format: 'email' }),
]);

function render(result: ReportResult, chart: ReportChart, showTable = true) {
  TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
  const fixture = TestBed.createComponent(SMTEntityReportComponent);
  fixture.componentRef.setInput('result', result);
  fixture.componentRef.setInput('meta', META);
  fixture.componentRef.setInput('chart', chart);
  fixture.componentRef.setInput('caption', 'Отчёт: Заказы');
  fixture.componentRef.setInput('showTable', showTable);
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('report fields and words (ADR-0032 10.2)', () => {
  it('groups by a choice, a yes/no, a date and a reference; measures a number or money only', () => {
    const by = (key: string) => META.fields.find((field) => field.key === key)!;
    expect(['status', 'paid', 'orderDate', 'customerId'].map((key) => groupable(by(key)))).toEqual([
      true,
      true,
      true,
      true,
    ]);
    expect(groupable(by('number'))).toBe(false);
    expect(measurable(by('total'))).toBe(true);
    expect(measurable(by('customerId'))).toBe(false);
    expect(measurable(by('status'))).toBe(false);
  });

  it('names values in words: a choice, yes/no, an empty group, a reference and every bucket', () => {
    const translate = (key: string) => ({ 'common.yes': 'Да', 'ui.report.empty_group': '(пусто)' })[key] ?? key;
    const words = {
      translate,
      date: (iso: string, options: Intl.DateTimeFormatOptions) => `${iso}|${options.month}`,
      refName: () => 'ООО Ромашка',
    };
    const status = META.fields[1];
    expect(groupText('draft', status, undefined, words)).toBe('Черновик');
    expect(groupText('posted', status, undefined, words)).toBe('posted');
    expect(groupText(true, undefined, undefined, words)).toBe('Да');
    expect(groupText(null, status, undefined, words)).toBe('(пусто)');
    expect(groupText(5, META.fields[4], undefined, words)).toBe('ООО Ромашка');
    expect(groupText('2026-04-01', undefined, 'year', words)).toBe('2026');
    expect(groupText('2026-04-01', undefined, 'quarter', words)).toBe('2026 Q2');
    expect(groupText('2026-04-01', undefined, 'month', words)).toBe('2026-04-01|long');
    expect(groupText('2026-04-06', undefined, 'week', words)).toBe('2026-04-06|short');
    expect(measureLabel({ op: 'sum', field: 'total' }, META, (key) => key)).toBe('ui.report.op.sum: Сумма');
  });
});

describe('smt-entity-report', () => {
  it('shows one figure per measure when nothing is grouped', () => {
    const host = render(
      {
        groups: [],
        measures: [{ op: 'count' }, { op: 'sum', field: 'total' }],
        rows: [{ groups: [], values: [12, 1500.5] }],
        truncated: false,
      },
      'table',
    );

    expect(host.querySelectorAll('ui-kpi-card')).toHaveLength(2);
    expect(host.querySelector('[data-testid="report-chart"]')).toBeNull();
  });

  it('draws bars stacked by the currency of money, and the same figures in a table with headers', () => {
    const host = render(
      {
        groups: [
          { field: 'status', implicit: false },
          { field: 'totalCurrency', implicit: true },
        ],
        measures: [{ op: 'sum', field: 'total' }],
        rows: [
          { groups: ['draft', 'USD'], values: [10] },
          { groups: ['draft', 'UZS'], values: [30] },
          { groups: ['posted', 'UZS'], values: [7.4] },
        ],
        truncated: true,
      },
      'bar',
    );

    const bars = host.querySelectorAll('[data-testid="bar-chart-bar"]');
    expect(bars).toHaveLength(2);
    expect(bars[0].querySelector('title')?.textContent).toContain('Черновик');
    expect(bars[0].querySelectorAll('rect')).toHaveLength(2);
    const table = host.querySelector('[data-testid="report-table"]') as HTMLTableElement;
    expect(table.querySelector('caption')?.textContent?.trim()).toBe('Отчёт: Заказы');
    expect(Array.from(table.querySelectorAll('thead th')).map((cell) => cell.getAttribute('scope'))).toEqual([
      'col',
      'col',
      'col',
    ]);
    expect(table.querySelectorAll('tbody tr')).toHaveLength(3);
    expect(table.querySelector('tbody th')?.getAttribute('scope')).toBe('row');
    expect(host.querySelector('[role="note"]')).not.toBeNull();
  });

  it('adds the stacks past five into one, and a widget shows its chart without the table', () => {
    const rows = ['a', 'b', 'c', 'd', 'e', 'f', 'g'].map((day, index) => ({
      groups: ['draft', day],
      values: [index + 1],
    }));
    const host = render(
      {
        groups: [
          { field: 'status', implicit: false },
          { field: 'email', implicit: false },
        ],
        measures: [{ op: 'count' }],
        rows,
        truncated: false,
      },
      'bar',
      false,
    );

    expect(host.querySelectorAll('[data-testid="bar-chart-bar"] rect')).toHaveLength(5);
    expect(host.querySelector('[data-testid="report-table"]')).toBeNull();
    expect(host.querySelector('[data-testid="bar-chart-table"]')?.textContent).toContain('18');
  });

  it('says when the report has no rows', () => {
    const host = render({ groups: [], measures: [{ op: 'count' }], rows: [], truncated: false }, 'table');
    expect(host.querySelector('[data-testid="report-empty"]')).not.toBeNull();
  });
});
