import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { metaField, queryMetaFixture } from '@testing/entity-page';
import type { ReportGroup } from './entity-reports';
import { SMTReportShapeComponent } from './smt-report-shape.component';

const META = queryMetaFixture('test.orders', [
  metaField('region', '', 'enum', { label: 'Регион', enumValues: ['north'] }),
  metaField('day', '', 'date', { label: 'Дата', ops: ['gte', 'lt'] }),
  metaField('at', '', 'instant', { label: 'Создано', ops: ['gte', 'lt'] }),
  metaField('hidden', '', 'date', { label: 'Без фильтра', ops: [] }),
]);

function render(groups: ReportGroup[]) {
  const fixture = TestBed.createComponent(SMTReportShapeComponent);
  fixture.componentRef.setInput('meta', META);
  fixture.componentRef.setInput('groupBy', groups);
  fixture.componentRef.setInput('ids', 's');
  fixture.detectChanges();
  return { fixture, component: fixture.componentInstance, host: fixture.nativeElement as HTMLElement };
}

describe('smt-report-shape (ADR-0044)', () => {
  it('makes a pivot only of two groupings and drops it when one goes', () => {
    const one = render([{ field: 'region' }]);
    one.component.setPivot(true);
    expect(one.component.pivot()).toBe(false);

    TestBed.resetTestingModule();
    const two = render([{ field: 'region' }, { field: 'day', trunc: 'month' }]);
    two.component.setPivot(true);
    expect(two.component.pivot()).toBe(true);
    two.fixture.componentRef.setInput('groupBy', [{ field: 'region' }]);
    TestBed.tick();
    expect(two.component.pivot()).toBe(false);
    two.component.setTotals(true);
    expect(two.component.totals()).toBe(true);
  });

  it('compares the periods of a filterable date that is not a grouping, a custom period with its range', () => {
    const { component, fixture, host } = render([{ field: 'region' }, { field: 'day', trunc: 'month' }]);
    expect(component.fieldOptions().map((option) => option.id)).toEqual(['at']);
    component.setPivot(true);
    component.setCompared(true);
    expect(component.compare()).toEqual({ field: 'at', period: 'month', against: 'previous' });
    expect(component.pivot()).toBe(false);
    component.change({ period: 'custom' });
    component.setRange({ from: '2026-01-01', to: '2026-01-31' });
    expect(component.compare()).toEqual({
      field: 'at',
      period: 'custom',
      against: 'previous',
      from: '2026-01-01',
      to: '2026-01-31',
    });
    expect(component.range()).toEqual({ from: '2026-01-01', to: '2026-01-31' });
    component.change({ period: 'ytd', against: 'year' });
    expect(component.compare()).toEqual({ field: 'at', period: 'ytd', against: 'year' });
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="report-compare"]')).not.toBeNull();
    fixture.componentRef.setInput('groupBy', [{ field: 'at', trunc: 'day' }]);
    TestBed.tick();
    expect(component.compare()).toBeNull();
    component.setCompared(false);
    expect(component.compare()).toBeNull();
  });

  it('changes nothing while locked', () => {
    const { component, fixture } = render([{ field: 'region' }, { field: 'day', trunc: 'month' }]);
    fixture.componentRef.setInput('locked', true);
    component.setPivot(true);
    component.setTotals(true);
    component.setCompared(true);
    expect([component.pivot(), component.totals(), component.compare()]).toEqual([false, false, null]);
  });
});
