import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { Task } from '@core/models/task.models';
import { ApiService } from '@core/services/api.service';
import { inScreen, Screen } from '@testing/in-screen';
import { TaskEditFormValue } from '../tasks.models';
import { TaskEditModalComponent } from './task-edit-modal.component';

const TASK = {
  id: 7,
  title: 'Отчёт за январь',
  statusId: 1,
  priority: 'medium',
  attributes: {},
  createdAt: '2026-09-01T00:00:00Z',
} as Task;

const editForm = (title = TASK.title): TaskEditFormValue => ({
  title,
  taskType: 'task',
  descriptionMarkdown: '',
  projectId: null,
  priority: 'medium',
  responsibleUserId: null,
  parentTaskId: null,
  executorUserIds: [],
  observerUserIds: [],
  beginTime: '',
  endTime: '',
  attributes: {},
});

function render(inputs: Record<string, unknown> = {}) {
  TestBed.configureTestingModule({
    imports: [TaskEditModalComponent],
    providers: [{ provide: ApiService, useValue: { get: vi.fn(() => of({ items: [], nextCursor: null })) } }],
  });
  const fixture = TestBed.createComponent(TaskEditModalComponent);
  const form = editForm();
  fixture.componentRef.setInput('isOpen', true);
  fixture.componentRef.setInput('editForm', form);
  for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
  fixture.detectChanges();
  return { fixture, form, screen: inScreen(fixture.nativeElement) };
}

const text = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
const titleError = (screen: Screen) =>
  text(screen.querySelector('smt-control:has(#task-edit-title) .smt-control__error'));
const footerButton = (screen: Screen, label: string) =>
  [...screen.querySelectorAll('[footer] button')].find((node: Element) => text(node) === label) as
    HTMLButtonElement | undefined;

function click(fixture: ComponentFixture<TaskEditModalComponent>, node: HTMLElement | undefined) {
  if (!node) throw new Error('nothing to click');
  node.click();
  fixture.detectChanges();
}

describe('TaskEditModalComponent', () => {
  it('says it is loading and offers no form or save until the task is in', () => {
    const { screen } = render({ editLoading: true });

    expect(text(screen.querySelector('[role="status"]'))).toBe('Загрузка актуальных данных задачи…');
    expect(screen.querySelector('fieldset.task-edit-form')).toBeNull();
    expect(footerButton(screen, 'Сохранить изменения')).toBeUndefined();
  });

  it('offers a retry when the task failed to load, and closing instead of cancelling', () => {
    const { fixture, screen } = render({ editLoadError: true });
    const retried = vi.fn();
    fixture.componentInstance.retryEditLoad.subscribe(retried);

    expect(text(screen.querySelector('[role="alert"] span'))).toBe('Не удалось загрузить задачу для редактирования.');
    click(fixture, screen.querySelector('[role="alert"] button') as HTMLButtonElement);
    expect(retried).toHaveBeenCalledTimes(1);
    expect(footerButton(screen, 'Закрыть')).toBeDefined();
  });

  it('shows the task title, writes an edit into the form and asks the page to save or close', () => {
    const { fixture, form, screen } = render({ editingTask: TASK });
    const saved = vi.fn();
    const closed = vi.fn();
    fixture.componentInstance.submitForm.subscribe(saved);
    fixture.componentInstance.closeModal.subscribe(closed);
    const title = screen.querySelector('#task-edit-title') as HTMLInputElement;

    expect(text(screen.querySelector('.smt-modal__title'))).toBe('Редактирование задачи');
    expect(title.value).toBe('Отчёт за январь');
    title.value = 'Отчёт за февраль';
    title.dispatchEvent(new Event('input'));
    expect(form.title).toBe('Отчёт за февраль');

    click(fixture, footerButton(screen, 'Сохранить изменения'));
    click(fixture, footerButton(screen, 'Отмена'));
    expect(saved).toHaveBeenCalledTimes(1);
    expect(closed).toHaveBeenCalledTimes(1);
  });

  it('says the title is required once left empty, and that it cannot be blank after a save', () => {
    const { fixture, screen } = render({ editingTask: TASK, editForm: editForm('   ') });
    expect(titleError(screen)).toBe('');

    fixture.componentRef.setInput('isEditSubmitted', true);
    fixture.detectChanges();
    expect(titleError(screen)).toBe('Название задачи не может быть пустым');

    fixture.componentRef.setInput('isEditSubmitted', false);
    fixture.componentRef.setInput('editForm', editForm(''));
    fixture.detectChanges();
    (screen.querySelector('#task-edit-title') as HTMLInputElement).dispatchEvent(new Event('blur'));
    fixture.detectChanges();
    expect(titleError(screen)).toBe('Обязательное поле');
  });

  it('does not offer the task itself as its own parent', () => {
    const { fixture } = render({ editingTask: TASK });

    expect(fixture.componentInstance.notThisTask({ id: 7, title: TASK.title })).toBe(true);
    expect(fixture.componentInstance.notThisTask({ id: 8, title: 'Другая' })).toBe(false);
  });

  it('asks before dropping unsaved changes and reports the answer', () => {
    const { fixture, screen } = render({ isOpen: false, isEditDiscardConfirmationOpen: true });
    const kept = vi.fn();
    const dropped = vi.fn();
    fixture.componentInstance.cancelDiscard.subscribe(kept);
    fixture.componentInstance.confirmDiscard.subscribe(dropped);

    expect(text(screen.querySelector('.smt-modal__title'))).toBe('Отменить редактирование?');
    expect(text(screen.querySelector('.dictionary-delete-body'))).toBe('Несохранённые изменения будут потеряны.');
    click(fixture, footerButton(screen, 'Отмена'));
    click(fixture, footerButton(screen, 'Не сохранять'));
    expect(kept).toHaveBeenCalledTimes(1);
    expect(dropped).toHaveBeenCalledTimes(1);
  });
});
