import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { Project, TaskStatus } from '@core/models/task.models';
import { TaskFilterBarComponent } from './task-filter-bar.component';

const STATUSES = [
  { id: 1, name: 'Новая', color: '#0284c7', isTerminal: false, orderNo: 1 },
  { id: 3, name: 'Готово', color: '#16a34a', isTerminal: true, orderNo: 2 },
] as TaskStatus[];

const PROJECTS = [
  { id: 5, name: 'Склад', state: 'A', createdAt: '2026-09-01T00:00:00Z' },
  { id: 6, name: 'Логистика', state: 'A', createdAt: '2026-09-01T00:00:00Z' },
] as Project[];

function render(inputs: Record<string, unknown> = {}) {
  TestBed.configureTestingModule({ imports: [TaskFilterBarComponent] });
  const fixture = TestBed.createComponent(TaskFilterBarComponent);
  fixture.componentRef.setInput('statuses', STATUSES);
  fixture.componentRef.setInput('projects', PROJECTS);
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
