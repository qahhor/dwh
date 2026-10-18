import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { metaField, queryMetaFixture } from '@testing/entity-page';
import { translateTest } from '@testing/i18n-test.stub';
import type { ReportResult } from './entity-reports';
import { SMTReportPivotComponent } from './smt-report-pivot.component';

const META = queryMetaFixture('test.orders', [
  metaField('region', '', 'enum', {
    label: 'Регион',
    enumValues: ['north', 'south'],
    ops: ['eq', 'in', 'empty'],
  }),
  metaField('day', '', 'date', { label: 'Дата', ops: ['gte', 'lt', 'empty'] }),
  metaField('total', '', 'number', { label: 'Сумма' }),
]);

/** Regions by month: north has both months, south only October; two measures, the second a share of the row. */
const PIVOT: ReportResult = {
  groups: [
    { field: 'region', implicit: false },
    { field: 'day', trunc: 'month', implicit: false },
  ],
  measures: [
    { op: 'sum', field: 'total' },
    { op: 'sum', field: 'total', share: 'row' },
  ],
  rows: [
    { groups: ['north', '2026-09-01'], values: [100, 40], labels: ['Север', null] },
    { groups: ['north', '2026-10-01'], values: [150, 60], labels: ['Север', null] },
    { groups: ['south', '2026-10-01'], values: [50, 100], labels: ['Юг', null] },
  ],
  totals: [
    { kind: 'row', groups: ['north', null], values: [250, 100], labels: ['Север', null] },
    { kind: 'row', groups: ['south', null], values: [50, 100], labels: ['Юг', null] },
    { kind: 'column', groups: [null, '2026-09-01'], values: [100, 33.33], labels: [null, null] },
    { kind: 'column', groups: [null, '2026-10-01'], values: [200, 66.67], labels: [null, null] },
    { kind: 'grand', groups: [null, null], values: [300, 100], labels: [null, null] },
  ],
  truncated: false,
  pivot: true,
};

function render(result: ReportResult, drillCode: string | null = 'test.orders') {
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
  });
  const fixture = TestBed.createComponent(SMTReportPivotComponent);
  fixture.componentRef.setInput('result', result);
  fixture.componentRef.setInput('meta', META);
  fixture.componentRef.setInput('caption', 'Отчёт: Заказы');
  fixture.componentRef.setInput('drillCode', drillCode);
  fixture.componentRef.setInput('drillBase', []);
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('smt-report-pivot (ADR-0044)', () => {
  it('lays the rows down and the columns across with each measure, the totals at the right and bottom', () => {
    const host = render(PIVOT);
    const table = host.querySelector('[data-testid="report-pivot"]')!;
    const headers = [...table.querySelectorAll('thead th')].map((th) => th.textContent!.trim());
    expect(headers[0]).toBe('Регион / Дата');
    expect(headers).toContain(translateTest('ui.report.total'));
    expect(table.querySelectorAll('thead tr')).toHaveLength(2);
    expect(table.querySelector('thead th[scope="colgroup"]')?.getAttribute('colspan')).toBe('2');
    const rows = [...table.querySelectorAll('[data-testid="report-pivot-row"]')];
    expect(rows.map((row) => row.querySelector('th[scope="row"]')!.textContent!.trim())).toEqual(['Север', 'Юг']);
    const south = [...rows[1].querySelectorAll('td')].map((td) => td.textContent!.trim());
    expect(south.slice(0, 2)).toEqual(['', '']);
    expect(south[3]).toContain('%');
    expect(
      [...table.querySelectorAll('[data-testid="report-pivot-grand"]')].map((td) => td.textContent!.trim())[0],
    ).toMatch(/300/);
    expect(table.querySelectorAll('[data-testid="report-pivot-footer"] td')).toHaveLength(6);
  });

  it('opens the records of a cell exactly: its row and its column bucket', () => {
    const host = render(PIVOT);
    const links = [...host.querySelectorAll<HTMLAnchorElement>('[data-testid="report-pivot-drill"]')];
    expect(links).toHaveLength(3);
    const filter = JSON.parse(new URL(links[0].href).searchParams.get('filter')!);
    expect(filter).toEqual([
      { field: 'region', op: 'eq', value: 'north', label: 'Север' },
      { field: 'day', op: 'gte', value: '2026-09-01' },
      { field: 'day', op: 'lt', value: '2026-10-01' },
    ]);
    expect(links[0].getAttribute('aria-label')).toContain('Север');
  });

  it('has no links without a list to open and a card per row for a narrow screen', () => {
    const host = render(PIVOT, null);
    expect(host.querySelectorAll('[data-testid="report-pivot-drill"]')).toHaveLength(0);
    const cards = host.querySelectorAll('[data-testid="report-pivot-cards"] .pivot-card');
    expect(cards).toHaveLength(3);
    expect(cards[1].querySelector('h4')?.textContent?.trim()).toBe('Юг');
  });
});
