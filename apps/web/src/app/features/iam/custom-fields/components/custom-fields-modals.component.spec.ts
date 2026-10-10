import { Component, inject, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { form } from '@angular/forms/signals';
import { describe, expect, it } from 'vitest';
import { markSMTFormFieldsTouched } from '@shared/ui-kit/forms/form-control-validation';
import { ProblemFieldErrors } from '@shared/ui/problem-fields';
import { tickInZone } from '@shared/ui-kit/testing/zone-tick';
import { inScreen } from '@testing/in-screen';
import { CustomField, CustomFieldFormData } from '../custom-fields.models';
import { CustomFieldsFormService } from '../services/custom-fields-form.service';
import { CustomFieldsModalsComponent } from './custom-fields-modals.component';

const blank = (): CustomFieldFormData => ({
  entityType: 'USER',
  code: '',
  name: '',
  fieldType: 'string',
  isRequired: false,
  defaultValue: '',
  orderNo: 10,
  optionsText: '',
});

@Component({
  imports: [CustomFieldsModalsComponent],
  template: `
    <app-custom-fields-modals
      [showModal]="open()"
      [editingField]="editing()"
      [fieldForm]="fieldForm"
      [serverErrors]="errors()"
      [saving]="saving()"
      (closeModal)="closes = closes + 1"
      (saveField)="save()"
      (codeInput)="codes = codes + 1"
    />
  `,
})
class Host {
  readonly open = signal(true);
  readonly editing = signal<CustomField | null>(null);
  readonly saving = signal(false);
  readonly errors = signal<ProblemFieldErrors>({ fields: {}, other: [] });
  readonly data = signal<CustomFieldFormData>(blank());
  readonly fieldForm = form(
    this.data,
    inject(CustomFieldsFormService).schema(() => !!this.editing()),
  );
  closes = 0;
  codes = 0;
  saves = 0;

  save(): void {
    this.saves++;
    markSMTFormFieldsTouched(this.fieldForm);
  }
}

describe('CustomFieldsModalsComponent', () => {
  const budget: CustomField = {
    id: 8,
    entityType: 'TASK',
    code: 'budget',
    name: 'Бюджет',
    fieldType: 'number',
    isRequired: false,
    orderNo: 10,
    createdAt: '2026-08-30T00:00:00Z',
  };

  async function setup(change: (host: Host) => void = () => {}) {
    const fixture = TestBed.createComponent(Host);
    const host = fixture.componentInstance;
    change(host);
    document.body.appendChild(fixture.nativeElement);
    const settle = async () => {
      tickInZone();
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    await settle();
    const screen = inScreen(fixture.nativeElement);
    const submit = () => screen.querySelector('[data-testid="form-submit"]') as HTMLButtonElement;
    const cancel = () => screen.querySelector('[data-testid="form-cancel"]') as HTMLButtonElement;
    const errors = () =>
      Array.from(screen.querySelectorAll('[role="dialog"] .smt-control__error')).map(
        (node) => (node as HTMLElement).textContent?.trim() ?? '',
      );
    return { fixture, host, screen, submit, cancel, errors, settle };
  }

  it('shows nothing until the page opens it', async () => {
    const { screen } = await setup((host) => host.open.set(false));

    expect(screen.querySelector('.smt-modal')).toBeNull();
  });

  it('creates a field with its entity and type chosen from labelled pickers, required fields marked', async () => {
    const { screen, submit } = await setup();

    expect(screen.querySelector('.smt-modal__title').textContent).toBe('Новое динамическое поле');
    for (const id of ['custom-field-entity', 'custom-field-type']) {
      expect(screen.querySelector(`#${id}`).getAttribute('role')).toBe('combobox');
      expect(screen.querySelector(`label[for="${id}"]`)).not.toBeNull();
    }
    const code = screen.querySelector('#custom-field-code') as HTMLInputElement;
    expect(code.disabled).toBe(false);
    expect(code.getAttribute('aria-required')).toBe('true');
    expect(code.hasAttribute('cdkFocusInitial')).toBe(true);
    expect((screen.querySelector('#custom-field-name') as HTMLInputElement).getAttribute('aria-required')).toBe('true');
    expect(screen.textContent).toContain('Только строчные латинские буквы');
    expect(submit().textContent?.trim()).toBe('Создать');
  });

  it('edits a field without changing its entity, type or code', async () => {
    const { screen, submit } = await setup((host) => {
      host.editing.set(budget);
      host.data.set({ ...blank(), code: 'budget', name: 'Бюджет' });
    });

    expect(screen.querySelector('.smt-modal__title').textContent).toBe('Редактирование поля');
    expect(screen.querySelector('#custom-field-entity')).toBeNull();
    expect(screen.querySelector('#custom-field-type')).toBeNull();
    expect((screen.querySelector('#custom-field-code') as HTMLInputElement).disabled).toBe(true);
    expect(screen.textContent).toContain('Код поля нельзя изменить после создания');
    expect(submit().textContent?.trim()).toBe('Сохранить');
  });

  it('asks for the list values only for a drop-down field, and requires them there', async () => {
    const plain = await setup();
    expect(plain.screen.querySelector('#custom-field-options')).toBeNull();
    plain.fixture.destroy();

    const list = await setup((host) =>
      host.data.set({ ...blank(), code: 'size', name: 'Размер', fieldType: 'select' }),
    );
    expect(list.screen.querySelector('label[for="custom-field-options"]')).not.toBeNull();
    list.submit().click();
    await list.settle();
    expect(list.errors()).toEqual(['Добавьте хотя бы один вариант списка']);
  });

  it('explains every empty or wrong field under it after a save attempt and focuses the first', async () => {
    const { screen, host, submit, errors, settle } = await setup();

    submit().click();
    await settle();
    await settle();

    expect(host.saves).toBe(1);
    expect(errors()).toEqual(['Укажите код поля', 'Укажите название поля']);
    expect(document.activeElement).toBe(screen.querySelector('#custom-field-code'));

    host.data.update((data) => ({ ...data, code: 'status', name: 'Статус' }));
    await settle();
    expect(errors()).toEqual(['Этот код поля зарезервирован системой']);
  });

  it('shows the server field messages under the fields and the others in the summary', async () => {
    const { screen, host, errors, settle } = await setup();

    host.errors.set({ fields: { code: 'Код уже занят' }, other: ['Слишком много полей'] });
    await settle();
    await settle();

    expect(errors()).toEqual(['Код уже занят']);
    expect(screen.querySelector('[data-testid="form-error-summary"]').textContent).toContain('Слишком много полей');
    expect(document.activeElement).toBe(screen.querySelector('#custom-field-code'));
  });

  it('cleans the typed code at once and passes the typing on to the page', async () => {
    const { screen, host, settle } = await setup();
    const code = screen.querySelector('#custom-field-code') as HTMLInputElement;

    code.value = 'Cost USD';
    code.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();

    expect(host.codes).toBe(1);
    expect(code.value).toBe('cost_usd');
    expect(host.data().code).toBe('cost_usd');
  });

  it('asks to save on Save, to close on Cancel, and shows the save busy', async () => {
    const { host, submit, cancel, settle } = await setup();

    submit().click();
    expect(host.saves).toBe(1);
    cancel().click();
    expect(host.closes).toBe(1);

    host.saving.set(true);
    await settle();
    expect(submit().getAttribute('aria-busy')).toBe('true');
    expect(cancel().disabled).toBe(true);
  });
});
