import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { CustomField } from '../../core/models/custom-field.models';
import { ApiService } from '../../core/services/api.service';
import { UiCustomFieldsComponent, selectOptions } from './ui-custom-fields.component';

const baseField: Omit<CustomField, 'id' | 'code' | 'name' | 'fieldType'> = {
  entityType: 'TASK',
  isRequired: false,
  orderNo: 0,
  createdAt: '2026-08-30T00:00:00Z'
};

describe('UiCustomFieldsComponent', () => {
  async function render(fields: CustomField[], values: Record<string, unknown> = {}) {
    const api = { get: vi.fn((path: string) => of(path === '/iam/users'
      ? { items: [{ id: 42, name: 'Анна Смирнова', login: 'asmirnova' }], nextCursor: null, hasMore: false }
      : { id: 42, name: 'Анна Смирнова', login: 'asmirnova' })) };
    await TestBed.configureTestingModule({ imports: [UiCustomFieldsComponent], providers: [{ provide: ApiService, useValue: api }] }).compileComponents();
    const fixture = TestBed.createComponent(UiCustomFieldsComponent);
    fixture.componentRef.setInput('fields', fields);
    fixture.componentRef.setInput('values', values);
    let emitted: Record<string, unknown> | undefined;
    fixture.componentInstance.valuesChange.subscribe(value => emitted = value);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, api, emitted: () => emitted };
  }

  it('names every field by its label and marks the required ones', async () => {
    const { fixture } = await render([
      { ...baseField, id: 1, code: 'inn', name: 'ИНН', fieldType: 'string', isRequired: true },
      { ...baseField, id: 2, code: 'approved', name: 'Согласовано', fieldType: 'boolean' }
    ]);
    const labels = Array.from(fixture.nativeElement.querySelectorAll('smt-control label')) as HTMLLabelElement[];
    expect(labels.map(label => label.textContent?.replace('*', '').trim())).toEqual(['ИНН', 'Согласовано']);
    const inn = fixture.nativeElement.querySelector('#' + labels[0].htmlFor) as HTMLInputElement;
    expect(inn.required).toBe(true);
    expect(fixture.nativeElement.querySelector('#' + labels[1].htmlFor)?.getAttribute('role')).toBe('switch');
  });

  it('emits a changed value with the others kept', async () => {
    const { fixture, emitted } = await render([{ ...baseField, id: 1, code: 'inn', name: 'ИНН', fieldType: 'string' }], { other: 1 });
    const inn = fixture.nativeElement.querySelector('input') as HTMLInputElement;
    inn.value = '123';
    inn.dispatchEvent(new Event('input'));
    expect(emitted()).toEqual({ other: 1, inn: '123' });
  });

  it('reads list choices from plain values and from value-label pairs', () => {
    const field = (optionsJson: string): CustomField => ({ ...baseField, id: 3, code: 'region', name: 'Регион', fieldType: 'select', optionsJson });
    expect(selectOptions(field('["Ташкент","Самарканд"]'))).toEqual([{ id: 'Ташкент', label: 'Ташкент' }, { id: 'Самарканд', label: 'Самарканд' }]);
    expect(selectOptions(field('[{"value":1,"label":"Один"},{"value":{"x":1}}]'))).toEqual([{ id: 1, label: 'Один' }]);
    expect(selectOptions(field('not json'))).toEqual([]);
  });

  it('searches people on the server for a user field and names the chosen one', async () => {
    const { fixture, api } = await render([{ ...baseField, id: 4, code: 'curator_id', name: 'Куратор', fieldType: 'user_ref' }], { curator_id: 42 });
    const trigger = fixture.nativeElement.querySelector('[role="combobox"]') as HTMLElement;
    expect(api.get).toHaveBeenCalledWith('/iam/users/42', undefined, { notifyError: false });
    expect(trigger.textContent).toContain('Анна Смирнова');
  });
});
