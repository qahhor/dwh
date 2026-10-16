import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { metaField, queryMetaFixture } from '@testing/entity-page';
import type { ReportResult } from './entity-reports';
import { SMTEntityReportComponent } from './smt-entity-report.component';

const META = queryMetaFixture('test.metrics', [
  metaField('month', '', 'instant', { label: 'Month' }),
  metaField('category', '', 'enum', {
    label: 'Category',
    enumValues: ['a', 'b'],
    enumLabels: { a: 'Same', b: 'Same' },
  }),
  metaField('amount', '', 'number', { label: 'Amount' }),
  metaField('currency', '', 'enum', { label: 'Currency', format: 'currency' }),
]);

function series(values: (number | null)[] = [-3, 0, null, 5]): ReportResult {
  return {
    groups: [{ field: 'month', trunc: 'month', implicit: false }],
    measures: [{ op: 'avg', field: 'amount' }],
    rows: values.map((value, index) => ({ groups: [`2026-0${index + 1}-01`], values: [value] })),
    truncated: false,
  };
}

function render(result: ReportResult, chart = 'line') {
  TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
  const fixture = TestBed.createComponent(SMTEntityReportComponent);
  fixture.componentRef.setInput('result', result);
  fixture.componentRef.setInput('meta', META);
  fixture.componentRef.setInput('caption', 'Metrics');
  fixture.componentRef.setInput('chart', chart);
  fixture.componentRef.setInput('showTable', false);
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('additional report charts (ADR-0039)', () => {
  it('shows signed temporal values and leaves NULL as a gap with an exact visible table', () => {
    const host = render(series());
    expect(host.querySelector('[data-testid="report-line"]')).not.toBeNull();
    expect(host.querySelectorAll('[data-testid="line-point"]')).toHaveLength(3);
    expect(host.querySelectorAll('[data-testid="line-segment"]')).toHaveLength(2);
    expect(host.querySelectorAll('[data-testid="report-table"] tbody tr')).toHaveLength(4);
    expect(host.querySelectorAll('[data-testid="report-table"] tbody td')[2].textContent).toBe('—');
  });

  it('does not connect missing buckets, including a missing calendar month', () => {
    const value = series([1, 2, 3]);
    value.rows[1].groups = ['2026-03-01'];
    value.rows[2].groups = ['2026-04-01'];
    const host = render(value);
    expect(host.querySelectorAll('[data-testid="line-segment"]')).toHaveLength(2);
    expect(host.querySelectorAll('[data-testid="line-point"]')).toHaveLength(3);
  });

  it('draws horizontal signed values independently without merging equal labels or average values', () => {
    const value = series([-3, 0, 5, null]);
    value.groups = [{ field: 'category', implicit: false }];
    value.rows.forEach((row, index) => {
      row.groups = [['a', 'b', 'c', null][index]];
    });
    const host = render(value, 'horizontalBar');
    expect(host.querySelector('[data-testid="report-horizontal-bar"]')).not.toBeNull();
    expect(host.querySelectorAll('[data-testid="horizontal-value"]')).toHaveLength(3);
    expect(host.querySelector('[data-testid="horizontal-zero"]')).not.toBeNull();
    expect(host.querySelectorAll('[data-testid="report-table"] tbody tr')).toHaveLength(4);
  });

  it.each(['truncated', 'currency', 'manyMeasures', 'invalid', 'categoryLine', 'dateHorizontal'] as const)(
    'falls back honestly for %s',
    (kind) => {
      const value = series([1, 2]);
      if (kind === 'truncated') value.truncated = true;
      if (kind === 'currency') {
        value.groups.push({ field: 'currency', implicit: true });
        value.rows[0].groups.push('USD');
        value.rows[1].groups.push('UZS');
      }
      if (kind === 'manyMeasures') value.measures.push({ op: 'count' });
      if (kind === 'invalid') value.rows[0].values = [Infinity];
      if (kind === 'categoryLine') value.groups = [{ field: 'category', implicit: false }];
      const host = render(value, kind === 'dateHorizontal' ? 'horizontalBar' : 'line');
      expect(host.querySelector('[data-testid="report-chart-fallback"]')).not.toBeNull();
      expect(host.querySelector('[data-testid="report-line"]')).toBeNull();
      expect(host.querySelector('[data-testid="report-horizontal-bar"]')).toBeNull();
      expect(host.querySelectorAll('[data-testid="report-table"] tbody tr')).toHaveLength(2);
    },
  );
});
