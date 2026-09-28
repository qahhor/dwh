import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { ProjectDistribution } from '../analytics.models';
import { AnalyticsProjectsCardComponent } from './analytics-projects-card.component';

const project = (projectId: number, projectName: string, completedTasks: number, totalTasks: number) =>
  ({
    projectId,
    projectName,
    totalTasks,
    completedTasks,
    activeTasks: totalTasks - completedTasks,
    progressPercent: Math.round((completedTasks / totalTasks) * 100),
  }) satisfies ProjectDistribution;

const PROJECTS = [
  project(1, 'Alpha ERP', 5, 10),
  project(2, 'Beta CRM', 6, 8),
  project(3, 'Gamma DWH', 4, 4),
  project(4, 'Delta Mobile', 0, 12),
];

function render(options: { projects?: ProjectDistribution[]; loading?: boolean; error?: string } = {}) {
  const fixture = TestBed.createComponent(AnalyticsProjectsCardComponent);
  fixture.componentRef.setInput('projects', options.projects ?? PROJECTS);
  fixture.componentRef.setInput('loading', options.loading ?? false);
  fixture.componentRef.setInput('error', options.error ?? '');
  const clicked = vi.fn();
  fixture.componentInstance.projectClick.subscribe(clicked);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const names = () => [...host.querySelectorAll('.project-name')].map((node) => node.textContent?.trim());
  const search = (text: string) => {
    const field = host.querySelector('.project-search-box input') as HTMLInputElement;
    field.value = text;
    field.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  };
  return { fixture, host, clicked, names, search };
}

describe('AnalyticsProjectsCardComponent', () => {
  it('shows every project with its progress and done out of total', () => {
    const { host, names } = render();

    expect(names()).toEqual(['Alpha ERP', 'Beta CRM', 'Gamma DWH', 'Delta Mobile']);
    const first = host.querySelector('.project-item') as HTMLElement;
    expect(first.querySelector('.project-pct')?.textContent?.trim()).toBe('50%');
    expect(first.querySelector('.project-tasks-count')?.textContent?.trim()).toBe('(5/10)');
  });

  it('narrows the list by a part of the project name, ignoring case', () => {
    const { host, names, search } = render();
    expect(host.querySelector('.project-search-box input')?.getAttribute('aria-label')).toBe('Поиск проекта…');

    search('crm');

    expect(names()).toEqual(['Beta CRM']);
  });

  it('offers the search only when there are more than three projects', () => {
    const { host } = render({ projects: PROJECTS.slice(0, 3) });

    expect(host.querySelector('.project-search-box')).toBeNull();
  });

  it('asks to open the project a person clicks', () => {
    const { host, clicked } = render();

    ([...host.querySelectorAll('.project-item')] as HTMLElement[])[2].click();

    expect(clicked).toHaveBeenCalledWith(3);
  });

  it('says no project was found when the search matches nothing', () => {
    const { host, search } = render();

    search('Omega');

    expect(host.querySelector('.project-item')).toBeNull();
    expect(host.querySelector('.empty-chart')?.textContent).toContain('Активные проекты не найдены');
  });

  it('keeps the empty message away while loading or after an error', () => {
    const { fixture, host } = render({ projects: [], loading: true });
    expect(host.querySelector('.empty-chart')).toBeNull();

    fixture.componentRef.setInput('loading', false);
    fixture.componentRef.setInput('error', 'Ошибка');
    fixture.detectChanges();
    expect(host.querySelector('.empty-chart')).toBeNull();

    fixture.componentRef.setInput('error', '');
    fixture.detectChanges();
    expect(host.querySelector('.empty-chart')).not.toBeNull();
  });
});
