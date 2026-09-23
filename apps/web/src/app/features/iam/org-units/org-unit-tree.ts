import { OrgUnit } from './org-units.models';
import { safeNumericRecordId } from '../../../core/services/search-target';
import { TreeTableColumns } from '../../../shared/ui-kit/components/tree-table/tree-table.component';
import { flattenTree, TreeRow } from '../../../shared/ui-kit/components/tree-table/tree.utils';
export interface OrgUnitNode { unit: OrgUnit; children: OrgUnitNode[]; }
export const orgUnitKindKeys: Readonly<Record<string, string>> = Object.assign(Object.create(null), {
  company: 'iam.org_units.kind_company', region: 'iam.org_units.kind_region',
  branch: 'iam.org_units.kind_branch', department: 'iam.org_units.kind_department'
});
const order = (a: OrgUnit, b: OrgUnit) => a.orderNo - b.orderNo || a.id - b.id;

/** Orphans and malformed cycles stay readable; a visited set cuts cycles. */
export function orderedTree(units: readonly OrgUnit[]): OrgUnitNode[] {
  const sorted = [...units].sort(order);
  const ids = new Set(units.map(unit => unit.id));
  const visited = new Set<OrgUnit>();
  const children = new Map<number, OrgUnit[]>();
  for (const unit of sorted) {
    if (unit.parentId !== null) children.set(unit.parentId, [...(children.get(unit.parentId) ?? []), unit]);
  }
  const visit = (unit: OrgUnit): OrgUnitNode[] => {
    if (visited.has(unit)) return [];
    visited.add(unit);
    return [{ unit, children: (children.get(unit.id) ?? []).flatMap(visit) }];
  };
  const roots = sorted.filter(unit => unit.parentId === null || !ids.has(unit.parentId)).flatMap(visit);
  return [...roots, ...sorted.flatMap(visit)];
}
export function descendants(units: readonly OrgUnit[], id: number): Set<number> {
  const result = new Set<number>();
  const queue = [id];
  for (let index = 0; index < queue.length; index++) {
    for (const unit of units) {
      if (unit.parentId === queue[index] && unit.id !== id && !result.has(unit.id)) {
        result.add(unit.id); queue.push(unit.id);
      }
    }
  }
  return result;
}
export function parentCandidates(units: readonly OrgUnit[], original: OrgUnit): OrgUnit[] {
  if (original.parentId === null) return [];
  const excluded = descendants(units, original.id);
  excluded.add(original.id);
  const byId = new Map(units.map(unit => [unit.id, unit]));
  const validAncestry = (unit: OrgUnit): boolean => {
    const visited = new Set<number>();
    let current: OrgUnit | undefined = unit;
    while (current) {
      if (!safeNumericRecordId(current.id) || visited.has(current.id)) return false;
      visited.add(current.id);
      if (current.parentId === null) return true;
      current = byId.get(current.parentId);
    }
    return false;
  };
  return units.filter(unit => !excluded.has(unit.id) && validAncestry(unit)).sort(order);
}

/** Rows for the tree table, rebuilt only when the unit list is replaced. */
export class OrgUnitTreeRows {
  private source: readonly OrgUnit[] | null = null;
  private rows: TreeRow<OrgUnit>[] = [];
  of(units: readonly OrgUnit[]): TreeRow<OrgUnit>[] {
    if (this.source !== units) {
      this.source = units;
      this.rows = flattenTree(orderedTree(units), { id: node => node.unit.id, children: node => node.children, data: node => node.unit });
    }
    return this.rows;
  }
}

type Translate = (key: string) => string;
export const orgUnitKindLabel = (kind: string, translate: Translate) => orgUnitKindKeys[kind] ? translate(orgUnitKindKeys[kind]) : kind;
export const orgUnitStateLabel = (unit: OrgUnit, translate: Translate) =>
  translate(unit.state === 'A' ? 'iam.org_units.active' : 'iam.org_units.passive');
/** What search matches: code, name and the translated kind. */
export const orgUnitSearchText = (translate: Translate) => (row: TreeRow<OrgUnit>) =>
  `${row.data.code} ${row.data.name} ${orgUnitKindLabel(row.data.kind, translate)}`;

/** The same three columns wherever divisions are shown as a tree. */
export function orgUnitTreeColumns(translate: Translate): TreeTableColumns<OrgUnit> {
  const header = (key: string) => ({ type: 'primitive' as const, value: translate(key) });
  return {
    treeColumn: 'name',
    columnsOrder: ['name', 'kind', 'state'],
    columns: {
      // Rows are separate grids, so tracks are fixed or a share of the width, never content-sized.
      name: { header: header('iam.org_units.name'), width: 'max(200px, calc(100% - 300px))', content: { type: 'primitive', value: row => `${row.data.code} · ${row.data.name}` } },
      kind: { header: header('iam.org_units.kind'), width: '150px', content: { type: 'primitive', value: row => orgUnitKindLabel(row.data.kind, translate) } },
      state: { header: header('iam.org_units.state'), width: '150px', content: { type: 'primitive', value: row => orgUnitStateLabel(row.data, translate) } },
    },
  };
}
