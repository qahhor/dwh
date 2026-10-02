import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { PACKAGED_RUSSIAN } from '@core/i18n/packaged-russian';
import { Task, TaskStatus } from '@core/models/task.models';
import { ApiService } from '@core/services/api.service';
import { ToastService } from '@core/services/toast.service';
import { KeysetPager } from '@shared/paging/keyset-pager';
import { TaskTableViewComponent } from './task-table-view.component';
import { TASKS_META } from '@testing/registry-meta';

const TASKS = [
  {
    id: 11,
    title: 'Отчёт за январь',
    priority: 'medium',
    statusCode: 'new',
    endTime: null,
    projectId: null,
    parentTaskId: null,
  },
  {
    id: 12,
    title: 'Сверка остатков',
    priority: 'low',
    statusCode: 'new',
    endTime: null,
    projectId: null,
    parentTaskId: null,
  },
] as unknown as Task[];

const STATUSES = [
  { id: 1, code: 'new', name: 'Новая' },
  { id: 3, code: 'done', name: 'Готово' },
] as unknown as TaskStatus[];

async function render(
  options: { canUpdate?: boolean; post?: ReturnType<typeof vi.fn>; inputs?: Record<string, unknown> } = {},
) {
  const post = options.post ?? vi.fn(() => of({ action: 'status', succeeded: 2, failed: 0, results: [] }));
  const toast = { success: vi.fn(), error: vi.fn() };
  await TestBed.configureTestingModule({
    imports: [TaskTableViewComponent],
    providers: [
      { provide: ApiService, useValue: { post } },
      { provide: ToastService, useValue: toast },
    ],
  }).compileComponents();
  const fixture = TestBed.createComponent(TaskTableViewComponent);
  const pager = new KeysetPager<Task>(() => of({ items: TASKS, nextCursor: null }), { pageSize: 20 });
  const reload = vi.spyOn(pager, 'reload');
  fixture.componentRef.setInput('pager', pager);
  fixture.componentRef.setInput('meta', TASKS_META);
  fixture.componentRef.setInput('statuses', STATUSES);
  fixture.componentRef.setInput('canUpdateTask', options.canUpdate ?? true);
  fixture.componentRef.setInput('isOverdue', () => false);
  fixture.componentRef.setInput('getTypeColor', () => '');
  fixture.componentRef.setInput('getTypeBg', () => '');
  fixture.componentRef.setInput('getTypeIcon', () => 'task');
  fixture.componentRef.setInput('getTypeLabel', () => '');
  fixture.componentRef.setInput('getProjectName', () => null);
  fixture.componentRef.setInput('getStatusColor', () => '');
  fixture.componentRef.setInput('getDeadlineInfo', () => ({ state: 'none', label: '' }));
  for (const [name, value] of Object.entries(options.inputs ?? {})) fixture.componentRef.setInput(name, value);
  fixture.detectChanges();
  pager.first();
  fixture.detectChanges();
  return { fixture, post, toast, reload };
}

const el = (fixture: ComponentFixture<TaskTableViewComponent>) => fixture.nativeElement as HTMLElement;
const rowChecks = (fixture: ComponentFixture<TaskTableViewComponent>) =>
  [...el(fixture).querySelectorAll('[role="rowgroup"] input[type="checkbox"]')] as HTMLInputElement[];
/** Opens the smt-select with the test id and picks the option with the label. */
function choose(fixture: ComponentFixture<TaskTableViewComponent>, testId: string, label: string) {
  (el(fixture).querySelector(`[data-testid="${testId}"] [role="combobox"]`) as HTMLButtonElement).click();
  fixture.detectChanges();
  const option = ([...document.querySelectorAll('.smt-select__option')] as HTMLElement[]).find(
    (item) => item.querySelector('.smt-select__option-label')?.textContent?.trim() === label,
  );
  if (!option) throw new Error(`no option "${label}" in ${testId}`);
  option.click();
  fixture.detectChanges();
}
const chosen = (fixture: ComponentFixture<TaskTableViewComponent>, testId: string) =>
  el(fixture)
    .querySelector(`[data-testid="${testId}"] :is(.smt-select__value, .smt-select__placeholder)`)
    ?.textContent?.trim();
function apply(fixture: ComponentFixture<TaskTableViewComponent>, testId: string) {
  (el(fixture).querySelector(`button[data-testid="${testId}"]`) as HTMLButtonElement).click();
  fixture.detectChanges();
}

