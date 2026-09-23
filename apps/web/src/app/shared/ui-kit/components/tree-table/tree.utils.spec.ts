/* The flatten case is carried from @greenwhite/ui-kit at 6472beb
 * (services/local-table-tree.utils.spec.ts); the rest covers the rewrite. */
import { describe, expect, it } from 'vitest';
import { expandableIds, flattenNestedTreeData, flattenTree, searchTreeRows, treePositions, visibleTreeRows } from './tree.utils';

interface Node { id: number; name: string; children?: Node[] }

// Company > Region North > (Branch A > Dept A1), Branch B ; Region South
const roots: Node[] = [
  { id: 1, name: 'Company', children: [
    { id: 2, name: 'Region North', children: [
      { id: 3, name: 'Branch A', children: [{ id: 4, name: 'Dept A1' }] },
      { id: 5, name: 'Branch B' },
    ] },
    { id: 6, name: 'Region South' },
  ] },
];
const rows = flattenTree(roots, { id: node => node.id, children: node => node.children, data: node => node.name });
const ids = (list: { id: string }[]) => list.map(row => row.id);

describe('flattenNestedTreeData (kit behaviour)', () => {
  it('flattens nested nodes depth-first', () => {
    const flat = flattenNestedTreeData(roots, {
      getChildren: node => node.children,
      createRow: (node, meta) => ({ id: node.id, level: meta.level, hasChildren: meta.hasChildren }),
    });
    expect(flat.map(row => row.id)).toEqual([1, 2, 3, 4, 5, 6]);
    expect(flat.map(row => row.level)).toEqual([0, 1, 2, 3, 2, 1]);
  });
});

describe('flattenTree', () => {
  it('records parent, level and children depth-first', () => {
    expect(ids(rows)).toEqual(['1', '2', '3', '4', '5', '6']);
    expect(rows.map(row => row.parentId)).toEqual([null, '1', '2', '3', '2', '1']);
    expect(rows.map(row => row.hasChildren)).toEqual([true, true, true, false, false, false]);
  });

  it('keeps a node met twice only where it was first met, and survives a cycle', () => {
    const shared: Node = { id: 9, name: 'Shared' };
    const loop: Node = { id: 7, name: 'Loop' };
    loop.children = [loop, shared];
    const flat = flattenTree([loop, { id: 8, name: 'Other', children: [shared] }], {
      id: node => node.id, children: node => node.children, data: node => node.name,
    });
    expect(ids(flat)).toEqual(['7', '9', '8']);
    // 8 lists only a child already placed under 7, so it has no children of its own here.
    expect(flat.find(row => row.id === '8')!.hasChildren).toBe(false);
  });
});

describe('visibleTreeRows', () => {
  it('hides every descendant of a collapsed row, and only those', () => {
    const allOpen = new Set(expandableIds(rows));
    expect(ids(visibleTreeRows(rows, allOpen))).toEqual(['1', '2', '3', '4', '5', '6']);
    const northClosed = new Set([...allOpen].filter(id => id !== '2'));
    expect(ids(visibleTreeRows(rows, northClosed))).toEqual(['1', '2', '6']);
    expect(ids(visibleTreeRows(rows, new Set()))).toEqual(['1']);
  });
});

describe('searchTreeRows', () => {
  it('keeps a match inside its real ancestors, not whichever row precedes it', () => {
    const result = searchTreeRows(rows, row => row.data === 'Dept A1');
    expect(ids(result.rows)).toEqual(['1', '2', '3', '4']);
    expect([...result.matched]).toEqual(['4']);
  });

  it('shares ancestors between matches and drops unrelated branches', () => {
    const result = searchTreeRows(rows, row => row.data.startsWith('Branch'));
    expect(ids(result.rows)).toEqual(['1', '2', '3', '5']);
  });

  it('returns nothing when nothing matches', () => {
    expect(searchTreeRows(rows, () => false).rows).toEqual([]);
  });
});

describe('treePositions', () => {
  it('counts only the siblings that are shown', () => {
    const positions = treePositions(searchTreeRows(rows, row => row.data === 'Branch B' || row.data === 'Region South').rows);
    expect(positions.get('2')).toEqual({ setSize: 2, posInSet: 1 });
    expect(positions.get('6')).toEqual({ setSize: 2, posInSet: 2 });
    expect(positions.get('5')).toEqual({ setSize: 1, posInSet: 1 });
  });
});
