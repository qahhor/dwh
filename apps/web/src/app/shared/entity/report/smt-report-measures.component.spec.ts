import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { metaField, queryMetaFixture } from '@testing/entity-page';
import type { ReportMeasure } from './entity-reports';
import { SMTReportMeasuresComponent } from './smt-report-measures.component';

const META = queryMetaFixture('test.orders', [
  metaField('client', '', 'number', {
    label: 'Клиент',
    ref: { path: '/c', labelField: 'name', keyField: 'id', paged: false },
  }),
  metaField('note', '', 'text', { label: 'Заметка' }),
  metaField('total', '', 'number', { label: 'Сумма' }),
  metaField('extra', '', 'object', { label: 'Данные' }),
]);

function render(measures: ReportMeasure[], pivot = false, compared = false) {
  const fixture = TestBed.createComponent(SMTReportMeasuresComponent);
  fixture.componentRef.setInput('measures', measures);
  fixture.componentRef.setInput('meta', META);
  fixture.componentRef.setInput('pivot', pivot);
  fixture.componentRef.setInput('compared', compared);
  fixture.componentRef.setInput('ids', 'm');
  fixture.detectChanges();
  return { fixture, component: fixture.componentInstance, host: fixture.nativeElement as HTMLElement };
}

describe('smt-report-measures (ADR-0044)', () => {
  it('counts the different values of a plain field, a choice or a reference', () => {
    const { component } = render([{ op: 'count' }]);
    expect(component.distinctOptions().map((option) => option.id)).toEqual(['client', 'note', 'total']);
    component.setOp(0, 'count_distinct');
    expect(component.measures()).toEqual([{ op: 'count_distinct', field: 'client' }]);
    component.setField(0, 'note');
    component.setOp(0, 'sum');
    expect(component.measures()).toEqual([{ op: 'sum', field: 'total' }]);
  });

  it('offers a share of the total to a count or a sum, of the row and column only in a pivot', () => {
    const flat = render([{ op: 'sum', field: 'total' }]);
    expect(flat.component.shareOptions().map((option) => option.id)).toEqual(['total']);
    expect(flat.component.shared({ op: 'avg', field: 'total' })).toBe(false);
    flat.component.setShare(0, 'total');
    expect(flat.component.measures()).toEqual([{ op: 'sum', field: 'total', share: 'total' }]);
    expect(flat.host.querySelector('[data-testid="report-share-0"]')).not.toBeNull();
    flat.component.setShare(0, null);
    expect(flat.component.measures()).toEqual([{ op: 'sum', field: 'total' }]);

    TestBed.resetTestingModule();
    const pivot = render([{ op: 'count', share: 'row' }], true);
    expect(pivot.component.shareOptions().map((option) => option.id)).toEqual(['total', 'row', 'column']);
    expect(pivot.component.measures()).toEqual([{ op: 'count', share: 'row' }]);
  });

  it('drops a share a flat report or a comparison cannot have', () => {
    const { component, fixture } = render([{ op: 'count', share: 'column' }]);
    TestBed.tick();
    expect(component.measures()).toEqual([{ op: 'count' }]);
    component.measures.set([{ op: 'count', share: 'total' }]);
    fixture.componentRef.setInput('compared', true);
    TestBed.tick();
    expect(component.measures()).toEqual([{ op: 'count' }]);
    expect(component.shared({ op: 'count' })).toBe(false);
  });

  it('adds and removes measures and changes nothing while locked', () => {
    const { component, fixture } = render([{ op: 'count' }]);
    component.add();
    expect(component.measures()).toEqual([{ op: 'count' }, { op: 'sum', field: 'total' }]);
    component.remove(0);
    expect(component.measures()).toEqual([{ op: 'sum', field: 'total' }]);
    fixture.componentRef.setInput('locked', true);
    component.add();
    component.setOp(0, 'count');
    component.setShare(0, 'total');
    component.setField(0, 'client');
    component.remove(0);
    expect(component.measures()).toEqual([{ op: 'sum', field: 'total' }]);
  });

  it('offers the measures a dataset computes by formulas, never to count or order by', () => {
    TestBed.resetTestingModule();
    const fixture = TestBed.createComponent(SMTReportMeasuresComponent);
    fixture.componentRef.setInput('measures', [{ op: 'count' }]);
    fixture.componentRef.setInput('meta', {
      ...META,
      fields: [...META.fields, metaField('avgLine', '', 'number', { label: 'Средняя', ops: [], format: 'formula' })],
    });
    fixture.componentRef.setInput('ids', 'm');
    fixture.detectChanges();
    const component = fixture.componentInstance;
    expect(component.opOptions().find((option) => option.id === 'formula')?.disabled).toBe(false);
    expect(component.distinctOptions().map((option) => option.id)).not.toContain('avgLine');
    component.setOp(0, 'formula');
    expect(component.measures()).toEqual([{ op: 'formula', field: 'avgLine' }]);
    expect(component.shared({ op: 'formula', field: 'avgLine' })).toBe(false);
  });
});
