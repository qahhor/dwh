import { Project, ProjectTaskStats } from '../../../core/models/task.models';
import { OrderBy } from '../../../shared/ui-kit/components/table/table.types';

export type ProjectSortColumn = 'id' | 'name' | 'state' | 'progress' | 'created';

export interface ProjectSort {
  column: ProjectSortColumn;
  sortBy: OrderBy;
}

/**
 * The project list in the order the user chose. Every project is loaded, so
 * this orders the whole filtered list, not one page. Without a choice the
 * server's order stays. A project whose statistics are unknown goes after the
 * known ones whichever way progress is sorted: "unknown" is not a low value.
 * Ties fall back to the id, so the order never shifts between renders.
 */
export function sortProjects(
  projects: readonly Project[],
  sort: ProjectSort | undefined,
  stats: Readonly<Record<number, ProjectTaskStats>>,
  locale: string,
): Project[] {
  if (!sort) return [...projects];
  const direction = sort.sortBy === OrderBy.Desc ? -1 : 1;
  const collator = new Intl.Collator(locale, { numeric: true, sensitivity: 'base' });
  const percent = (project: Project): number | null => {
    const entry = stats[project.id];
    if (!entry) return null;
    return entry.totalTasks === 0 ? 0 : entry.doneTasks / entry.totalTasks;
  };
  const compare = (a: Project, b: Project): number => {
    switch (sort.column) {
      case 'id': return a.id - b.id;
      case 'name': return collator.compare(a.name, b.name);
      // Active first when ascending: the state a list is usually scanned for.
      case 'state': return (a.state === 'A' ? 0 : 1) - (b.state === 'A' ? 0 : 1);
      case 'created': return Date.parse(a.createdAt) - Date.parse(b.createdAt);
      case 'progress': return (percent(a) ?? 0) - (percent(b) ?? 0);
    }
  };
  return [...projects].sort((a, b) => {
    if (sort.column === 'progress') {
      const [known, other] = [percent(a) !== null, percent(b) !== null];
      if (known !== other) return known ? -1 : 1;
    }
    return direction * compare(a, b) || a.id - b.id;
  });
}
