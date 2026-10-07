import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ListViewState, type ListViewsApi } from '@shared/list-views/list-views';
import { metaField, queryMetaFixture } from '@testing/entity-page';
import { translateTest } from '@testing/i18n-test.stub';
import { EntityReportsApi, type ReportResult } from './entity-reports';
import { SMTEntityReportBuilderComponent } from './smt-entity-report-builder.component';

const LIST = queryMetaFixture('test.orders', [
  metaField('number', '', 'text', { label: 'Номер' }),
  metaField('status', '', 'enum', { label: 'Статус', enumValues: ['draft', 'posted'] }),
  metaField('orderDate', '', 'date', { label: 'Дата' }),
  metaField('total', '', 'number', { label: 'Сумма', format: 'money' }),
]);

const COUNT: ReportResult = {
  groups: [],
  measures: [{ op: 'count' }],
  rows: [{ groups: [], values: [3] }],
  truncated: false,
} as ReportResult;

/* The report tab of an entity list (ADR-0032 10.2): groupings, measures and a chart, run as they change. */
describe('SMTEntityReportBuilderComponent', () => {
  const reports = { run: vi.fn(() => of(COUNT)), list: () => of([]) };
  let views: ListViewState;

  beforeEach(() => {
    reports.run.mockReset();
    reports.run.mockReturnValue(of(COUNT));
    const api = { list: () => of([]), create: vi.fn(), update: vi.fn(), remove: vi.fn() } as unknown as ListViewsApi;
    views = new ListViewState('test.orders', api, { defaultSort: () => null, onApply: () => undefined });
    TestBed.configureTestingModule({ providers: [{ provide: EntityReportsApi, useValue: reports }] });
  });

  async function render(meta = LIST) {
    const fixture = TestBed.createComponent(SMTEntityReportBuilderComponent);
    fixture.componentRef.setInput('code', 'test.orders');
    fixture.componentRef.setInput('meta', meta);
    fixture.componentRef.setInput('views', views);
    fixture.componentRef.setInput('title', 'Заказы');
    fixture.detectChanges();
    await afterDebounce(fixture);
    return { fixture, builder: fixture.componentInstance, host: fixture.nativeElement as HTMLElement };
  }

  async function afterDebounce(fixture: { detectChanges(): void }) {
    fixture.detectChanges();
    await new Promise((resolve) => setTimeout(resolve, 300));
    fixture.detectChanges();
  }

  it('runs the count of the whole list at first and shows one figure', async () => {
    const { builder } = await render();

    expect(reports.run).toHaveBeenCalledTimes(1);
    expect(reports.run).toHaveBeenCalledWith(
      'test.orders',
      expect.objectContaining({ code: 'test.orders', groupBy: [], measures: [{ op: 'count' }] }),
    );
    expect(builder.run()).toEqual({ result: COUNT, error: '', loading: false });
    expect(builder.effectiveChart()).toBe('kpi');
    expect(builder.caption()).toBe(translateTest('ui.report.caption', { list: 'Заказы' }));
  });

  it('offers groupings of choices and dates, measures of numbers, and one more empty grouping slot', async () => {
    const { builder } = await render();

    expect(builder.groupOptions().map((option) => option.id)).toEqual(['status', 'orderDate']);
    expect(builder.measureOptions().map((option) => option.id)).toEqual(['total']);
    expect(builder.groupSlots()).toEqual([null]);

    builder.setGroup(0, 'orderDate');
    builder.setGroup(1, 'status');
    expect(builder.groupBy()).toEqual([{ field: 'orderDate', trunc: 'month' }, { field: 'status' }]);
    expect(builder.groupSlots()).toHaveLength(2);
    expect(builder.effectiveChart()).toBe('bar');

    builder.setTrunc(0, 'year');
    expect(builder.groupBy()[0]).toEqual({ field: 'orderDate', trunc: 'year' });
    builder.setGroup(1, 'orderDate');
    expect(builder.groupBy()).toEqual([{ field: 'orderDate', trunc: 'year' }]);
    builder.setGroup(0, null);
    expect(builder.groupBy()).toEqual([]);
  });

  it('measures a number field and keeps at most four measures', async () => {
    const { builder } = await render();

    builder.setOp(0, 'sum');
    expect(builder.measures()).toEqual([{ op: 'sum', field: 'total' }]);
    builder.setOp(0, null);
    expect(builder.measures()).toEqual([{ op: 'count' }]);

    builder.addMeasure();
    builder.addMeasure();
    builder.addMeasure();
    expect(builder.measures()).toHaveLength(builder.maxMeasures);
    builder.setMeasureField(1, null);
    expect(builder.measures()[1]).toEqual({ op: 'sum', field: 'total' });
    builder.removeMeasure(1);
    expect(builder.measures()).toHaveLength(3);
  });

  it('keeps only the count for a list without a number field', async () => {
    const { builder } = await render(queryMetaFixture('test.orders', [metaField('status', '', 'enum')]));
    expect(builder.opOptions().find((option) => option.id === 'sum')?.disabled).toBe(true);

    builder.measures.set([{ op: 'sum', field: 'missing' }]);
    TestBed.tick();
    expect(builder.measures()).toEqual([{ op: 'count' }]);
  });

  it('shows why the server refused a report, or its own text', async () => {
    reports.run.mockReturnValue(throwError(() => ({ detail: 'Слишком много групп' })));
    const { fixture, builder, host } = await render();
    expect(host.querySelector('[data-testid="report-error"]')?.textContent?.trim()).toBe('Слишком много групп');

    reports.run.mockReturnValue(throwError(() => ({})));
    builder.setGroup(0, 'status');
    await afterDebounce(fixture);
    expect(builder.run().error).toBe(translateTest('ui.report.failed'));
  });

  it('opens a saved report with its filter in the list filter bar', async () => {
    const { builder } = await render();

    builder.open({
      groupBy: [{ field: 'status' }],
      measures: [],
      filter: [{ field: 'status', op: 'eq', value: 'draft' }],
      chart: 'table',
    });

    expect(builder.groupBy()).toEqual([{ field: 'status' }]);
    expect(builder.measures()).toEqual([{ op: 'count' }]);
    expect(builder.chart()).toBe('table');
    expect(views.filter()).toEqual([{ field: 'status', op: 'eq', value: 'draft' }]);
    expect(builder.state().chart).toBe('table');
  });

  it('offers the saved reports only to a person who may save them', async () => {
    const { fixture, host } = await render();
    expect(host.querySelector('smt-entity-report-saved')).toBeNull();

    fixture.componentRef.setInput('canSave', true);
    fixture.detectChanges();
    expect(host.querySelector('smt-entity-report-saved')).not.toBeNull();
  });
});
