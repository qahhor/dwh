import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { CustomField } from '@core/models/custom-field.models';
import { Task, TaskComment, TaskMember, TaskStatus } from '@core/models/task.models';
import { ApiService } from '@core/services/api.service';
import { inScreen, Screen } from '@testing/in-screen';
import { TaskDetailModalComponent } from './task-detail-modal.component';

const task = (id: number, title: string, extra: Partial<Task> = {}): Task =>
  ({
    id,
    title,
    statusCode: 's1',
    priority: 'medium',
    attributes: {},
    createdAt: '2026-09-01T00:00:00Z',
    ...extra,
  }) as Task;

const TASK = task(7, 'Отчёт за январь', {
  projectId: 5,
  descriptionMarkdown: 'Свести продажи',
  typeCode: 'task',
  attributes: { urgent: true, contract: 'Д-15' },
});

const STATUSES = [
  { id: 1, code: 's1', name: 'Новая', terminal: false, sortOrder: 1 },
  { id: 3, code: 's3', name: 'Готово', terminal: true, sortOrder: 2 },
] as TaskStatus[];

const MEMBERS = [
  { taskId: 7, userId: 1, involveKind: 'R', userName: 'Ольга Петрова', userLogin: 'olga' },
  { taskId: 7, userId: 2, involveKind: 'E', userName: 'Андрей Ким', userLogin: 'andrey' },
  { taskId: 7, userId: 3, involveKind: 'E', userName: 'Бахром Алиев', userLogin: 'bahrom' },
] as TaskMember[];

const URGENT = {
  id: 1,
  entityType: 'task',
  code: 'urgent',
  name: 'Срочно',
  fieldType: 'boolean',
  isRequired: false,
  orderNo: 1,
  createdAt: '2026-09-01T00:00:00Z',
} as CustomField;

function render(inputs: Record<string, unknown> = {}) {
  TestBed.configureTestingModule({
    imports: [TaskDetailModalComponent],
    providers: [{ provide: ApiService, useValue: { get: vi.fn(() => of({ items: [], nextCursor: null })) } }],
  });
  const fixture = TestBed.createComponent(TaskDetailModalComponent);
  const set = (name: string, value: unknown) => fixture.componentRef.setInput(name, value);
  set('isOverdue', () => false);
  set('getTypeColor', () => '#2563eb');
  set('getTypeBg', () => '#eff6ff');
  set('getTypeIcon', () => 'task_alt');
  set('getTypeLabel', () => 'Задача');
  set('getStatusName', (id: number | null | undefined) => STATUSES.find((status) => status.id === id)?.name ?? '');
  set('getStatusColor', () => '#0284c7');
  set('getPriorityLabel', (priority: string) => `приоритет ${priority}`);
  set('getProjectName', (row: Task) => (row.projectId === 5 ? 'Склад' : null));
  set('isOpen', true);
  set('detailRecordId', '7');
  set('selectedTask', TASK);
  set('statuses', STATUSES);
  for (const [name, value] of Object.entries(inputs)) set(name, value);
  fixture.detectChanges();
  return { fixture, screen: inScreen(fixture.nativeElement) };
}

const text = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
const button = (screen: Screen, label: string) =>
  [...screen.querySelectorAll('button')].find((node: Element) => text(node).endsWith(label)) as
    HTMLButtonElement | undefined;

function click(fixture: ComponentFixture<TaskDetailModalComponent>, node: HTMLElement | undefined | null) {
  if (!node) throw new Error('nothing to click');
  node.click();
  fixture.detectChanges();
}

