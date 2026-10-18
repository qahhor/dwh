import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ListViewState, type ListViewsApi } from '@shared/list-views/list-views';
import { metaField, queryMetaFixture } from '@testing/entity-page';
import { translateTest } from '@testing/i18n-test.stub';
import { EntityReportsApi, type ReportResult } from './entity-reports';
import { SMTEntityReportBuilderComponent } from './smt-entity-report-builder.component';
import { SMTReportMeasuresComponent } from './smt-report-measures.component';

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
  const reports = { run: vi.fn(() => of(COUNT)), saved: vi.fn(() => of(COUNT)), list: () => of([]) };
  let views: ListViewState;

  beforeEach(() => {
    reports.saved.mockReset();
    reports.saved.mockReturnValue(of(COUNT));
    reports.run.mockReset();
    reports.run.mockReturnValue(of(COUNT));
    const api = { list: () => of([]), create: vi.fn(), update: vi.fn(), remove: vi.fn() } as unknown as ListViewsApi;
    views = new ListViewState('test.orders', api, { defaultSort: () => null, onApply: () => undefined });
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: EntityReportsApi, useValue: reports },
      ],
    });
  });

  let current: ComponentFixture<SMTEntityReportBuilderComponent> | null = null;

  async function render(meta = LIST) {
    const fixture = TestBed.createComponent(SMTEntityReportBuilderComponent);
    current = fixture;
    fixture.componentRef.setInput('code', 'test.orders');
    fixture.componentRef.setInput('meta', meta);
    fixture.componentRef.setInput('views', views);
    fixture.componentRef.setInput('title', 'Заказы');
    fixture.detectChanges();
    await afterDebounce(fixture);
    return { fixture, builder: fixture.componentInstance, host: fixture.nativeElement as HTMLElement };
  }

  /** The measures of the builder on screen. */
  function measuresOf(): SMTReportMeasuresComponent {
    return current!.debugElement.query(By.directive(SMTReportMeasuresComponent)).componentInstance;
  }

  async function afterDebounce(fixture: { detectChanges(): void }) {
    fixture.detectChanges();
    await new Promise((resolve) => setTimeout(resolve, 300));
    fixture.detectChanges();
  }

  it('runs a selected saved definition by ID and never falls back to an ad hoc clone on denial', async () => {
    const { fixture, builder } = await render();
    reports.run.mockClear();
    reports.saved.mockReturnValue(throwError(() => ({ status: 403, detail: 'Revoked' })));
    builder.openSaved({
      id: 9,
      name: 'Shared report',
      kind: 'report',
      state: { groupBy: [], measures: [{ op: 'count' }], filter: [], chart: 'kpi' },
      lockVersion: 1,
    });
    await afterDebounce(fixture);
    expect(reports.saved).toHaveBeenCalledWith('test.orders', 9);
    expect(reports.run).not.toHaveBeenCalled();
    expect(builder.run().error).toBe('Revoked');
    expect(builder.run().result).toBeNull();
  });

  it('requires an explicit detach before editing a saved definition query', async () => {
    const { fixture, builder } = await render();
    builder.openSaved({
      id: 9,
      name: 'Shared report',
      kind: 'report',
      state: { groupBy: [], measures: [{ op: 'count' }], filter: [], chart: 'kpi' },
      lockVersion: 1,
    });
    await afterDebounce(fixture);
    builder.setGroup(0, 'status');
    expect(builder.groupBy()).toEqual([]);
    builder.detachSource();
    builder.setGroup(0, 'status');
    await afterDebounce(fixture);
    expect(builder.source()).toBeNull();
    expect(builder.groupBy()).toEqual([{ field: 'status' }]);
    expect(reports.run).toHaveBeenLastCalledWith(
      'test.orders',
      expect.objectContaining({ groupBy: [{ field: 'status' }] }),
    );
  });

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
    expect(
      measuresOf()
        .measureOptions()
        .map((option) => option.id),
    ).toEqual(['total']);
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
    const measures = measuresOf();

    measures.setOp(0, 'sum');
    expect(builder.measures()).toEqual([{ op: 'sum', field: 'total' }]);
    measures.setOp(0, null);
    expect(builder.measures()).toEqual([{ op: 'count' }]);

    measures.add();
    measures.add();
    measures.add();
    expect(builder.measures()).toHaveLength(measures.maxMeasures);
    measures.setField(1, null);
    expect(builder.measures()[1]).toEqual({ op: 'sum', field: 'total' });
    measures.remove(1);
    expect(builder.measures()).toHaveLength(3);
  });

  it('keeps only the count for a list without a number field', async () => {
    const { builder } = await render(queryMetaFixture('test.orders', [metaField('status', '', 'enum')]));
    expect(
      measuresOf()
        .opOptions()
        .find((option) => option.id === 'sum')?.disabled,
    ).toBe(true);

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

  it('runs the analytic shape it is asked for and keeps it in the saved state (ADR-0044)', async () => {
    const { fixture, builder } = await render();
    builder.setGroup(0, 'status');
    builder.setGroup(1, 'orderDate');
    builder.pivot.set(true);
    await afterDebounce(fixture);
    expect(reports.run).toHaveBeenLastCalledWith('test.orders', expect.objectContaining({ pivot: true }));
    expect(builder.effectiveChart()).toBe('table');
    builder.pivot.set(false);
    builder.setGroup(1, null);
    builder.totals.set(true);
    builder.compare.set({ field: 'orderDate', period: 'custom' });
    expect(builder.effectiveCompare()).toBeNull();
    builder.compare.set({ field: 'orderDate', period: 'month', against: 'previous' });
    builder.parameters.set([{ key: 'status', field: 'status', required: false }]);
    expect(builder.state()).toEqual(
      expect.objectContaining({
        totals: true,
        compare: { field: 'orderDate', period: 'month', against: 'previous' },
        parameters: [{ key: 'status', field: 'status', required: false }],
      }),
    );
  });

  it('waits for a required parameter of a saved report and runs it with the values applied', async () => {
    const { fixture, builder, host } = await render();
    reports.saved.mockClear();
    builder.openSaved({
      id: 12,
      name: 'С параметрами',
      kind: 'report',
      state: {
        groupBy: [],
        measures: [{ op: 'count' }],
        filter: [],
        chart: 'kpi',
        parameters: [{ key: 'day', field: 'orderDate', required: true }],
      },
      lockVersion: 1,
    });
    await afterDebounce(fixture);
    expect(reports.saved).not.toHaveBeenCalled();
    expect(host.querySelector('smt-report-parameters')).not.toBeNull();
    builder.applyParameters({ day: { preset: 'this_month' } });
    await afterDebounce(fixture);
    expect(reports.saved).toHaveBeenCalledWith('test.orders', 12, { day: { preset: 'this_month' } });
    expect(builder.exportSource()).toEqual({
      kind: 'saved',
      code: 'test.orders',
      id: 12,
      parameters: { day: { preset: 'this_month' } },
    });
  });

  it('runs the report a mailing links with the values of its parameters the link names (ADR-0045)', async () => {
    const { fixture, builder } = await render();
    fixture.componentRef.setInput('openId', 12);
    fixture.componentRef.setInput('openParameters', { day: { preset: 'last_month' } });
    reports.saved.mockClear();
    const linked = {
      id: 12,
      name: 'С параметрами',
      kind: 'report' as const,
      state: {
        groupBy: [],
        measures: [{ op: 'count' as const }],
        filter: [],
        chart: 'kpi' as const,
        parameters: [{ key: 'day', field: 'orderDate', required: true }],
      },
      lockVersion: 1,
    };
    builder.openSaved(linked);
    await afterDebounce(fixture);
    expect(reports.saved).toHaveBeenCalledWith('test.orders', 12, { day: { preset: 'last_month' } });
    reports.saved.mockClear();
    builder.openSaved({ ...linked, id: 13 });
    await afterDebounce(fixture);
    expect(reports.saved).not.toHaveBeenCalled();
  });
});
