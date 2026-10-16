import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { CustomField } from '@core/models/custom-field.models';
import { TaskType } from '@core/models/task.models';
import { ApiService } from '@core/services/api.service';
import { inScreen, Screen } from '@testing/in-screen';
import { focusFirstInvalid } from '@shared/ui/focus-first-invalid';
import { TaskCreateFormValue, createDefaultTaskCreateForm } from '../tasks.models';
import { TaskCreateModalComponent } from './task-create-modal.component';

const TYPES: TaskType[] = [
  { id: 1, code: 'task', name: 'Задача', icon: 'task_alt', color: '#2563eb', sortOrder: 1, system: true },
  { id: 2, code: 'bug', name: 'Ошибка', icon: 'bug_report', color: '#dc2626', sortOrder: 2, system: false },
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
const radios = (screen: Screen, groupId: string) =>
  [...screen.querySelectorAll(`[role="radiogroup"]#${groupId} [role="radio"]`)] as HTMLElement[];

function click(fixture: ComponentFixture<TaskCreateModalComponent>, node: HTMLElement) {
  node.click();
  fixture.detectChanges();
}

describe('TaskCreateModalComponent', () => {
  it('removes only the edited field refusal from the control and summary', () => {
    const { fixture, screen } = render({
      serverErrors: { fields: { title: 'Title refused', priority: 'Priority refused' }, other: ['Unknown refused'] },
    });
    const title = screen.querySelector('#task-create-title') as HTMLInputElement;
    title.value = 'Corrected';
    title.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(titleError(screen)).toBe('');
    const summary = text(screen.querySelector('[data-testid="form-error-summary"]'));
    expect(summary).not.toContain('Title refused');
    expect(summary).toContain('Priority refused');
    expect(summary).toContain('Unknown refused');
  });
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
    click(fixture, radios(screen, 'task-create-type')[1]);
    click(fixture, radios(screen, 'task-create-priority')[3]);

    expect(radios(screen, 'task-create-priority').map(text)).toEqual(['Низкий', 'Средний', 'Высокий', 'Критический']);
    expect(form).toEqual(expect.objectContaining({ title: 'Сверить остатки', taskType: 'bug', priority: 'critical' }));
    expect(radios(screen, 'task-create-type')[1].getAttribute('aria-checked')).toBe('true');
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

  it('locks every control and the cancel button while the task is being created', () => {
    const { screen } = render({ isSubmitting: true });
    const form = screen.querySelector('fieldset.task-create-form') as HTMLFieldSetElement;
    const controls = [...form.querySelectorAll('input, select, textarea, button')] as HTMLElement[];

    expect(form.disabled).toBe(true);
    expect(controls.length).toBeGreaterThan(5);
    expect(controls.every((control) => control.matches(':disabled'))).toBe(true);
    expect(footerButton(screen, 'Отмена').disabled).toBe(true);
  });

  it('names each field by its label for assistive technology', () => {
    const { screen } = render({ isCreateSubmitted: true });
    TestBed.tick(); // smt-control wires label, error and aria state after render
    const title = screen.querySelector('#task-create-title') as HTMLInputElement;
    const description = screen.querySelector('ui-markdown-editor textarea') as HTMLTextAreaElement;
    const project = screen.querySelector('#task-create-project') as HTMLElement;

    expect(screen.querySelector(`label[for="${title.id}"]`)).not.toBeNull();
    expect(title.getAttribute('aria-required')).toBe('true');
    expect(title.getAttribute('aria-invalid')).toBe('true');
    expect(description.getAttribute('aria-label')).toBe('Описание');
    expect(project.getAttribute('role')).toBe('combobox');
    expect(text(screen.querySelector('label[for="task-create-project"]'))).toContain('Проект');
    for (const [id, name] of [
      ['task-create-responsible', 'Ответственный'],
      ['task-create-parent', 'Родительская задача'],
    ]) {
      expect(text(screen.querySelector(`label[for="${id}"]`))).toBe(name);
    }
    for (const id of ['task-create-type', 'task-create-priority']) {
      const group = screen.querySelector(`#${id}`) as HTMLElement;
      expect(screen.querySelector(`#${group.getAttribute('aria-labelledby')}`)).not.toBeNull();
    }
    expect(screen.querySelector('smt-multi-select button[aria-label="Наблюдатели"]')).not.toBeNull();
  });

  it('shows the server field errors under their fields, and the others in the summary', () => {
    const { fixture, screen } = render({
      serverErrors: { fields: { endTime: 'Срок в прошлом' }, other: ['Бюджет: не число'] },
    });
    TestBed.tick();
    fixture.detectChanges();
    expect(text(screen.querySelector('smt-control:has(#task-create-deadline) .smt-control__error'))).toBe(
      'Срок в прошлом',
    );
    const summary = text(screen.querySelector('[data-testid="form-error-summary"]'));
    expect(summary).toContain('Срок сдачи (Дедлайн): Срок в прошлом');
    expect(summary).toContain('Бюджет: не число');
  });

  it('moves focus to the empty title when a submit finds it missing', async () => {
    const { fixture, screen } = render();
    const form = screen.querySelector('form#task-create-form') as HTMLFormElement;
    fixture.componentInstance.submitForm.subscribe(() => fixture.componentRef.setInput('isCreateSubmitted', true));
    form.dispatchEvent(new Event('submit', { cancelable: true }));
    fixture.detectChanges();
    await fixture.whenStable();
    TestBed.tick();
    expect(document.activeElement?.id).toBe('task-create-title');
  });

  it('shows configured custom fields and omits administration tips when there are none', () => {
    const { fixture, screen } = render();
    expect(screen.querySelector('.custom-fields-empty-tip')).toBeNull();
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

const additionalToggle = (screen: Screen) =>
  screen.querySelector('[data-testid="task-create-additional-toggle"]') as HTMLButtonElement;
const additionalFields = (screen: Screen) => screen.querySelector('#task-create-additional') as HTMLElement;

describe('TaskCreateModalComponent compact flow', () => {
  it('keeps the common fields visible and starts secondary options collapsed', () => {
    const { screen } = render();
    expect(additionalToggle(screen)?.getAttribute('aria-expanded')).toBe('false');
    expect(additionalFields(screen)?.hidden).toBe(true);
    for (const id of ['title', 'responsible', 'deadline', 'project']) {
      expect(screen.querySelector(`#task-create-${id}`)).not.toBeNull();
      expect(additionalFields(screen).querySelector(`#task-create-${id}`)).toBeNull();
    }
    expect(additionalFields(screen).querySelector('ui-markdown-editor')).toBeNull();
    expect(additionalFields(screen).querySelector('#task-create-parent')).not.toBeNull();
  });

  it('preserves secondary values through collapse and submits the same complete draft', () => {
    const { fixture, form, screen } = render();
    const toggle = additionalToggle(screen);
    expect(toggle).not.toBeNull();
    click(fixture, toggle);
    click(fixture, radios(screen, 'task-create-type')[1]);
    click(fixture, radios(screen, 'task-create-priority')[2]);
    fixture.componentInstance.setField('title', 'Plan delivery');
    fixture.componentInstance.setField('parentTaskId', 42);
    fixture.componentInstance.setField('executorUserIds', [3, 5]);
    fixture.componentInstance.setField('observerUserIds', [7]);
    click(fixture, toggle);
    expect(additionalFields(screen).hidden).toBe(true);
    expect(text(toggle)).toContain('Ошибка');
    expect(text(toggle)).toContain('Высокий');
    const submitted = vi.fn(() => ({ ...form }));
    fixture.componentInstance.submitForm.subscribe(submitted);
    click(fixture, footerButton(screen, 'Создать задачу'));
    expect(submitted).toHaveBeenCalledOnce();
    expect(submitted.mock.results[0].value).toMatchObject({
      title: 'Plan delivery',
      taskType: 'bug',
      priority: 'high',
      parentTaskId: 42,
      executorUserIds: [3, 5],
      observerUserIds: [7],
    });
    click(fixture, toggle);
    expect(radios(screen, 'task-create-priority')[2].getAttribute('aria-checked')).toBe('true');
  });

  it('reveals a server-rejected secondary field before the existing focus step', async () => {
    const { fixture, screen } = render();
    const form = screen.querySelector('form#task-create-form') as HTMLFormElement;
    expect(additionalFields(screen)?.hidden).toBe(true);
    fixture.componentRef.setInput('serverErrors', { fields: { parentTaskId: 'Parent is unavailable' }, other: [] });
    focusFirstInvalid(form, fixture.debugElement.injector);
    fixture.detectChanges();
    await fixture.whenStable();
    TestBed.tick();
    expect(additionalToggle(screen).getAttribute('aria-expanded')).toBe('true');
    expect(additionalFields(screen).hidden).toBe(false);
    expect(document.activeElement?.id).toBe('task-create-parent');
    expect(additionalToggle(screen).disabled).toBe(true);
    fixture.componentInstance.setField('parentTaskId', 55);
    fixture.detectChanges();
    expect(additionalFields(screen).hidden).toBe(false);
    expect(additionalToggle(screen).disabled).toBe(false);
  });

  it('resets disclosure only when a new draft is opened', () => {
    const { fixture, screen } = render();
    expect(additionalToggle(screen)).not.toBeNull();
    click(fixture, additionalToggle(screen));
    fixture.componentInstance.setField('title', 'Draft');
    fixture.detectChanges();
    expect(additionalFields(screen).hidden).toBe(false);
    fixture.componentRef.setInput('createForm', createDefaultTaskCreateForm());
    fixture.detectChanges();
    expect(additionalFields(screen).hidden).toBe(true);
  });

  it('locks secondary radio choices while saving', () => {
    const { fixture, form, screen } = render({ isSubmitting: true });
    click(fixture, radios(screen, 'task-create-type')[1]);
    click(fixture, radios(screen, 'task-create-priority')[3]);
    expect(form.taskType).toBe('task');
    expect(form.priority).toBe('medium');
  });
});
