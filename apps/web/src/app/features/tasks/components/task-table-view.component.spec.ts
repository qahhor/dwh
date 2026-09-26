import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { PACKAGED_RUSSIAN } from '../../../core/i18n/packaged-russian';
import { Task, TaskStatus } from '../../../core/models/task.models';
import { ApiService } from '../../../core/services/api.service';
import { ToastService } from '../../../core/services/toast.service';
import { KeysetPager } from '../../../shared/paging/keyset-pager';
import { TaskTableViewComponent } from './task-table-view.component';
import { TASKS_META } from '../../../../testing/registry-meta';

const TASKS = [
  { id: 11, title: 'Отчёт за январь', priority: 'medium', statusId: 1, endTime: null, projectId: null, parentTaskId: null },
  { id: 12, title: 'Сверка остатков', priority: 'low', statusId: 1, endTime: null, projectId: null, parentTaskId: null }
] as unknown as Task[];

const STATUSES = [
  { id: 1, name: 'Новая' },
  { id: 3, name: 'Готово' }
] as unknown as TaskStatus[];

async function render(options: { canUpdate?: boolean; post?: ReturnType<typeof vi.fn> } = {}) {
  const post = options.post ?? vi.fn(() => of({ action: 'status', succeeded: 2, failed: 0, results: [] }));
  const toast = { success: vi.fn(), error: vi.fn() };
  await TestBed.configureTestingModule({
    imports: [TaskTableViewComponent],
    providers: [
      { provide: ApiService, useValue: { post } },
      { provide: ToastService, useValue: toast }
    ]
  }).compileComponents();
  const fixture = TestBed.createComponent(TaskTableViewComponent);
  const pager = new KeysetPager<Task>(() => of({ items: TASKS, nextCursor: null }), { pageSize: 20 });
  const reload = vi.spyOn(pager, 'reload');
  const component = fixture.componentInstance;
  component.pager = pager;
  fixture.componentRef.setInput('meta', TASKS_META);
  component.statuses = STATUSES;
  component.canUpdateTask = options.canUpdate ?? true;
  component.isOverdue = () => false;
  component.getTypeColor = () => '';
  component.getTypeBg = () => '';
  component.getTypeIcon = () => 'task';
  component.getTypeLabel = () => '';
  component.getProjectName = () => null;
  component.getStatusColor = () => '';
  component.getDeadlineInfo = () => ({ state: 'none', label: '' });
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
  const option = ([...document.querySelectorAll('.smt-select__option')] as HTMLElement[])
    .find(item => item.querySelector('.smt-select__option-label')?.textContent?.trim() === label);
  if (!option) throw new Error(`no option "${label}" in ${testId}`);
  option.click();
  fixture.detectChanges();
}
const chosen = (fixture: ComponentFixture<TaskTableViewComponent>, testId: string) =>
  el(fixture).querySelector(`[data-testid="${testId}"] :is(.smt-select__value, .smt-select__placeholder)`)?.textContent?.trim();
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
    rowChecks(fixture).forEach(check => { check.click(); fixture.detectChanges(); });
    expect(el(fixture).querySelector('[data-testid="bulk-bar"]')?.textContent).toContain('Выбрано: 2');

    const button = el(fixture).querySelector('button[data-testid="bulk-status-apply"]') as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    choose(fixture, 'bulk-status', 'Готово');
    expect(chosen(fixture, 'bulk-status')).toBe('Готово');
    expect(button.disabled).toBe(false);
    apply(fixture, 'bulk-status-apply');

    expect(post).toHaveBeenCalledWith('/tasks/bulk', { action: 'status', ids: [11, 12], params: { statusId: 3 } }, { notifyError: false });
    expect(toast.success).toHaveBeenCalledWith('Изменено задач: 2');
    expect(reload).toHaveBeenCalledTimes(1);
    expect(fixture.componentInstance.bulkStatusId()).toBeNull();
  });

  it('names the tasks that could not be changed, with the reason', async () => {
    const post = vi.fn((..._args: unknown[]) => of({
      action: 'priority', succeeded: 1, failed: 1,
      results: [
        { id: 11, ok: true, code: null, message: null },
        { id: 12, ok: false, code: 'task_not_found', message: 'Задача не найдена' }
      ]
    }));
    const { fixture } = await render({ post });
    rowChecks(fixture).forEach(check => { check.click(); fixture.detectChanges(); });
    choose(fixture, 'bulk-priority', PACKAGED_RUSSIAN['task.priority.high']);
    apply(fixture, 'bulk-priority-apply');

    expect(post.mock.calls[0][1]).toEqual({ action: 'priority', ids: [11, 12], params: { priority: 'high' } });
    const failure = document.querySelector('[data-testid="bulk-result-failure"]')?.textContent?.replace(/\s+/g, ' ').trim();
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
    expect(fixture.componentInstance.bulkStatusId()).toBe(3);
    expect(fixture.componentInstance.selectedTasks().map(task => task.id)).toEqual([11]);
  });
});
