import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { Project, ProjectTaskStats } from '@core/models/task.models';
import { ProjectCardsViewComponent } from './project-cards-view.component';

const PROJECTS: Project[] = [
  { id: 1, name: 'Склад', description: 'Учёт остатков', state: 'A', createdAt: '2026-09-01T00:00:00Z' },
  { id: 2, name: 'Архив 2025', state: 'P', createdAt: '2026-01-15T00:00:00Z' },
];

const STATS: Record<number, ProjectTaskStats> = {
  1: { projectId: 1, totalTasks: 4, activeTasks: 1, doneTasks: 3 },
};

function render(inputs: Record<string, unknown> = {}) {
  TestBed.configureTestingModule({ imports: [ProjectCardsViewComponent] });
  const fixture = TestBed.createComponent(ProjectCardsViewComponent);
  fixture.componentRef.setInput('paginatedProjects', PROJECTS);
  fixture.componentRef.setInput('totalCount', PROJECTS.length);
  for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
  fixture.detectChanges();
  return fixture;
}

const cards = (fixture: ComponentFixture<ProjectCardsViewComponent>) => [
  ...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('.project-card'),
];
const text = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();

describe('ProjectCardsViewComponent', () => {
  it('shows each project with its state and description, or says the description is missing', () => {
    const fixture = render();
    const [first, second] = cards(fixture);

    expect(cards(fixture)).toHaveLength(2);
    expect(text(first.querySelector('.project-title'))).toBe('Склад');
    expect(text(first.querySelector('.status-pill'))).toBe('Активен');
    expect(text(first.querySelector('.project-desc'))).toBe('Учёт остатков');
    expect(text(second.querySelector('.status-pill'))).toBe('В архиве');
    expect(text(second.querySelector('.project-desc'))).toBe('Описание проекта отсутствует');
  });

  it('shows closed tasks as a named progress bar and opens the project tasks from the card', () => {
    const fixture = render({ canViewTasks: true, projectStats: STATS, statsLoaded: true });
    const [first, second] = cards(fixture);
    const opened = vi.fn();
    fixture.componentInstance.viewTasks.subscribe(opened);

    const bar = first.querySelector('[role="progressbar"]') as HTMLElement;
    expect(text(first.querySelector('.progress-count'))).toBe('3 / 4 закрыто');
    expect(bar.getAttribute('aria-valuenow')).toBe('75');
    expect(bar.getAttribute('aria-label')).toBe('Доля закрытых задач проекта Склад');
    expect(text(second.querySelector('.stats-unknown'))).toBe('Статистика недоступна');

    (first.querySelector('.view-tasks-link') as HTMLButtonElement).click();
    (second.querySelector('.project-title-btn') as HTMLButtonElement).click();
    expect(opened.mock.calls.map(([project]) => project.id)).toEqual([1, 2]);
  });

  it('shows no progress while the statistics failed to load', () => {
    const fixture = render({ canViewTasks: true, projectStats: STATS, statsLoaded: true, statsLoadError: true });

    expect(cards(fixture)[0].querySelector('[role="progressbar"]')).toBeNull();
    expect(text(cards(fixture)[0].querySelector('.stats-unknown'))).toBe('Статистика недоступна');
  });

  it('without the right to view tasks shows the name as plain text and no task figures', () => {
    const fixture = render({ projectStats: STATS, statsLoaded: true });

    expect(cards(fixture)[0].querySelector('.project-title-btn')).toBeNull();
    expect(text(cards(fixture)[0].querySelector('.project-name-text'))).toBe('Склад');
    expect(cards(fixture)[0].querySelector('.card-progress, .stats-unknown, .view-tasks-link')).toBeNull();
  });

  it('gives someone who may change projects named edit and members buttons', () => {
    const readOnly = render();
    expect(cards(readOnly)[0].querySelector('.edit-btn')).toBeNull();
    TestBed.resetTestingModule();

    const fixture = render({ canUpdateProject: true });
    const edited = vi.fn();
    const members = vi.fn();
    fixture.componentInstance.editProject.subscribe(edited);
    fixture.componentInstance.manageMembers.subscribe(members);
    const buttons = [...cards(fixture)[1].querySelectorAll<HTMLButtonElement>('.edit-btn')];

    expect(buttons.map((button) => button.getAttribute('aria-label'))).toEqual([
      'Участники проекта Архив 2025',
      'Редактировать проект Архив 2025',
    ]);
    buttons[0].click();
    buttons[1].click();
    expect(members).toHaveBeenCalledWith(PROJECTS[1]);
    expect(edited).toHaveBeenCalledWith(PROJECTS[1]);
  });

  it('says no projects were found for an empty list, and asks for the next page by number', () => {
    const empty = render({ paginatedProjects: [], totalCount: 0 });
    expect(text(empty.nativeElement.querySelector('.empty-projects-cell p'))).toBe('Проекты не найдены');
    TestBed.resetTestingModule();

    const fixture = render({ currentPage: 2, hasNextPage: true });
    const paged = vi.fn();
    fixture.componentInstance.pageChange.subscribe(paged);
    (fixture.nativeElement.querySelector('button[aria-label="Следующая страница"]') as HTMLButtonElement).click();
    expect(paged).toHaveBeenCalledWith(3);
  });
});
