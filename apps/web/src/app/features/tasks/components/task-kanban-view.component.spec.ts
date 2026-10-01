import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { Task, TaskStatus } from '@core/models/task.models';
import { TaskKanbanViewComponent } from './task-kanban-view.component';

const STATUSES = [
  { id: 1, name: 'Новая', color: '#0284c7', isTerminal: false, orderNo: 1 },
  { id: 2, name: 'В работе', color: '#f59e0b', isTerminal: false, orderNo: 2 },
  { id: 3, name: 'Готово', color: '#16a34a', isTerminal: true, orderNo: 3 },
] as TaskStatus[];

const task = (id: number, title: string, statusId: number, extra: Partial<Task> = {}): Task =>
  ({
    id,
    title,
    statusId,
    priority: 'medium',
    attributes: {},
    createdAt: '2026-09-01T00:00:00Z',
    ...extra,
  }) as Task;

const TASKS = [
  task(11, 'Отчёт за январь', 1, { projectId: 5, endTime: '2026-09-20T10:00:00Z' }),
  task(12, 'Сверка остатков', 1),
  task(13, 'Закрытие месяца', 3),
];

function render(inputs: Record<string, unknown> = {}) {
  TestBed.configureTestingModule({ imports: [TaskKanbanViewComponent] });
  const fixture = TestBed.createComponent(TaskKanbanViewComponent);
  fixture.componentRef.setInput('tasks', TASKS);
  fixture.componentRef.setInput('statuses', STATUSES);
  fixture.componentRef.setInput('getDeadlineInfo', (endTime: string | null | undefined) =>
    endTime ? { state: 'overdue', label: 'Просрочено на 8 дн.' } : { state: 'none', label: '' },
  );
  fixture.componentRef.setInput('getPriorityLabel', (priority: string) => `приоритет ${priority}`);
  fixture.componentRef.setInput('getTypeColor', () => '#2563eb');
  fixture.componentRef.setInput('getTypeIcon', () => 'task_alt');
  fixture.componentRef.setInput('getTypeLabel', () => 'Задача');
  fixture.componentRef.setInput('getProjectName', (row: Task) => (row.projectId === 5 ? 'Склад' : null));
  fixture.componentRef.setInput('isOverdue', () => false);
  for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
  fixture.detectChanges();
  return fixture;
}

const el = (fixture: ComponentFixture<TaskKanbanViewComponent>) => fixture.nativeElement as HTMLElement;
const text = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
const columns = (fixture: ComponentFixture<TaskKanbanViewComponent>) => [
  ...el(fixture).querySelectorAll<HTMLElement>('.kanban-column'),
];
const card = (fixture: ComponentFixture<TaskKanbanViewComponent>, id: number) =>
  [...el(fixture).querySelectorAll<HTMLElement>('.kanban-card')].find((node) =>
    text(node.querySelector('.task-id')).includes(`#${id}`),
  ) as HTMLElement;
const button = (fixture: ComponentFixture<TaskKanbanViewComponent>, name: string) =>
  el(fixture).querySelector(`button[aria-label="${name}"]`) as HTMLButtonElement;

