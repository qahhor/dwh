import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { metaField, queryMetaFixture } from '@testing/entity-page';
import type { ReportOp, ReportResult } from './entity-reports';
import { SMTEntityReportComponent } from './smt-entity-report.component';

const META = queryMetaFixture('test.metrics', [
  metaField('category', '', 'enum', {
    label: 'Category',
    enumValues: ['a', 'b'],
    enumLabels: { a: 'Same category', b: 'Same category' },
  }),
  metaField('segment', '', 'enum', {
    label: 'Segment',
    enumValues: ['x', 'y'],
    enumLabels: { x: 'Same segment', y: 'Same segment' },
  }),
  metaField('amount', '', 'number', { label: 'Amount' }),
  metaField('currency', '', 'enum', { label: 'Currency', format: 'currency', enumValues: ['USD', 'UZS'] }),
]);

function result(op: ReportOp = 'count'): ReportResult {
  return {
    groups: [
      { field: 'category', implicit: false },
      { field: 'segment', implicit: false },
    ],
    measures: [op === 'count' ? { op } : { op, field: 'amount' }],
    rows: ['a', 'b', 'c', 'd', 'e', 'f', 'g'].map((segment, index) => ({
      groups: ['a', segment],
      values: [index + 1],
    })),
    truncated: false,
  };
}

