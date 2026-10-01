import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { TaskStatus } from '@core/models/task.models';
import { ApiService } from '@core/services/api.service';
import { TASKS_META, registryProviders } from '@testing/registry-meta';
import { TaskDictionariesService } from './task-dictionaries.service';
import { TaskListStore } from './task-list.store';
import { TaskPresenter } from './task-presenter';

const STATUSES: TaskStatus[] = [
  { id: 1, name: 'В работе', orderNo: 1, isTerminal: false },
  { id: 2, name: 'Готово', orderNo: 2, isTerminal: true },
];

function setup() {
  TestBed.configureTestingModule({
    providers: [
      TaskListStore,
      TaskPresenter,
      { provide: ApiService, useValue: { get: () => of([]) } },
      ...registryProviders(TASKS_META),
    ],
  });
  TestBed.inject(TaskDictionariesService).statuses.set(STATUSES);
  return TestBed.inject(TaskPresenter);
}

const daysFromNow = (days: number) => new Date(Date.now() + days * 86_400_000).toISOString();

describe('TaskPresenter', () => {
  it('names the deadline of an open task: overdue, today, tomorrow, or none', () => {
    const presenter = setup();

    const overdue = presenter.getDeadlineInfo(daysFromNow(-2), 1);
    expect(overdue.state).toBe('overdue');
    expect(overdue.label).toContain('Просрочено');
    const today = presenter.getDeadlineInfo(new Date().toISOString(), 1);
    expect(today.state).toBe('today');
    expect(today.label).toContain('Сегодня');
    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    expect(presenter.getDeadlineInfo(tomorrow.toISOString(), 1)).toEqual(
      expect.objectContaining({ state: 'tomorrow', label: 'Завтра' }),
    );
    expect(presenter.getDeadlineInfo(null, 1)).toEqual(expect.objectContaining({ state: 'none', label: '—' }));
  });

  it('does not call a finished task overdue, and names its status', () => {
    const presenter = setup();

    expect(presenter.isOverdue(daysFromNow(-2), 1)).toBe(true);
    expect(presenter.isOverdue(daysFromNow(-2), 2)).toBe(false);
    expect(presenter.getStatusName(2)).toBe('Готово');
  });

  it('names a project by the name the task carries, by number without it, and nothing without a project', () => {
    const presenter = setup();

    expect(presenter.getProjectName({ projectId: 5, projectName: 'Склад' })).toBe('Склад');
    expect(presenter.getProjectName({ projectId: 5, projectName: null })).toBe('#5');
    expect(presenter.getProjectName({ projectId: null })).toBeNull();
  });
});
