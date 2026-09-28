import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { CustomField } from '@core/models/custom-field.models';
import { TaskType } from '@core/models/task.models';
import { ApiService } from '@core/services/api.service';
import { inScreen, Screen } from '@testing/in-screen';
import { TaskCreateFormValue, createDefaultTaskCreateForm } from '../tasks.models';
import { TaskCreateModalComponent } from './task-create-modal.component';

const TYPES: TaskType[] = [
  { id: 1, code: 'task', name: 'Задача', icon: 'task_alt', color: '#2563eb', orderNo: 1, isSystem: true },
  { id: 2, code: 'bug', name: 'Ошибка', icon: 'bug_report', color: '#dc2626', orderNo: 2, isSystem: false },
];

function render(inputs: Record<string, unknown> = {}) {
  TestBed.configureTestingModule({
    imports: [TaskCreateModalComponent],
    providers: [{ provide: ApiService, useValue: { get: vi.fn(() => of({ items: [], nextCursor: null })) } }],
  });
  const fixture = TestBed.createComponent(TaskCreateModalComponent);
  const form: TaskCreateFormValue = createDefaultTaskCreateForm();
  fixture.componentRef.setInput('isOpen', true);
  fixture.componentRef.setInput('taskTypes', TYPES);
  fixture.componentRef.setInput('createForm', form);
  for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
  fixture.detectChanges();
  return { fixture, form, screen: inScreen(fixture.nativeElement) };
}

const text = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
const titleError = (screen: Screen) =>
  text(screen.querySelector('smt-control:has(#task-create-title) .smt-control__error'));
const footerButton = (screen: Screen, label: string) =>
  [...screen.querySelectorAll('[footer] button')].find((node: Element) => text(node) === label) as HTMLButtonElement;
const radios = (screen: Screen, groupName: string) =>
  [...screen.querySelectorAll(`[role="radiogroup"][aria-label="${groupName}"] [role="radio"]`)] as HTMLElement[];

function click(fixture: ComponentFixture<TaskCreateModalComponent>, node: HTMLElement) {
  node.click();
  fixture.detectChanges();
}

describe('TaskCreateModalComponent', () => {
  it('is titled for a new task, or for a subtask of the parent the form names', () => {
    const { screen } = render();
    expect(text(screen.querySelector('.smt-modal__title'))).toBe('Создание новой задачи');
    TestBed.resetTestingModule();

    const subtask = render({ createForm: { ...createDefaultTaskCreateForm(), parentTaskId: 42 } });
    expect(text(subtask.screen.querySelector('.smt-modal__title'))).toBe('Создание подзадачи к #42');
  });

  it('writes the typed title, the chosen type and the chosen priority into the form', () => {
    const { fixture, form, screen } = render();
    const title = screen.querySelector('#task-create-title') as HTMLInputElement;

    title.value = 'Сверить остатки';
    title.dispatchEvent(new Event('input'));
    click(fixture, radios(screen, 'Тип задачи')[1]);
    click(fixture, radios(screen, 'Приоритет задачи')[3]);

    expect(radios(screen, 'Приоритет задачи').map(text)).toEqual(['Низкий', 'Средний', 'Высокий', 'Критический']);
    expect(form).toEqual(expect.objectContaining({ title: 'Сверить остатки', taskType: 'bug', priority: 'critical' }));
    expect(radios(screen, 'Тип задачи')[1].getAttribute('aria-checked')).toBe('true');
  });

  it('says the title is required once a person leaves it empty, and asks for it after a submit', () => {
    const { fixture, screen } = render();
    expect(titleError(screen)).toBe('');

    (screen.querySelector('#task-create-title') as HTMLInputElement).dispatchEvent(new Event('blur'));
    fixture.detectChanges();
    expect(titleError(screen)).toBe('Обязательное поле');

    fixture.componentRef.setInput('isCreateSubmitted', true);
    fixture.detectChanges();
    expect(titleError(screen)).toBe('Пожалуйста, укажите название задачи');
  });

  it('asks the page to create or to close', () => {
    const { fixture, screen } = render();
    const submitted = vi.fn();
    const closed = vi.fn();
    fixture.componentInstance.submitForm.subscribe(submitted);
    fixture.componentInstance.closeModal.subscribe(closed);

    click(fixture, footerButton(screen, 'Создать задачу'));
    click(fixture, footerButton(screen, 'Отмена'));

    expect(submitted).toHaveBeenCalledTimes(1);
    expect(closed).toHaveBeenCalledTimes(1);
  });

  it('locks the form and the cancel button while the task is being created', () => {
    const { screen } = render({ isSubmitting: true });

    expect((screen.querySelector('fieldset.task-create-form') as HTMLFieldSetElement).disabled).toBe(true);
    expect(footerButton(screen, 'Отмена').disabled).toBe(true);
  });

  it('points to the custom fields menu when the tasks have none, and shows the fields when they do', () => {
    const { fixture, screen } = render();
    expect(text(screen.querySelector('.custom-fields-empty-tip'))).toContain('Динамические поля');
    expect(screen.querySelector('ui-custom-fields')).toBeNull();

    const budget = {
      id: 1,
      entityType: 'task',
      code: 'budget',
      name: 'Бюджет',
      fieldType: 'number',
      isRequired: false,
      orderNo: 1,
      createdAt: '2026-09-01T00:00:00Z',
    } as CustomField;
    fixture.componentRef.setInput('taskCustomFields', [budget]);
    fixture.detectChanges();
    expect(screen.querySelector('.custom-fields-empty-tip')).toBeNull();
    expect(screen.querySelector('ui-custom-fields')).not.toBeNull();
  });
});
