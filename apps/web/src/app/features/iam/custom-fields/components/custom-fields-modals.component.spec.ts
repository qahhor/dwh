import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { inScreen } from '@testing/in-screen';
import { CustomField, CustomFieldFormData } from '../custom-fields.models';
import { CustomFieldsModalsComponent } from './custom-fields-modals.component';

describe('CustomFieldsModalsComponent', () => {
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

  function setup(
    inputs: {
      showModal?: boolean;
      formData?: CustomFieldFormData;
      editingField?: CustomField | null;
      formError?: string;
    } = {},
  ) {
    const fixture = TestBed.createComponent(CustomFieldsModalsComponent);
    fixture.componentRef.setInput('showModal', inputs.showModal ?? true);
    fixture.componentRef.setInput('formData', inputs.formData ?? blank());
    fixture.componentRef.setInput('editingField', inputs.editingField ?? null);
    fixture.componentRef.setInput('formError', inputs.formError ?? '');
    const component = fixture.componentInstance;
    const asked = { close: vi.fn(), save: vi.fn(), code: vi.fn() };
    component.closeModal.subscribe(asked.close);
    component.saveField.subscribe(asked.save);
    component.codeInput.subscribe(asked.code);
    fixture.detectChanges();
    const screen = inScreen(fixture.nativeElement);
    const button = (text: string) =>
      (Array.from(screen.querySelectorAll('.modal-footer-actions button')) as HTMLButtonElement[]).find(
        (item) => item.textContent?.trim() === text,
      )!;
    return { fixture, screen, button, asked };
  }

  it('shows nothing until the page opens it', () => {
    const { screen } = setup({ showModal: false });

    expect(screen.querySelector('.smt-modal')).toBeNull();
  });

  it('creates a field with its entity and type chosen from labelled pickers', () => {
    const { screen } = setup();

    expect(screen.querySelector('.smt-modal__title').textContent).toBe('Новое динамическое поле');
    for (const id of ['custom-field-entity', 'custom-field-type']) {
      expect(screen.querySelector(`#${id}`).getAttribute('role')).toBe('combobox');
      expect(screen.querySelector(`label[for="${id}"]`)).not.toBeNull();
    }
    expect((screen.querySelector('#custom-field-code') as HTMLInputElement).disabled).toBe(false);
    expect(screen.textContent).toContain('Только строчные латинские буквы');
  });

  it('edits a field without changing its entity, type or code', () => {
    const { screen } = setup({ editingField: budget, formData: { ...blank(), code: 'budget', name: 'Бюджет' } });

    expect(screen.querySelector('.smt-modal__title').textContent).toBe('Редактирование поля');
    expect(screen.querySelector('#custom-field-entity')).toBeNull();
    expect(screen.querySelector('#custom-field-type')).toBeNull();
    expect((screen.querySelector('#custom-field-code') as HTMLInputElement).disabled).toBe(true);
    expect(screen.textContent).toContain('Код поля нельзя изменить после создания');
  });

  it('asks for the list values only for a drop-down field', () => {
    const plain = setup();
    expect(plain.screen.querySelector('#custom-field-options')).toBeNull();
    plain.fixture.destroy();

    const list = setup({ formData: { ...blank(), fieldType: 'select' } });
    expect(list.screen.querySelector('#custom-field-options')).not.toBeNull();
    expect(list.screen.querySelector('label[for="custom-field-options"]')).not.toBeNull();
  });

  it('shows the page error as an alert tied to the fields', () => {
    const { screen } = setup({ formError: 'Код уже занят' });

    const alert = screen.querySelector('#custom-field-form-error');
    expect(alert.getAttribute('role')).toBe('alert');
    expect(alert.textContent).toBe('Код уже занят');
    const code = screen.querySelector('#custom-field-code') as HTMLInputElement;
    expect(code.getAttribute('aria-invalid')).toBe('true');
    expect(code.getAttribute('aria-describedby')).toBe('custom-field-form-error');
  });

  it('cleans the typed code at once and passes the typing on to the page', () => {
    const { fixture, screen, asked } = setup();
    const code = screen.querySelector('#custom-field-code') as HTMLInputElement;

    code.value = 'Cost USD';
    code.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();

    expect(asked.code).toHaveBeenCalledTimes(1);
    expect(code.value).toBe('cost_usd');
  });

  it('asks to save once per press of Save when the form is filled in', () => {
    const { button, asked } = setup({ formData: { ...blank(), code: 'budget', name: 'Бюджет' } });

    button('Сохранить').click();

    expect(asked.save).toHaveBeenCalledTimes(1);
  });

  it('asks to save on Save and to close on Cancel', () => {
    const { button, asked } = setup();

    button('Сохранить').click();
    expect(asked.save).toHaveBeenCalledTimes(1);
    expect(asked.close).not.toHaveBeenCalled();

    button('Отмена').click();
    expect(asked.close).toHaveBeenCalledTimes(1);
  });
});