describe('TaskTableViewComponent bulk actions', () => {
  it('without the right to change tasks has no row checkboxes', async () => {
    const { fixture } = await render({ canUpdate: false });
    expect(rowChecks(fixture)).toHaveLength(0);
  });

  it('sets one status on every chosen task, reports the count and reloads the page', async () => {
    const { fixture, post, toast, reload } = await render();
    rowChecks(fixture).forEach((check) => {
      check.click();
      fixture.detectChanges();
    });
    expect(el(fixture).querySelector('[data-testid="bulk-bar"]')?.textContent).toContain('Выбрано: 2');

    const button = el(fixture).querySelector('button[data-testid="bulk-status-apply"]') as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    choose(fixture, 'bulk-status', 'Готово');
    expect(chosen(fixture, 'bulk-status')).toBe('Готово');
    expect(button.disabled).toBe(false);
    apply(fixture, 'bulk-status-apply');

    expect(post).toHaveBeenCalledWith(
      '/entities/ms.tasks/bulk',
      { action: 'set_status', ids: [11, 12], params: { status: 'done' } },
      { notifyError: false },
    );
    expect(toast.success).toHaveBeenCalledWith('Изменено задач: 2');
    expect(reload).toHaveBeenCalledTimes(1);
    expect(fixture.componentInstance.bulkStatusCode()).toBeNull();
  });

  it('names the tasks that could not be changed, with the reason', async () => {
    const post = vi.fn((..._args: unknown[]) =>
      of({
        action: 'priority',
        succeeded: 1,
        failed: 1,
        results: [
          { id: 11, ok: true, code: null, message: null },
          { id: 12, ok: false, code: 'task_not_found', message: 'Задача не найдена' },
        ],
      }),
    );
    const { fixture } = await render({ post });
    rowChecks(fixture).forEach((check) => {
      check.click();
      fixture.detectChanges();
    });
    choose(fixture, 'bulk-priority', PACKAGED_RUSSIAN['task.priority.high']);
    apply(fixture, 'bulk-priority-apply');

    expect(post.mock.calls[0][1]).toEqual({ action: 'update', ids: [11, 12], params: { priority: 'high' } });
    const failure = document
      .querySelector('[data-testid="bulk-result-failure"]')
      ?.textContent?.replace(/\s+/g, ' ')
      .trim();
    expect(failure).toBe('#12 Сверка остатков: Задача не найдена');
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain(PACKAGED_RUSSIAN['ui.bulk.result_title']);
  });

  it('says so when the request itself fails and keeps the choice', async () => {
    const { fixture, toast, reload } = await render({ post: vi.fn(() => throwError(() => ({ status: 503 }))) });
    rowChecks(fixture)[0].click();
    fixture.detectChanges();
    choose(fixture, 'bulk-status', 'Готово');
    apply(fixture, 'bulk-status-apply');

    expect(toast.error).toHaveBeenCalledWith(PACKAGED_RUSSIAN['tasks.bulk.error']);
    expect(reload).not.toHaveBeenCalled();
    expect(fixture.componentInstance.bulkStatusCode()).toBe('done');
    expect(fixture.componentInstance.selectedTasks().map((task) => task.id)).toEqual([11]);
  });
});

describe('TaskTableViewComponent rows', () => {
  const rows = (fixture: ComponentFixture<TaskTableViewComponent>) =>
    [...el(fixture).querySelectorAll('[role="rowgroup"] > [role="row"]')] as HTMLElement[];

  it('opens a task from its row or its named title button, never from the row controls', async () => {
    const { fixture } = await render();
    const opened: number[] = [];
    fixture.componentInstance.openTaskDetails.subscribe((task) => opened.push(task.id));
    const row = rows(fixture)[0];
    const title = row.querySelector('.task-title-open') as HTMLButtonElement;

    // A plain row: the title button is the keyboard way in, the row is not a stop of its own.
    expect(row.getAttribute('tabindex')).toBeNull();
    expect(title.type).toBe('button');
    expect(title.getAttribute('aria-label')).toBe('Открыть задачу #11: Отчёт за январь');
    (row.querySelector('.inline-priority-select [role="combobox"]') as HTMLButtonElement).click();
    const status = row.querySelector('.inline-status-select [role="combobox"]') as HTMLButtonElement;
    status.click();
    status.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    expect(opened).toEqual([]);

    (row.querySelector('.task-type-badge') as HTMLElement).click();
    title.click();
    expect(opened).toEqual([11, 11]);
  });

  it('marks an overdue row and shows the status by name beside its colour dot', async () => {
    const { fixture } = await render({
      inputs: {
        isOverdue: () => true,
        getStatusColor: () => '#ff0000',
        getDeadlineInfo: () => ({ state: 'overdue', label: 'Просрочено на 2 дн.' }),
        getProjectName: () => 'Склад',
      },
    });
    const status = el(fixture).querySelector('.table-status') as HTMLElement;
    const select = el(fixture).querySelector('.inline-status-select [role="combobox"]') as HTMLButtonElement;

    expect(rows(fixture).every((row) => row.classList.contains('task-row-overdue'))).toBe(true);
    expect(el(fixture).querySelector('.deadline-pill.overdue')?.textContent).toContain('Просрочено на 2 дн.');
    expect(el(fixture).querySelector('.project-tag')?.textContent).toContain('Склад');
    expect(status.textContent).toContain('Новая');
    expect(status.style.color).toBe('');
    expect((status.querySelector('.status-dot') as HTMLElement).style.backgroundColor).toBe('rgb(255, 0, 0)');
    (select.closest('[role="cell"]') as HTMLElement).style.color = 'rgb(255, 0, 0)';
    expect(getComputedStyle(select).color).toBe('var(--text-main)');
  });
});
