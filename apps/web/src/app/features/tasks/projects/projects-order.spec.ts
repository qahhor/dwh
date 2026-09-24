import { describe, expect, it } from 'vitest';
import { Project, ProjectTaskStats } from '../../../core/models/task.models';
import { OrderBy } from '../../../shared/ui-kit/components/table/table.types';
import { sortProjects } from './projects-order';

const project = (id: number, name: string, state: 'A' | 'P' = 'A', createdAt = `2026-09-0${id}T00:00:00Z`): Project =>
  ({ id, name, state, createdAt });

const projects = [project(3, 'Склад 10'), project(1, 'склад 2', 'P'), project(2, 'Автопарк')];
const ids = (list: Project[]) => list.map(item => item.id);

describe('sortProjects', () => {
  it('keeps the server order when nothing is chosen, without touching the input', () => {
    const sorted = sortProjects(projects, undefined, {}, 'ru');
    expect(ids(sorted)).toEqual([3, 1, 2]);
    expect(sorted).not.toBe(projects);
  });

  it('orders names by letters and numbers the way people read them', () => {
    expect(ids(sortProjects(projects, { column: 'name', sortBy: OrderBy.Asc }, {}, 'ru'))).toEqual([2, 1, 3]);
    expect(ids(sortProjects(projects, { column: 'name', sortBy: OrderBy.Desc }, {}, 'ru'))).toEqual([3, 1, 2]);
  });

  it('orders by id, creation date and state, active first when ascending', () => {
    expect(ids(sortProjects(projects, { column: 'id', sortBy: OrderBy.Desc }, {}, 'ru'))).toEqual([3, 2, 1]);
    expect(ids(sortProjects(projects, { column: 'created', sortBy: OrderBy.Asc }, {}, 'ru'))).toEqual([1, 2, 3]);
    expect(ids(sortProjects(projects, { column: 'state', sortBy: OrderBy.Asc }, {}, 'ru'))).toEqual([2, 3, 1]);
  });

  it('puts projects with unknown statistics last in either direction', () => {
    const stats: Record<number, ProjectTaskStats> = {
      1: { projectId: 1, totalTasks: 4, activeTasks: 1, doneTasks: 3 },
      3: { projectId: 3, totalTasks: 0, activeTasks: 0, doneTasks: 0 },
    };
    expect(ids(sortProjects(projects, { column: 'progress', sortBy: OrderBy.Asc }, stats, 'ru'))).toEqual([3, 1, 2]);
    expect(ids(sortProjects(projects, { column: 'progress', sortBy: OrderBy.Desc }, stats, 'ru'))).toEqual([1, 3, 2]);
  });
});
