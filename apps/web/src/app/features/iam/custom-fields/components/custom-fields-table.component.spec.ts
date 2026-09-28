import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { CustomField } from '../custom-fields.models';
import { CustomFieldsTableComponent } from './custom-fields-table.component';

describe('CustomFieldsTableComponent', () => {
  const budget: CustomField = {
    id: 8,
    entityType: 'TASK',
    code: 'budget',
    name: 'Бюджет',
    fieldType: 'number',
    isRequired: true,
    orderNo: 10,
    createdAt: '2026-08-30T00:00:00Z',
  };
  const inn: CustomField = {
    id: 9,
    entityType: 'USER',
    code: 'inn',
    name: 'ИНН',
    fieldType: 'string',
    isRequired: false,
    defaultValue: '000',
    orderNo: 20,
    createdAt: '2026-08-30T00:00:00Z',
  };

  function setup(
    inputs: {
      fields?: CustomField[];
      searchQuery?: string;
      isLoading?: boolean;
      canManage?: boolean;
      canEdit?: boolean;
      canDelete?: boolean;
    } = {},
  ) {
    const fixture = TestBed.createComponent(CustomFieldsTableComponent);
    fixture.componentRef.setInput('fields', inputs.fields ?? [budget, inn]);
    fixture.componentRef.setInput('searchQuery', inputs.searchQuery ?? '');
    fixture.componentRef.setInput('isLoading', inputs.isLoading ?? false);
    fixture.componentRef.setInput('canManage', inputs.canManage ?? false);
    fixture.componentRef.setInput('canEdit', inputs.canEdit ?? false);
    fixture.componentRef.setInput('canDelete', inputs.canDelete ?? false);
    const component = fixture.componentInstance;
    const asked = {
      copy: vi.fn(),
      edit: vi.fn(),
      remove: vi.fn(),
      clear: vi.fn(),
      create: vi.fn(),
    };
    component.copyCode.subscribe(asked.copy);
    component.editField.subscribe(asked.edit);
    component.deleteField.subscribe(asked.remove);
    component.clearSearch.subscribe(asked.clear);
    component.createField.subscribe(asked.create);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const rows = () => Array.from(host.querySelectorAll('.smt-data-row')) as HTMLElement[];
    const button = (label: string) => host.querySelector(`button[aria-label="${label}"]`) as HTMLButtonElement | null;
    return { fixture, host, rows, button, asked };
  }

  it('shows each field with its code, name, entity, type and whether it is required', () => {
    const { host, rows } = setup();

    expect(host.querySelector('.table-wrapper')?.getAttribute('aria-label')).toBe('Таблица динамических атрибутов');
    expect(rows()).toHaveLength(2);
    expect(rows()[0].textContent).toContain('budget');
    expect(rows()[0].textContent).toContain('Бюджет');
    expect(rows()[0].textContent).toContain('Задачи');
    expect(rows()[0].textContent).toContain('Число');
    expect(rows()[0].textContent).toContain('Да');
    expect(rows()[1].textContent).toContain('Пользователи');
    expect(rows()[1].textContent).toContain('000');
    expect(rows()[1].textContent).toContain('Нет');
  });

  it('copies the code of the row through its named button', () => {
    const { button, asked } = setup();

    button('Скопировать код inn')!.click();

    expect(asked.copy).toHaveBeenCalledWith('inn');
  });

  it('offers each row action and the actions column only to a viewer with the right', () => {
    const viewer = setup();
    const headers = Array.from(viewer.host.querySelectorAll('[role="columnheader"]')).map((cell) =>
      cell.textContent?.trim(),
    );
    expect(headers).not.toContain('Действия');
    expect(viewer.button('Редактировать Бюджет')).toBeNull();
    expect(viewer.button('Удалить Бюджет')).toBeNull();

    const editor = setup({ canEdit: true });
    expect(editor.button('Удалить Бюджет')).toBeNull();
    editor.button('Редактировать Бюджет')!.click();
    expect(editor.asked.edit).toHaveBeenCalledWith(budget);
  });

  it('offers editing and deleting of each row to a manager', () => {
    const { button, asked } = setup({ canManage: true });

    button('Удалить ИНН')!.click();

    expect(button('Редактировать ИНН')).not.toBeNull();
    expect(asked.remove).toHaveBeenCalledWith(inn);
  });

  it('says a search found nothing and offers to reset it', () => {
    const { host, asked } = setup({ fields: [], searchQuery: 'xyz' });

    expect(host.querySelector('.empty-state')?.textContent).toContain('Ничего не найдено по запросу');
    expect(host.querySelector('.empty-state strong')?.textContent).toBe('xyz');
    (host.querySelector('.empty-state button') as HTMLButtonElement).click();

    expect(asked.clear).toHaveBeenCalledTimes(1);
  });

  it('offers to add the first field only to a manager', () => {
    const viewer = setup({ fields: [] });
    expect(viewer.host.querySelector('.empty-state')?.textContent).toContain('Динамические поля не найдены');
    expect(viewer.host.querySelector('.empty-state button')).toBeNull();

    const manager = setup({ fields: [], canManage: true });
    (manager.host.querySelector('.empty-state button') as HTMLButtonElement).click();

    expect(manager.asked.create).toHaveBeenCalledTimes(1);
  });

  it('marks the table busy while loading and shows no empty message yet', () => {
    const { host } = setup({ fields: [], isLoading: true });

    expect(host.querySelector('[aria-label="Динамические атрибуты"][aria-busy="true"]')).not.toBeNull();
    expect(host.querySelector('.empty-state')).toBeNull();
  });
});
