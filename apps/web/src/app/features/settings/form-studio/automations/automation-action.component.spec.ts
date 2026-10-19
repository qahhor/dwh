import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FormMetaService } from '@core/services/form-meta.service';
import { ORDER_META } from '@testing/automations';
import { formField } from '@testing/form-meta';
import { AutomationActionComponent } from './automation-action.component';
import { AutomationsApi, type AutomationAction } from './automations.api';

const TASKS_META = {
  ...ORDER_META,
  code: 'studio.tasks',
  fields: [formField('ordernum', 'text', { required: true }), formField('note', 'text')],
  workflow: null,
};

/* One action of an automation (ADR-0046): its fields and formulas, a new record's form, a transition, a notice. */
describe('AutomationActionComponent', () => {
  const formMeta = { get: vi.fn((code: string) => of(code === 'studio.tasks' ? TASKS_META : ORDER_META)) };

  beforeEach(() => {
    formMeta.get.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: FormMetaService, useValue: formMeta },
        { provide: AutomationsApi, useValue: { check: vi.fn(() => of({ valid: true, type: 'bool' })) } },
      ],
    });
  });

  function render(action: AutomationAction) {
    const fixture = TestBed.createComponent(AutomationActionComponent);
    fixture.componentRef.setInput('action', action);
    fixture.componentRef.setInput('index', 1);
    fixture.componentRef.setInput('count', 3);
    fixture.componentRef.setInput('form', 'studio.orders');
    fixture.componentRef.setInput('meta', ORDER_META);
    fixture.componentRef.setInput('trigger', 'saved');
    fixture.componentRef.setInput('forms', [{ code: 'studio.tasks', title: 'Задачи' }]);
    fixture.componentRef.setInput('channels', ['in_app', 'email']);
    fixture.componentRef.setInput('errors', { 'actions[1].values[0].field': 'Нет поля' });
    fixture.detectChanges();
    const changes: AutomationAction[] = [];
    fixture.componentInstance.changed.subscribe((changed) => changes.push(changed));
    return { fixture, component: fixture.componentInstance, changes };
  }

  it('writes fields with formulas over the record', () => {
    const { component, changes, fixture } = render({
      type: 'set_field',
      values: [{ field: 'review', formula: 'true' }],
    });
    expect(component.fieldOptions().map((option) => option.id)).toEqual(['client', 'discount', 'manager', 'review']);
    expect(component.nameOptions().map((option) => option.id)).toContain('old.discount');
    expect(component.error('.values[0].field')).toBe('Нет поля');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Нет поля');
    component.setValue(0, 'formula', 'discount > 20');
    component.addValue();
    component.removeValue(0);
    expect(changes[0].values).toEqual([{ field: 'review', formula: 'discount > 20' }]);
    expect(changes[1].values).toHaveLength(2);
    expect(changes[2].values).toEqual([{ field: '', formula: '' }]);
  });

  it('creates a record in the form chosen, with the fields of that form', () => {
    const { component, changes, fixture } = render({ type: 'create_record', form: 'studio.tasks', values: [] });
    fixture.detectChanges();
    expect(formMeta.get).toHaveBeenCalledWith('studio.tasks');
    expect(component.fieldOptions().map((option) => option.id)).toEqual(['ordernum', 'note']);
    component.setTarget(null);
    expect(changes[0]).toEqual({ type: 'create_record', form: undefined, values: [{ field: '', formula: '' }] });
  });

  it('runs a transition of the form with a comment', () => {
    const { component, changes } = render({ type: 'transition' });
    expect(component.transitionOptions().map((option) => option.id)).toEqual(['submit', 'approve']);
    component.change({ transition: 'approve', comment: 'Готово' });
    expect(changes[0]).toEqual({ type: 'transition', transition: 'approve', comment: 'Готово' });
  });

  it('notifies on the channels chosen, the people named, with texts in each language', () => {
    const { component, changes } = render({
      type: 'notify',
      channels: ['in_app'],
      recipients: { rights: [{ form: 'studio.orders', action: 'approve' }] },
      title: { ru: 'Заказ' },
      body: { ru: 'Текст', en: 'Text' },
    });
    expect(component.channelOptions().map((option) => option.id)).toEqual(['in_app', 'email']);
    expect(component.userFieldOptions().map((option) => option.id)).toEqual(['manager']);
    expect(component.rightOptions().map((option) => option.id)).toEqual([
      'studio.orders.update',
      'studio.orders.submit',
      'studio.orders.approve',
    ]);
    expect(component.rights()).toEqual(['studio.orders.approve']);
    expect(component.hasChannel('in_app')).toBe(true);
    expect(component.placeholders()).toContain('{docNumber}');
    component.toggleChannel('email', true);
    component.toggleChannel('in_app', false);
    component.setRecipients('fields', ['manager']);
    component.setRecipients('users', [7]);
    component.setRecipients('author', true);
    component.setRecipients('rights', ['studio.orders.submit']);
    component.setText('title', 'uz', 'Buyurtma');
    component.setText('body', 'en', '');
    component.setText('title', 'ru', 'Новый');
    expect(changes[0].channels).toEqual(['in_app', 'email']);
    expect(changes[1].channels).toEqual(['email']);
    expect(changes[2].recipients?.fields).toEqual(['manager']);
    expect(changes[3].recipients?.users).toEqual([7]);
    expect(changes[4].recipients?.author).toBe(true);
    expect(changes[5].recipients?.rights).toEqual([{ form: 'studio.orders', action: 'submit' }]);
    expect(changes[6].title).toEqual({ ru: 'Заказ', uz: 'Buyurtma' });
    expect(changes[7].body).toEqual({ ru: 'Текст' });
    expect(changes[8].title).toEqual({ ru: 'Новый', uz: 'Buyurtma' });
    expect(component.text('body', 'en')).toBe('');
    expect(component.text('body', 'ru')).toBe('Текст');
  });
});