function render(value: ReportResult, chart: 'bar' | 'kpi' = 'bar') {
  TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
  const fixture = TestBed.createComponent(SMTEntityReportComponent);
  fixture.componentRef.setInput('result', value);
  fixture.componentRef.setInput('meta', META);
  fixture.componentRef.setInput('chart', chart);
  fixture.componentRef.setInput('caption', 'Metrics');
  fixture.componentRef.setInput('showTable', false);
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

function expectTableFallback(host: HTMLElement, rows: number) {
  expect(host.querySelector('[data-testid="report-chart"]')).toBeNull();
  expect(host.querySelectorAll('[data-testid="report-table"] tbody tr')).toHaveLength(rows);
  expect(host.querySelector('[data-testid="report-chart-fallback"][role="note"]')).not.toBeNull();
}

describe('report display accuracy', () => {
  it.each(['avg', 'min', 'max'] as const)('does not sum %s into an Others series', (op) => {
    const host = render(result(op));
    expectTableFallback(host, 7);
    expect(
      Array.from(host.querySelectorAll('[data-testid="report-table"] tbody td:last-child')).map((cell) =>
        cell.textContent?.trim(),
      ),
    ).toEqual(['1', '2', '3', '4', '5', '6', '7']);
  });

  it.each(['count', 'sum'] as const)('preserves additive %s totals in Others', (op) => {
    const host = render(result(op));
    expect(host.querySelectorAll('[data-testid="bar-chart-bar"] rect')).toHaveLength(5);
    expect(host.querySelector('[data-testid="bar-chart-table"]')?.textContent).toContain('18');
    expect(host.querySelector('[data-testid="report-table"]')).toBeNull();
  });

  it('keeps different axis identities separate when their labels coincide', () => {
    const value = result();
    value.rows = [
      { groups: ['a', 'x'], values: [2] },
      { groups: ['b', 'x'], values: [3] },
    ];
    const host = render(value);
    const bars = host.querySelectorAll('[data-testid="bar-chart-bar"]');
    expect(bars).toHaveLength(2);
    expect(bars[0].querySelector('title')?.textContent).toContain('2');
    expect(bars[1].querySelector('title')?.textContent).toContain('3');
  });

  it('keeps different stack identities separate when their labels coincide', () => {
    const value = result();
    value.rows = [
      { groups: ['a', 'x'], values: [2] },
      { groups: ['a', 'y'], values: [3] },
    ];
    const host = render(value);
    expect(host.querySelectorAll('[data-testid="bar-chart-bar"] rect')).toHaveLength(2);
    const cells = Array.from(host.querySelectorAll('[data-testid="bar-chart-table"] tbody td'));
    expect(cells.map((cell) => cell.textContent?.trim())).toEqual(['2', '3']);
  });

  it('does not confuse a real rest-marker label with the synthetic series', () => {
    const value = result();
    value.rows[0].groups[1] = '__rest__';
    const host = render(value);
    const cells = Array.from(host.querySelectorAll('[data-testid="bar-chart-table"] tbody td'));
    expect(cells.map((cell) => cell.textContent?.trim())).toEqual(['1', '2', '3', '4', '18']);
  });

  it('keeps numeric and string keys distinct', () => {
    const value = result();
    value.rows = [
      { groups: [1, 'x'], values: [2] },
      { groups: ['1', 'x'], values: [3] },
    ];
    const host = render(value);
    expect(host.querySelectorAll('[data-testid="bar-chart-bar"]')).toHaveLength(2);
  });

  it('does not stack amounts expressed in different currencies', () => {
    const value = result('sum');
    value.groups[1] = { field: 'currency', implicit: true };
    value.rows = [
      { groups: ['a', 'USD'], values: [10] },
      { groups: ['a', 'UZS'], values: [30] },
    ];
    const host = render(value);
    expectTableFallback(host, 2);
    expect(host.querySelector('[data-testid="report-table"]')?.textContent).toContain('USD');
    expect(host.querySelector('[data-testid="report-table"]')?.textContent).toContain('UZS');
  });

  it('does not stack currencies when the currency grouping is explicit', () => {
    const value = result('sum');
    value.groups[1] = { field: 'currency', implicit: false };
    value.rows = [
      { groups: ['a', 'USD'], values: [10] },
      { groups: ['a', 'UZS'], values: [30] },
    ];
    expectTableFallback(render(value), 2);
  });

  it.each(['avg', 'min', 'max'] as const)('does not invent zero %s values for missing group pairs', (op) => {
    const value = result(op);
    value.rows = [
      { groups: ['a', 'x'], values: [10] },
      { groups: ['b', 'y'], values: [20] },
    ];
    const host = render(value);
    expectTableFallback(host, 2);
    expect(
      Array.from(host.querySelectorAll('[data-testid="report-table"] tbody td:last-child')).map((cell) =>
        cell.textContent?.trim(),
      ),
    ).toEqual(['10', '20']);
  });

  it('does not drop a third grouping added for money', () => {
    const value = result('sum');
    value.groups.push({ field: 'currency', implicit: true });
    value.rows = [
      { groups: ['a', 'x', 'USD'], values: [10] },
      { groups: ['a', 'x', 'UZS'], values: [30] },
    ];
    expectTableFallback(render(value), 2);
  });

  it.each([null, -3, Number.NaN, Number.POSITIVE_INFINITY])(
    'does not draw an unsupported first measure as a positive bar: %s',
    (amount) => {
      const value = result('sum');
      value.groups = [{ field: 'category', implicit: false }];
      value.rows = [{ groups: ['a'], values: [amount] }];
      const host = render(value);
      expectTableFallback(host, 1);
      if (amount === null) expect(host.querySelector('tbody td')?.textContent?.trim()).toBe('—');
      if (amount === -3) expect(host.querySelector('tbody td')?.textContent?.trim()).toBe('-3');
    },
  );

  it('keeps a supported nonadditive result without collapsing its series', () => {
    const value = result('avg');
    value.rows = value.rows.slice(0, 5);
    const host = render(value);
    expect(host.querySelectorAll('[data-testid="bar-chart-bar"] rect')).toHaveLength(5);
    expect(host.querySelector('[data-testid="report-table"]')).toBeNull();
  });

  it('does not present a missing KPI as zero', () => {
    const value = result('avg');
    value.groups = [];
    value.rows = [{ groups: [], values: [null] }];
    expect(render(value, 'kpi').querySelector('.kpi__value')?.textContent?.trim()).toBe('—');
  });

  it('keeps an actual zero KPI', () => {
    const value = result();
    value.groups = [];
    value.rows = [{ groups: [], values: [0] }];
    expect(render(value, 'kpi').querySelector('.kpi__value')?.textContent?.trim()).toBe('0');
  });
});
