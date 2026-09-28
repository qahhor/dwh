import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { UserWorkload } from '../analytics.models';
import { AnalyticsWorkloadTableComponent } from './analytics-workload-table.component';

const person = (userId: number, userName: string, userLogin: string, assignedTasks: number, completedTasks: number) =>
  ({ userId, userName, userLogin, assignedTasks, completedTasks }) satisfies UserWorkload;

const TEAM = [
  person(1, 'Alice Smith', 'asmith', 5, 4),
  person(2, 'Bob Jones', 'bjones', 8, 2),
  person(3, 'Carol White', 'cwhite', 5, 0),
  person(4, 'Dan Brown', 'dbrown', 0, 0),
];

function render(options: { workload?: UserWorkload[]; loading?: boolean; error?: string } = {}) {
  const fixture = TestBed.createComponent(AnalyticsWorkloadTableComponent);
  fixture.componentRef.setInput('workload', options.workload ?? TEAM);
  fixture.componentRef.setInput('loading', options.loading ?? false);
  fixture.componentRef.setInput('error', options.error ?? '');
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const rows = () => [...host.querySelectorAll('[role="rowgroup"] > [role="row"]')] as HTMLElement[];
  const names = () => [...host.querySelectorAll('.user-name-text')].map((node) => node.textContent?.trim());
  const search = (text: string) => {
    const field = host.querySelector('.user-search-box input') as HTMLInputElement;
    field.value = text;
    field.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  };
  return { fixture, host, rows, names, search };
}

describe('AnalyticsWorkloadTableComponent', () => {
  it('lists the busiest people first, by name on a tie, in a named table', () => {
    const { host, names } = render();

    expect(names()).toEqual(['Bob Jones', 'Alice Smith', 'Carol White', 'Dan Brown']);
    expect(host.querySelector('[role="table"], table')?.getAttribute('aria-label')).toBe(
      'Утилизация и загрузка команды',
    );
    const region = host.querySelector('.table-scroll') as HTMLElement;
    expect(region.getAttribute('role')).toBe('region');
    expect(region.getAttribute('tabindex')).toBe('0');
  });

  it('shows the share of done tasks, with no bar for a person with nothing assigned', () => {
    const { rows } = render();
    const efficiency = (row: HTMLElement) => row.querySelector('smt-badge')?.textContent?.trim();

    expect(rows().map(efficiency)).toEqual(['25%', '80%', '0%', '0%']);
    expect(rows()[1].querySelector('.eff-mini-bar-bg')).not.toBeNull();
    expect(rows()[3].querySelector('.eff-mini-bar-bg')).toBeNull();
  });

  it('finds a person by a part of the name or the login', () => {
    const { names, search } = render();

    search('smith');
    expect(names()).toEqual(['Alice Smith']);

    search('CWH');
    expect(names()).toEqual(['Carol White']);
  });

  it('sorts every person by a column header click', () => {
    const { fixture, host, names } = render();
    const header = [...host.querySelectorAll('[role="columnheader"]')][0] as HTMLElement;

    header.querySelector('smt-cell-header')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();

    expect(names()).toEqual(['Alice Smith', 'Bob Jones', 'Carol White', 'Dan Brown']);
    expect(header.getAttribute('aria-sort')).toBe('ascending');
  });

  it('says there is no workload data only when nothing is loading and nothing failed', () => {
    const { fixture, host } = render({ workload: [] });
    expect(host.querySelector('.user-search-box')).toBeNull();
    expect(host.querySelector('.empty')?.textContent).toContain('Данные по загрузке сотрудников отсутствуют.');

    fixture.componentRef.setInput('loading', true);
    fixture.detectChanges();
    expect(host.querySelector('.empty')).toBeNull();

    fixture.componentRef.setInput('loading', false);
    fixture.componentRef.setInput('error', 'Ошибка');
    fixture.detectChanges();
    expect(host.querySelector('.empty')).toBeNull();
  });
});
