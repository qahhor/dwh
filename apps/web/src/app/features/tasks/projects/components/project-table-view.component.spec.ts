import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ProjectTaskStats } from '@core/models/task.models';
import { ApiService } from '@core/services/api.service';
import { KeysetPager } from '@shared/paging/keyset-pager';
import { PROJECTS_META, registryProviders } from '@testing/registry-meta';
import { ProjectListItem } from '../projects.models';
import { ProjectTableViewComponent } from './project-table-view.component';

const PROJECTS: ProjectListItem[] = [
  { id: 1, name: 'Склад', description: 'Учёт остатков', state: 'A', createdAt: '2026-09-01T00:00:00Z' },
  { id: 2, name: 'Архив 2025', state: 'P', createdAt: '2026-01-15T00:00:00Z' },
];

const STATS: Record<number, ProjectTaskStats> = {
  1: { projectId: 1, totalTasks: 4, activeTasks: 1, doneTasks: 3 },
};

async function render(inputs: Record<string, unknown> = {}, items: ProjectListItem[] = PROJECTS) {
  await TestBed.configureTestingModule({
    imports: [ProjectTableViewComponent],
    providers: [{ provide: ApiService, useValue: { get: vi.fn(() => of([])) } }, ...registryProviders(PROJECTS_META)],
  }).compileComponents();
  const fixture = TestBed.createComponent(ProjectTableViewComponent);
  const pager = new KeysetPager<ProjectListItem>(() => of({ items, nextCursor: null }), { pageSize: 20 });
  fixture.componentRef.setInput('pager', pager);
  fixture.componentRef.setInput('meta', PROJECTS_META);
  for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
  fixture.detectChanges();
  pager.first();
  fixture.detectChanges();
  return fixture;
}

const el = (fixture: ComponentFixture<ProjectTableViewComponent>) => fixture.nativeElement as HTMLElement;
const text = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
const rows = (fixture: ComponentFixture<ProjectTableViewComponent>) => [
  ...el(fixture).querySelectorAll<HTMLElement>('[role="rowgroup"] [role="row"]'),
];
const named = (fixture: ComponentFixture<ProjectTableViewComponent>, name: string) =>
  el(fixture).querySelector(`button[aria-label="${name}"]`) as HTMLButtonElement | null;

describe('ProjectTableViewComponent', () => {
  it('lists the projects of the page in a named table with their state', async () => {
    const fixture = await render();

    expect(el(fixture).querySelector('.table-card')?.getAttribute('aria-label')).toBe('Таблица проектов');
    expect(el(fixture).querySelector('[role="table"]')?.getAttribute('aria-label')).toBe('Список проектов');
    expect(rows(fixture)).toHaveLength(2);
    expect(text(rows(fixture)[0].querySelector('.project-name-text'))).toBe('Склад');
    expect(text(rows(fixture)[0].querySelector('.project-desc-line'))).toBe('Учёт остатков');
    expect(rows(fixture).map((row) => text(row.querySelector('.status-pill')))).toEqual(['Активен', 'В архиве']);
  });

  it('shows closed tasks as a named progress bar once the statistics are in, and says so otherwise', async () => {
    const fixture = await render({ canViewTasks: true, projectStats: STATS, statsLoaded: true });
    const bar = rows(fixture)[0].querySelector('[role="progressbar"]') as HTMLElement;

    expect(text(rows(fixture)[0].querySelector('.progress-count'))).toBe('3 / 4 закрыто');
    expect(bar.getAttribute('aria-valuenow')).toBe('75');
    expect(bar.getAttribute('aria-label')).toBe('Доля закрытых задач проекта Склад');
    expect(text(rows(fixture)[1].querySelector('.stats-unknown'))).toBe('Статистика недоступна');
  });

  it('opens the project tasks from its name and its named tasks button', async () => {
    const fixture = await render({ canViewTasks: true });
    const opened = vi.fn();
    fixture.componentInstance.viewTasks.subscribe(opened);

    (rows(fixture)[0].querySelector('button.project-name') as HTMLButtonElement).click();
    named(fixture, 'Открыть задачи проекта Архив 2025')?.click();

    expect(opened.mock.calls.map(([project]) => project.id)).toEqual([1, 2]);
  });

  it('without the right to view tasks shows plain names and no way to the tasks', async () => {
    const fixture = await render();

    expect(el(fixture).querySelector('button.project-name')).toBeNull();
    expect(text(rows(fixture)[0].querySelector('.project-name-text'))).toBe('Склад');
    expect(named(fixture, 'Открыть задачи проекта Склад')).toBeNull();
  });

  it('gives someone who may change projects named edit and members buttons', async () => {
    const fixture = await render({ canUpdateProject: true });
    const edited = vi.fn();
    const members = vi.fn();
    fixture.componentInstance.editProject.subscribe(edited);
    fixture.componentInstance.manageMembers.subscribe(members);

    named(fixture, 'Редактировать проект Склад')?.click();
    named(fixture, 'Участники проекта Архив 2025')?.click();

    expect(edited).toHaveBeenCalledWith(PROJECTS[0]);
    expect(members).toHaveBeenCalledWith(PROJECTS[1]);
  });

  it('says no projects were found for an empty page, and shows no table before the field list', async () => {
    const empty = await render({}, []);
    expect(text(el(empty).querySelector('.empty-state-cell p'))).toBe('Проекты не найдены');

    empty.componentRef.setInput('meta', null);
    empty.detectChanges();
    expect(el(empty).querySelector('ui-server-table')).toBeNull();
  });
});
