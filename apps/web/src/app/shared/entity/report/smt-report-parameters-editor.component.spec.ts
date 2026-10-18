import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { metaField, queryMetaFixture } from '@testing/entity-page';
import type { ReportParameter } from './report-analytics';
import { parameterKey } from './report-analytics';
import { SMTReportParametersEditorComponent } from './smt-report-parameters-editor.component';

const META = queryMetaFixture('test.orders', [
  metaField('day', '', 'date', { label: 'Дата', ops: ['between'] }),
  metaField('region', '', 'enum', { label: 'Регион', enumValues: ['north', 'south'] }),
  metaField('note', '', 'text', { label: 'Заметка' }),
  metaField('at', '', 'instant', { label: 'Создано', ops: ['gte'] }),
]);

function render(parameters: ReportParameter[] = []) {
  const fixture = TestBed.createComponent(SMTReportParametersEditorComponent);
  fixture.componentRef.setInput('parameters', parameters);
  fixture.componentRef.setInput('meta', META);
  fixture.componentRef.setInput('ids', 'p');
  fixture.detectChanges();
  return { fixture, component: fixture.componentInstance, host: fixture.nativeElement as HTMLElement };
}

describe('smt-report-parameters-editor (ADR-0044)', () => {
  it('declares parameters of the fields a parameter narrows, with a key, a requirement and a default', () => {
    const { component, fixture, host } = render();
    expect(component.fieldOptions().map((option) => option.id)).toEqual(['day', 'region', 'at']);
    component.add();
    component.add();
    expect(component.parameters()).toEqual([
      { key: 'day', field: 'day', required: false },
      { key: 'region', field: 'region', required: false },
    ]);
    component.setRequired(0, true);
    component.setPreset(0, 'this_month');
    component.setValues(1, ['south']);
    expect(component.preset(component.parameters()[0])).toBe('this_month');
    expect(component.values(component.parameters()[1])).toEqual(['south']);
    expect(component.choiceOptions(component.parameters()[1]).map((option) => option.id)).toEqual(['north', 'south']);
    component.setField(1, 'at');
    expect(component.parameters()[1]).toEqual({ key: 'at', field: 'at', required: false });
    component.setPreset(0, null);
    expect(component.parameters()[0]).toEqual({ key: 'day', field: 'day', required: true });
    fixture.detectChanges();
    expect(host.querySelectorAll('[data-testid^="report-parameter-field-"]')).toHaveLength(2);
    component.remove(1);
    expect(component.parameters()).toHaveLength(1);
  });

  it('drops a parameter on the field of the comparison and changes nothing while locked', () => {
    const { component, fixture } = render([{ key: 'day', field: 'day', required: true }]);
    fixture.componentRef.setInput('compareField', 'day');
    TestBed.tick();
    expect(component.parameters()).toEqual([]);
    fixture.componentRef.setInput('locked', true);
    component.add();
    expect(component.parameters()).toEqual([]);
  });

  it('makes a key of the field unique among the keys taken', () => {
    expect(parameterKey('region', [])).toBe('region');
    expect(parameterKey('region', ['region'])).toBe('region2');
    expect(parameterKey('1x-y', [])).toBe('xy');
  });
});
