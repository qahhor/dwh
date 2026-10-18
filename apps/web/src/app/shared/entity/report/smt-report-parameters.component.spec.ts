import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { metaField, queryMetaFixture } from '@testing/entity-page';
import { translateTest } from '@testing/i18n-test.stub';
import type { ParameterValue, ReportParameter } from './report-analytics';
import { SMTReportParametersComponent } from './smt-report-parameters.component';

const META = queryMetaFixture('test.orders', [
  metaField('day', '', 'date', { label: 'Дата', ops: ['between'] }),
  metaField('region', '', 'enum', {
    label: 'Регион',
    enumValues: ['north', 'south'],
    enumLabels: { north: 'Север', south: 'Юг' },
  }),
  metaField('client', '', 'number', {
    label: 'Клиент',
    ref: { path: '/c', labelField: 'name', keyField: 'id', paged: false },
  }),
  metaField('paid', '', 'boolean', { label: 'Оплачен' }),
  metaField('note', '', 'text', { label: 'Заметка' }),
]);

const PARAMETERS: ReportParameter[] = [
  { key: 'period', field: 'day', required: true, labels: { ru: 'Период', uz: 'Davr', en: 'Period' } },
  { key: 'region', field: 'region', required: false, default: ['north'] },
  { key: 'client', field: 'client', required: false },
  { key: 'paid', field: 'paid', required: false },
  { key: 'note', field: 'note', required: false },
];

function render(parameters = PARAMETERS) {
  TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
  const fixture = TestBed.createComponent(SMTReportParametersComponent);
  fixture.componentRef.setInput('parameters', parameters);
  fixture.componentRef.setInput('meta', META);
  const applied: Record<string, ParameterValue>[] = [];
  fixture.componentInstance.applied.subscribe((values) => applied.push(values));
  fixture.detectChanges();
  return { fixture, component: fixture.componentInstance, host: fixture.nativeElement as HTMLElement, applied };
}

describe('smt-report-parameters (ADR-0044)', () => {
  it('starts from the defaults and waits for a required parameter before running', () => {
    const { component, host, applied, fixture } = render();
    expect(component.rows().map((row) => [row.label, row.kind])).toEqual([
      ['Период', 'period'],
      ['Регион', 'choice'],
      ['Клиент', 'reference'],
      ['Оплачен', 'flag'],
      ['Заметка', null],
    ]);
    expect(component.list('region')).toEqual(['north']);
    expect(component.choiceOptions(META.fields[1]).map((option) => option.label)).toEqual(['Север', 'Юг']);
    expect(host.querySelector('[data-testid="report-parameters-missing"]')?.textContent).toContain('Период');
    component.apply();
    expect(applied).toEqual([]);

    component.setPreset('period', 'last_month');
    component.setFlag('paid', 'true');
    component.setList('client', [7]);
    fixture.detectChanges();
    expect(component.preset('period')).toBe('last_month');
    expect(component.first('client')).toBe(7);
    expect(component.flag('paid')).toBe('true');
    expect(host.textContent).toContain(translateTest('ui.report.parameter_unavailable', { name: 'Заметка' }));
    component.apply();
    expect(applied).toEqual([{ region: ['north'], period: { preset: 'last_month' }, paid: true, client: [7] }]);
  });

  it('takes a custom period by its two days and clears an emptied value', () => {
    const { component, applied } = render();
    component.setPreset('period', 'custom');
    expect(component.preset('period')).toBe('custom');
    expect(component.missing()).toEqual(['Период']);
    component.setRange('period', { from: '2026-10-01', to: '2026-10-31' });
    expect(component.range('period')).toEqual({ from: '2026-10-01', to: '2026-10-31' });
    component.setList('region', []);
    component.setFlag('paid', null);
    component.setPreset('client', null);
    component.apply();
    expect(applied).toEqual([{ period: { from: '2026-10-01', to: '2026-10-31' } }]);
  });

  it('inside a mailing form starts from its values, has no form of its own and tells each change (ADR-0045)', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const fixture = TestBed.createComponent(SMTReportParametersComponent);
    fixture.componentRef.setInput('parameters', PARAMETERS);
    fixture.componentRef.setInput('meta', META);
    fixture.componentRef.setInput('embedded', true);
    fixture.componentRef.setInput('initial', { period: { preset: 'last_month' } });
    const changed: Record<string, ParameterValue>[] = [];
    fixture.componentInstance.changed.subscribe((values) => changed.push(values));
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('form')).toBeNull();
    expect(host.querySelector('[data-testid="report-parameters-apply"]')).toBeNull();
    expect(fixture.componentInstance.preset('period')).toBe('last_month');
    expect(fixture.componentInstance.list('region')).toEqual([]);
    fixture.componentInstance.setList('region', ['south']);
    fixture.componentInstance.setFlag('paid', null);
    expect(changed).toEqual([
      { period: { preset: 'last_month' }, region: ['south'] },
      { period: { preset: 'last_month' }, region: ['south'] },
    ]);
  });
});