describe('TaskDetailModalComponent', () => {
  it('says it is loading, offers a retry after a failure, and a way back when the task is gone', () => {
    const loading = render({ detailLoading: true });
    expect(text(loading.screen.querySelector('[role="status"]'))).toBe('Загрузка деталей задачи…');
    expect(loading.screen.querySelector('.task-details-view')).toBeNull();

    loading.fixture.componentRef.setInput('detailLoading', false);
    loading.fixture.componentRef.setInput('detailLoadError', true);
    loading.fixture.detectChanges();
    const retried = vi.fn();
    loading.fixture.componentInstance.retryTaskDetails.subscribe(retried);
    expect(text(loading.screen.querySelector('[role="alert"] span'))).toBe('Не удалось загрузить детали задачи.');
    click(loading.fixture, loading.screen.querySelector('[role="alert"] button'));
    expect(retried).toHaveBeenCalledTimes(1);

    loading.fixture.componentRef.setInput('detailNotFound', true);
    loading.fixture.detectChanges();
    const closed = vi.fn();
    loading.fixture.componentInstance.closeModal.subscribe(closed);
    expect(text(loading.screen.querySelector('[role="alert"] span'))).toBe('404 — Запись не найдена или недоступна.');
    click(loading.fixture, loading.screen.querySelector('[role="alert"] button'));
    expect(closed).toHaveBeenCalledTimes(1);
  });

  it('shows the task with its number, description, project and members grouped by role', () => {
    const { screen } = render({ taskMembers: MEMBERS });

    expect(text(screen.querySelector('.smt-modal__title'))).toBe('Задача #7');
    expect(text(screen.querySelector('.detail-main-title'))).toBe('Отчёт за январь');
    expect(text(screen.querySelector('.description-card'))).toContain('Свести продажи');
    expect(text(screen.querySelector('.details-side-col'))).toContain('Склад');
    expect(text(screen.querySelector('.member-highlighted .member-name'))).toBe('Ольга Петрова');
    const groups = [...screen.querySelectorAll('.member-role-title span:not([aria-hidden])')].map(text);
    expect(groups).toEqual(['Ответственный', 'Соисполнители (2)']);
  });

  it('groups observers and the author too, and offers the change history closed', () => {
    const others = [
      { taskId: 7, userId: 4, involveKind: 'O', userName: 'Глеб', userLogin: 'gleb' },
      { taskId: 7, userId: 5, involveKind: 'A', userName: 'Дамир', userLogin: 'damir' },
    ] as TaskMember[];
    const { screen } = render({ taskMembers: [...MEMBERS, ...others] });
    const groups = [...screen.querySelectorAll('.member-role-title')].map(text).join(' ');
    const history = screen.querySelector('ui-record-history [data-testid="record-history-toggle"]') as HTMLElement;

    expect(groups).toContain('Наблюдатели');
    expect(groups).toContain('Автор');
    expect([...screen.querySelectorAll('.member-name')].map(text)).toEqual(expect.arrayContaining(['Глеб', 'Дамир']));
    expect(history.getAttribute('aria-expanded')).toBe('false');
    expect(text(history)).toContain('История изменений');
  });

  it('names custom attributes by their field and reads a yes/no field as words', () => {
    const { screen } = render({ taskCustomFields: [URGENT] });
    const attributes = [...screen.querySelectorAll('.attr-stack-item')].map((item: Element) =>
      [...item.children].map(text),
    );

    expect(attributes).toEqual([
      ['Срочно:', 'Да'],
      ['contract:', 'Д-15'],
    ]);
  });

  it('lists the subtasks as named buttons that open them, or says there are none', () => {
    const subtasks = [task(8, 'Выгрузка'), task(9, 'Сверка', { statusCode: 's3' })];
    const { fixture, screen } = render({ taskSubtasks: subtasks });
    const opened = vi.fn();
    fixture.componentInstance.openTaskDetails.subscribe(opened);

    expect(text(screen.querySelector('.section-header-between .section-label'))).toBe('Подзадачи (2)');
    click(fixture, screen.querySelector('button[aria-label="Открыть подзадачу #9: Сверка"]'));
    expect(opened).toHaveBeenCalledWith(subtasks[1]);

    fixture.componentRef.setInput('taskSubtasks', []);
    fixture.detectChanges();
    expect(text(screen.querySelector('.no-subtasks-hint'))).toBe('У этой задачи пока нет подзадач.');
  });

  it('offers adding a subtask, editing and a status change only to someone allowed to', () => {
    const readOnly = render();
    const status = () => readOnly.screen.querySelector('[role="combobox"][aria-label="Статус задачи #7"]');
    expect(button(readOnly.screen, 'Добавить подзадачу')).toBeUndefined();
    expect(button(readOnly.screen, 'Редактировать задачу')).toBeUndefined();
    expect(status().disabled).toBe(true);
    TestBed.resetTestingModule();

    const { fixture, screen } = render({ canCreateTask: true, canUpdateTask: true });
    const added = vi.fn();
    const edited = vi.fn();
    const changed = vi.fn();
    fixture.componentInstance.openAddSubtask.subscribe(added);
    fixture.componentInstance.openEditModal.subscribe(edited);
    fixture.componentInstance.statusChange.subscribe(changed);

    click(fixture, button(screen, 'Добавить подзадачу'));
    click(fixture, button(screen, 'Редактировать задачу'));
    click(fixture, screen.querySelector('[role="combobox"][aria-label="Статус задачи #7"]'));
    const done = [...document.querySelectorAll<HTMLElement>('.smt-select__option')].find(
      (option) => text(option) === 'Готово',
    );
    click(fixture, done);

    expect(added).toHaveBeenCalledWith(TASK);
    expect(edited).toHaveBeenCalledWith(TASK);
    expect(changed).toHaveBeenCalledWith({ taskId: 7, statusCode: 's3' });
  });

  it('shows the comments, a retry when they fail, and passes a new comment to the page', () => {
    const comments = [
      { id: 1, taskId: 7, userId: 2, userName: 'Андрей Ким', userLogin: 'andrey', textMarkdown: 'Готово к сверке' },
      { id: 2, taskId: 7, userId: 9, userName: null, userLogin: null, textMarkdown: 'Старый ответ' },
    ] as TaskComment[];
    const { fixture, screen } = render({ comments, canCommentTask: true });
    const drafted = vi.fn();
    const sent = vi.fn();
    fixture.componentInstance.commentDraftChange.subscribe(drafted);
    fixture.componentInstance.submitComment.subscribe(sent);

    expect(text(screen.querySelector('.comments-section .section-label'))).toBe('Комментарии (2)');
    expect([...screen.querySelectorAll('.comment-author')].map(text)).toEqual([
      'Андрей Ким @andrey',
      'Удалённый пользователь',
    ]);
    const draft = screen.querySelector('#task-comment-draft') as HTMLTextAreaElement;
    expect(text(screen.querySelector('.comment-control .smt-control__label'))).toBe('Комментарий к задаче #7');
    draft.value = 'Принято';
    draft.dispatchEvent(new Event('input'));
    click(fixture, button(screen, 'Отправить'));
    expect(drafted).toHaveBeenCalledWith('Принято');
    expect(sent).toHaveBeenCalledTimes(1);

    fixture.componentRef.setInput('commentsLoadError', true);
    fixture.detectChanges();
    const retried = vi.fn();
    fixture.componentInstance.retryComments.subscribe(retried);
    expect(screen.querySelector('.comments-feed')).toBeNull();
    click(fixture, screen.querySelector('.comments-section [role="alert"] button'));
    expect(retried).toHaveBeenCalledTimes(1);
  });
});