describe('TaskKanbanViewComponent', () => {
  it('lays the tasks out in one counted column per status, with a drop hint in an empty one', () => {
    const fixture = render();
    const [fresh, working, done] = columns(fixture);

    expect(columns(fixture).map((column) => text(column.querySelector('.column-title')))).toEqual([
      'Новая',
      'В работе',
      'Готово',
    ]);
    expect(columns(fixture).map((column) => text(column.querySelector('.column-badge')))).toEqual(['2', '0', '1']);
    expect([...fresh.querySelectorAll('.kanban-title-open')].map(text)).toEqual(['Отчёт за январь', 'Сверка остатков']);
    expect(text(working.querySelector('.kanban-empty-col'))).toBe('Перетащите задачу сюда');
    expect([...done.querySelectorAll('.kanban-title-open')].map(text)).toEqual(['Закрытие месяца']);
  });

  it('shows a card with its project, deadline and priority, or says it has no deadline', () => {
    const fixture = render();

    expect(text(card(fixture, 11).querySelector('.project-tag-mini'))).toContain('Склад');
    expect(text(card(fixture, 11).querySelector('.deadline-pill'))).toContain('Просрочено на 8 дн.');
    expect(text(card(fixture, 11).querySelector('.priority-pill'))).toBe('приоритет medium');
    expect(card(fixture, 12).querySelector('.deadline-pill')).toBeNull();
    expect(text(card(fixture, 12).querySelector('.card-bottom-row'))).toContain('Без срока');
  });

  it('opens a task from its named title or the card, but not from its move buttons', () => {
    const fixture = render({ canUpdateTask: true });
    const opened = vi.fn();
    fixture.componentInstance.openTaskDetails.subscribe(opened);

    button(fixture, 'Открыть задачу #11: Отчёт за январь').click();
    (card(fixture, 12).querySelector('.card-top-row') as HTMLElement).click();
    button(fixture, 'Переместить задачу #12 вперёд').click();

    expect(opened.mock.calls.map(([opened]) => opened.id)).toEqual([11, 12]);
  });

  it('moves a task one status along, with no way back from the first or forward from the last', () => {
    const fixture = render({ canUpdateTask: true });
    const moved = vi.fn();
    fixture.componentInstance.taskStatusChange.subscribe(moved);

    expect(button(fixture, 'Переместить задачу #11 назад').disabled).toBe(true);
    expect(button(fixture, 'Переместить задачу #13 вперёд').disabled).toBe(true);
    button(fixture, 'Переместить задачу #11 вперёд').click();
    button(fixture, 'Переместить задачу #13 назад').click();

    expect(moved.mock.calls.map(([change]) => [change.task.id, change.targetStatusId])).toEqual([
      [11, 2],
      [13, 2],
    ]);
  });

  it('reports a card dragged to another column, and nothing when it is dropped where it was', () => {
    const fixture = render({ canUpdateTask: true });
    const started = vi.fn();
    const ended = vi.fn();
    const moved = vi.fn();
    fixture.componentInstance.taskDragStart.subscribe(started);
    fixture.componentInstance.taskDragEnd.subscribe(ended);
    fixture.componentInstance.taskStatusChange.subscribe(moved);
    const drag = (type: string, target: HTMLElement) =>
      target.dispatchEvent(new Event(type, { bubbles: true, cancelable: true }));

    expect(card(fixture, 12).getAttribute('draggable')).toBe('true');
    drag('dragstart', card(fixture, 12));
    drag('drop', columns(fixture)[0]);
    drag('dragstart', card(fixture, 12));
    drag('drop', columns(fixture)[2]);
    drag('dragend', card(fixture, 12));

    expect(started.mock.calls.map(([dragged]) => dragged.id)).toEqual([12, 12]);
    expect(moved.mock.calls.map(([change]) => [change.task.id, change.targetStatusId])).toEqual([[12, 3]]);
    expect(ended).toHaveBeenCalledTimes(1);
  });

  it('without the right to change tasks offers no moving and ignores a drop', () => {
    const fixture = render();
    const moved = vi.fn();
    fixture.componentInstance.taskStatusChange.subscribe(moved);

    expect(el(fixture).querySelector('.kanban-move-actions')).toBeNull();
    expect(card(fixture, 11).getAttribute('draggable')).toBeNull();
    card(fixture, 11).dispatchEvent(new Event('dragstart', { bubbles: true, cancelable: true }));
    columns(fixture)[1].dispatchEvent(new Event('drop', { bubbles: true, cancelable: true }));
    expect(moved).not.toHaveBeenCalled();
  });

  it('on an empty board offers to reset the filters, or to create a task when none are on', () => {
    const filtered = render({ tasks: [], hasActiveFilters: true, canCreateTask: true });
    const reset = vi.fn();
    filtered.componentInstance.resetFilters.subscribe(reset);
    const recovery = el(filtered).querySelector('.kanban-empty-recovery') as HTMLElement;

    expect(text(recovery)).toBe('Задачи не найдены Сбросить все фильтры');
    (recovery.querySelector('button') as HTMLButtonElement).click();
    expect(reset).toHaveBeenCalledTimes(1);

    filtered.componentRef.setInput('hasActiveFilters', false);
    filtered.detectChanges();
    const create = vi.fn();
    filtered.componentInstance.createTask.subscribe(create);
    expect(text(el(filtered).querySelector('.kanban-empty-recovery button'))).toContain('Новая задача');
    (el(filtered).querySelector('.kanban-empty-recovery button') as HTMLButtonElement).click();
    expect(create).toHaveBeenCalledTimes(1);

    filtered.componentRef.setInput('isLoading', true);
    filtered.detectChanges();
    expect(el(filtered).querySelector('.kanban-empty-recovery')).toBeNull();
  });
});
