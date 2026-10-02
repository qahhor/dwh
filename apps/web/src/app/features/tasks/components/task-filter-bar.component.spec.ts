import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TaskStatus } from '@core/models/task.models';
import { ApiService } from '@core/services/api.service';
import { TaskLookupsService } from '../services/task-lookups.service';
import { TaskFilterBarComponent } from './task-filter-bar.component';

const STATUSES = [
  { id: 1, code: 's1', name: 'Новая', color: '#0284c7', terminal: false, sortOrder: 1 },
  { id: 3, code: 's3', name: 'Готово', color: '#16a34a', terminal: true, sortOrder: 2 },
] as TaskStatus[];

const PROJECTS = [
  { id: 5, name: 'Склад' },
  { id: 6, name: 'Логистика' },
];

/** The paged project list (plan 10/10, item 3.5): searched with q, one project read by id. */
const api = {
  get: vi.fn((path: string, params?: Record<string, unknown>) => {
    if (path === '/entities/ms.projects') {
      const text = String(params?.['q'] ?? '').toLowerCase();
      const items = PROJECTS.filter((project) => project.name.toLowerCase().includes(text));
      return of({ items, nextCursor: null, hasMore: false });
    }
    return of(PROJECTS.find((project) => path === `/entities/ms.projects/${project.id}`) ?? null);
  }),
};

function render(inputs: Record<string, unknown> = {}, before: () => void = () => undefined) {
  TestBed.configureTestingModule({
    imports: [TaskFilterBarComponent],
    providers: [{ provide: ApiService, useValue: api }],
  });
  before();
  const fixture = TestBed.createComponent(TaskFilterBarComponent);
  fixture.componentRef.setInput('statuses', STATUSES);
  for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
  fixture.detectChanges();
  return fixture;
}

const el = (fixture: ComponentFixture<TaskFilterBarComponent>) => fixture.nativeElement as HTMLElement;

function radios(fixture: ComponentFixture<TaskFilterBarComponent>, groupName: string): HTMLElement[] {
  const group = el(fixture).querySelector(`[role="radiogroup"][aria-label="${groupName}"]`) as HTMLElement;
  return [...group.querySelectorAll<HTMLElement>('[role="radio"]')];
}

const label = (radio: HTMLElement) => radio.querySelector('.smt-radio-group__label')?.textContent?.trim();

/** Opens the smt-select behind the trigger id and picks the option with the label. */
function choose(fixture: ComponentFixture<TaskFilterBarComponent>, triggerId: string, optionLabel: string) {
  (el(fixture).querySelector(`#${triggerId}`) as HTMLButtonElement).click();
  fixture.detectChanges();
  const option = [...document.querySelectorAll<HTMLElement>('.smt-select__option')].find(
    (item) => item.querySelector('.smt-select__option-label')?.textContent?.trim() === optionLabel,
  );
  if (!option) throw new Error(`no option "${optionLabel}" in ${triggerId}`);
  option.click();
  fixture.detectChanges();
}

describe('TaskFilterBarComponent', () => {
  afterEach(() => api.get.mockClear());

  it('shows the quick presets with the active one checked and reports the preset a person picks', () => {
    const fixture = render({ activePreset: 'my' });
    const presets = radios(fixture, 'Быстрые фильтры');
    const picked = vi.fn();
    fixture.componentInstance.activePresetChange.subscribe(picked);

    expect(presets.map(label)).toEqual([
      'Все задачи',
      'Мои задачи',
      'Я исполнитель',
      'Я наблюдатель',
      'Поручено мной',
      'Просроченные',
    ]);
    expect(presets.find((radio) => radio.getAttribute('aria-checked') === 'true')?.textContent).toContain('Мои задачи');

    presets[5].click();
    expect(picked).toHaveBeenCalledWith('overdue');
  });

  it('offers active, all and every status, and reports a status by its id', () => {
    const fixture = render();
    const chips = radios(fixture, 'Фильтр по статусу');
    const changed = vi.fn();
    fixture.componentInstance.statusFilterModeChange.subscribe(changed);

    expect(chips.map(label)).toEqual(['Активные', 'Все', 'Новая', 'Готово']);
    expect(chips[0].getAttribute('aria-checked')).toBe('true');

    chips[3].click();
    expect(changed).toHaveBeenCalledWith(3);
  });

  it('reports typed search text, a cleared field as a cleared search, and Enter as apply', () => {
    const fixture = render();
    const search = el(fixture).querySelector('#task-search') as HTMLInputElement;
    const typed = vi.fn();
    const cleared = vi.fn();
    const applied = vi.fn();
    fixture.componentInstance.searchQueryChange.subscribe(typed);
    fixture.componentInstance.searchClear.subscribe(cleared);
    fixture.componentInstance.searchApply.subscribe(applied);

    expect(el(fixture).querySelector('label[for="task-search"]')?.textContent?.trim()).toBe('Поиск задач');
    search.value = 'отчёт';
    search.dispatchEvent(new Event('input'));
    expect(typed).toHaveBeenCalledWith('отчёт');

    search.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    expect(applied).toHaveBeenCalledTimes(1);

    search.value = '';
    search.dispatchEvent(new Event('input'));
    expect(cleared).toHaveBeenCalledTimes(1);
    expect(typed).toHaveBeenCalledTimes(1);
  });

  it('reports the chosen project by id and the chosen priority by code', () => {
    const fixture = render();
    const project = vi.fn();
    const priority = vi.fn();
    fixture.componentInstance.selectedProjectIdChange.subscribe(project);
    fixture.componentInstance.selectedPriorityChange.subscribe(priority);

    choose(fixture, 'task-project-filter', 'Логистика');
    expect(project).toHaveBeenCalledWith(6);

    choose(fixture, 'task-priority-filter', 'Высокий');
    expect(priority).toHaveBeenCalledWith('high');
  });

  it('searches projects on the server 20 at a time, labelled for screen readers, never the whole list', () => {
    const fixture = render();
    const trigger = el(fixture).querySelector('#task-project-filter') as HTMLElement;
    expect(el(fixture).querySelector('label[for="task-project-filter"]')?.textContent?.trim()).toBe(
      'Фильтр по проекту',
    );
    expect(trigger.getAttribute('role')).toBe('combobox');

    trigger.click();
    fixture.detectChanges();
    const read = api.get.mock.calls.find(([path]) => path === '/entities/ms.projects');
    expect(read?.[1]).toEqual(expect.objectContaining({ limit: 20 }));
    expect(api.get.mock.calls.some(([path]) => path === '/tasks/projects')).toBe(false);
  });

  it('shows a chosen project by the name its tasks carry, without asking the server', () => {
    const fixture = render({ selectedProjectId: 9 }, () =>
      TestBed.inject(TaskLookupsService).retainTaskProjects([{ projectId: 9, projectName: 'Архив 2025' }]),
    );
    expect(el(fixture).querySelector('#task-project-filter')?.textContent).toContain('Архив 2025');
    expect(api.get).not.toHaveBeenCalled();
  });

  it('offers the named reset button only while a filter is on', () => {
    const fixture = render();
    expect(el(fixture).querySelector('.reset-filters-btn')).toBeNull();

    fixture.componentRef.setInput('hasActiveFilters', true);
    fixture.detectChanges();
    const reset = el(fixture).querySelector('.reset-filters-btn') as HTMLButtonElement;
    const resetFilters = vi.fn();
    fixture.componentInstance.resetFilters.subscribe(resetFilters);

    expect(reset.getAttribute('aria-label')).toBe('Сбросить все фильтры');
    reset.click();
    expect(resetFilters).toHaveBeenCalledTimes(1);
  });
});
