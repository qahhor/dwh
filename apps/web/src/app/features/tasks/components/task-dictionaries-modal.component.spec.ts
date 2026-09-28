import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { TaskStatus, TaskType } from '@core/models/task.models';
import { inScreen, Screen } from '@testing/in-screen';
import { TaskDictionariesModalComponent } from './task-dictionaries-modal.component';

const TYPES: TaskType[] = [
  { id: 1, code: 'task', name: 'Задача', icon: 'task_alt', color: '#2563eb', orderNo: 1, isSystem: true },
  { id: 2, code: 'doc', name: 'Документ', icon: 'description', color: '#16a34a', orderNo: 2, isSystem: false },
];

const STATUSES: TaskStatus[] = [
  { id: 1, pcode: 'new', name: 'Новая', color: '#0284c7', isTerminal: false, orderNo: 1 },
  { id: 4, pcode: null, name: 'Согласовано', color: '#16a34a', isTerminal: true, orderNo: 2 },
];

function render() {
  TestBed.configureTestingModule({ imports: [TaskDictionariesModalComponent] });
  const fixture = TestBed.createComponent(TaskDictionariesModalComponent);
  fixture.componentRef.setInput('isOpen', true);
  fixture.componentRef.setInput('taskTypes', TYPES);
  fixture.componentRef.setInput('statuses', STATUSES);
  fixture.detectChanges();
  return { fixture, screen: inScreen(fixture.nativeElement) };
}

const text = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
/** A dictionary row as its visible parts: name, code and badges; icons and colour marks are left out. */
const parts = (row: Element) =>
  [...row.children]
    .filter((child) => child.getAttribute('aria-hidden') !== 'true')
    .map(text)
    .filter(Boolean);

function type(fixture: ComponentFixture<TaskDictionariesModalComponent>, screen: Screen, id: string, value: string) {
  const field = screen.querySelector(`#${id}`) as HTMLInputElement;
  field.value = value;
  field.dispatchEvent(new Event('input'));
  fixture.detectChanges();
}

function press(fixture: ComponentFixture<TaskDictionariesModalComponent>, screen: Screen, label: string) {
  const button = [...screen.querySelectorAll('button')].find((node: HTMLButtonElement) => text(node) === label);
  if (!button) throw new Error(`no button "${label}"`);
  (button as HTMLButtonElement).click();
  fixture.detectChanges();
}

function openStatuses(fixture: ComponentFixture<TaskDictionariesModalComponent>, screen: Screen) {
  (screen.querySelector('#task-statuses-tab') as HTMLElement).click();
  fixture.detectChanges();
}

describe('TaskDictionariesModalComponent', () => {
  it('names the tabs with their counts and lists the types, marking the system one', () => {
    const { screen } = render();

    expect(text(screen.querySelector('.smt-modal__title'))).toBe('Настройка справочников задач');
    expect([...screen.querySelectorAll('[role="tab"]')].map(text)).toEqual(['Типы задач (2)', 'Статусы задач (2)']);
    expect([...screen.querySelectorAll('#task-types-panel .dict-item-info')].map(parts)).toEqual([
      ['Задача', '(task)', 'Системный'],
      ['Документ', '(doc)'],
    ]);
  });

  it('lets a person delete only the non-system type, through a named button', () => {
    const { fixture, screen } = render();
    const deleted = vi.fn();
    fixture.componentInstance.deleteItem.subscribe(deleted);

    expect(screen.querySelector('button[aria-label="Удалить тип задачи Задача"]')).toBeNull();
    (screen.querySelector('button[aria-label="Удалить тип задачи Документ"]') as HTMLButtonElement).click();
    expect(deleted).toHaveBeenCalledWith({ kind: 'type', id: 2, name: 'Документ' });
  });

  it('adds a type from the trimmed code and name with the default icon, then empties the form', () => {
    const { fixture, screen } = render();
    const created = vi.fn();
    fixture.componentInstance.createType.subscribe(created);

    type(fixture, screen, 'task-type-code', '  bug ');
    press(fixture, screen, 'add Добавить тип');
    expect(created).not.toHaveBeenCalled();

    type(fixture, screen, 'task-type-name', ' Ошибка ');
    press(fixture, screen, 'add Добавить тип');
    expect(created).toHaveBeenCalledWith({ code: 'bug', name: 'Ошибка', icon: 'task_alt', color: '#2563eb' });
    expect((screen.querySelector('#task-type-code') as HTMLInputElement).value).toBe('');
    expect((screen.querySelector('#task-type-name') as HTMLInputElement).value).toBe('');
  });

  it('shows the statuses on their tab, marking the final and the basic one, with delete only for the rest', () => {
    const { fixture, screen } = render();
    openStatuses(fixture, screen);

    expect(screen.querySelector('#task-types-panel')).toBeNull();
    expect([...screen.querySelectorAll('#task-statuses-panel .dict-item-info')].map(parts)).toEqual([
      ['Новая', 'Базовый'],
      ['Согласовано', 'Завершающий'],
    ]);
    expect(screen.querySelector('button[aria-label="Удалить статус Новая"]')).toBeNull();
    expect(screen.querySelector('button[aria-label="Удалить статус Согласовано"]')).not.toBeNull();
  });

  it('adds a final status and needs a name for it', () => {
    const { fixture, screen } = render();
    const created = vi.fn();
    fixture.componentInstance.createStatus.subscribe(created);
    openStatuses(fixture, screen);

    press(fixture, screen, 'add Добавить статус');
    expect(created).not.toHaveBeenCalled();

    type(fixture, screen, 'task-status-name', ' На проверке ');
    (screen.querySelector('#task-statuses-panel [role="checkbox"]') as HTMLElement).click();
    fixture.detectChanges();
    press(fixture, screen, 'add Добавить статус');
    expect(created).toHaveBeenCalledWith({ name: 'На проверке', color: '#0284c7', isTerminal: true });
  });

  it('hands a new order of the types to the page when a row is moved', () => {
    const { fixture, screen } = render();
    const reordered = vi.fn();
    fixture.componentInstance.reorderTypes.subscribe(reordered);

    (screen.querySelector('button[aria-label="Опустить «Задача»"]') as HTMLButtonElement).click();
    expect(reordered.mock.calls[0][0].map((item: TaskType) => item.code)).toEqual(['doc', 'task']);
  });
});
