/* Derived from @greenwhite/ui-kit (MIT) at commit 6472beb, path
 * services/local-table-tree.utils.ts. Per ADR-0015 this copy is ours to
 * change; the commit above is only the base for comparing later work in the kit.
 *
 * `flattenNestedTreeData` is kept as the kit has it. The rest is rewritten:
 * the kit finds a row's parent by scanning back for the previous row one
 * level up, once per row and again per ancestor, which is quadratic and,
 * once search has removed rows, attaches a child to whichever unrelated row
 * now precedes it. Here every row carries its parent from the start, and
 * each pass over the rows is linear. See NOTICE. */

/** One node of a tree, flattened depth-first. `level` is 0 for a root. */
export interface TreeRow<T> {
  readonly id: string;
  readonly parentId: string | null;
  readonly level: number;
  readonly hasChildren: boolean;
  readonly data: T;
}

export interface LocalTableTreeRowMeta {
  level: number;
  hasChildren: boolean;
}

export interface FlattenNestedTreeOptions<TNode, TRow> {
  getChildren: (node: TNode) => TNode[] | null | undefined;
  createRow: (node: TNode, meta: LocalTableTreeRowMeta) => TRow;
}

/** Depth-first flatten for nested `{ children: TNode[] }` sources, as in the kit. */
export function flattenNestedTreeData<TNode, TRow>(
  roots: TNode[],
  options: FlattenNestedTreeOptions<TNode, TRow>
): TRow[] {
  const rows: TRow[] = [];

  const walk = (nodes: TNode[], level: number): void => {
    for (const node of nodes) {
      const children = options.getChildren(node) ?? [];
      const hasChildren = children.length > 0;
      rows.push(options.createRow(node, { level, hasChildren }));
      if (hasChildren) {
        walk(children, level + 1);
      }
    }
  };

  walk(roots, 0);
  return rows;
}

export interface FlattenTreeOptions<TNode, T> {
  id: (node: TNode) => string | number;
  children: (node: TNode) => readonly TNode[] | null | undefined;
  data: (node: TNode) => T;
}

/**
 * Depth-first rows with their parent recorded. A node reached twice (a cycle,
 * or a node listed under two parents) is kept once, where it was first met,
 * so malformed input stays renderable instead of recursing forever.
 */
export function flattenTree<TNode, T>(roots: readonly TNode[], options: FlattenTreeOptions<TNode, T>): TreeRow<T>[] {
  const rows: TreeRow<T>[] = [];
  const seen = new Set<string>();
  const walk = (nodes: readonly TNode[], parentId: string | null, level: number): void => {
    for (const node of nodes) {
      const id = String(options.id(node));
      if (seen.has(id)) continue;
      seen.add(id);
      const index = rows.length;
      rows.push({ id, parentId, level, hasChildren: false, data: options.data(node) });
      walk(options.children(node) ?? [], id, level + 1);
      // Decided after the walk: a child already met elsewhere adds no row here.
      if (rows.length > index + 1) rows[index] = { ...rows[index], hasChildren: true };
    }
  };
  walk(roots, null, 0);
  return rows;
}

/** Rows whose every ancestor is expanded. One pass: a collapsed row hides the deeper rows after it. */
export function visibleTreeRows<T>(rows: readonly TreeRow<T>[], expanded: ReadonlySet<string>): TreeRow<T>[] {
  const visible: TreeRow<T>[] = [];
  let hiddenDeeperThan = Number.POSITIVE_INFINITY;
  for (const row of rows) {
    if (row.level > hiddenDeeperThan) continue;
    hiddenDeeperThan = Number.POSITIVE_INFINITY;
    visible.push(row);
    if (row.hasChildren && !expanded.has(row.id)) hiddenDeeperThan = row.level;
  }
  return visible;
}

export interface TreeSearchResult<T> {
  /** Matches and every ancestor of a match, in tree order. */
  readonly rows: TreeRow<T>[];
  /** Ids of the rows that matched themselves; the rest are shown for context. */
  readonly matched: ReadonlySet<string>;
}

/**
 * Keeps each match together with its ancestors, so a result is always read
 * in its place in the hierarchy, never as an orphan.
 */
export function searchTreeRows<T>(rows: readonly TreeRow<T>[], isMatch: (row: TreeRow<T>) => boolean): TreeSearchResult<T> {
  const matched = new Set<string>();
  const keep = new Set<string>();
  const parentOf = new Map<string, string | null>();
  for (const row of rows) {
    parentOf.set(row.id, row.parentId);
    if (!isMatch(row)) continue;
    matched.add(row.id);
    // Ancestors are already in `parentOf`: depth-first order puts them first.
    for (let id: string | null = row.id; id !== null && !keep.has(id); id = parentOf.get(id) ?? null) keep.add(id);
  }
  return { rows: rows.filter(row => keep.has(row.id)), matched };
}

/** Ids of every row that has children, for "expand all". */
export function expandableIds<T>(rows: readonly TreeRow<T>[]): string[] {
  return rows.filter(row => row.hasChildren).map(row => row.id);
}

export interface TreePosition {
  readonly setSize: number;
  readonly posInSet: number;
}

/**
 * `aria-setsize` and `aria-posinset` for the rows actually shown, counted
 * among siblings that are shown, so a filtered tree does not announce
 * "3 of 12" when one sibling is on screen.
 */
export function treePositions<T>(rows: readonly TreeRow<T>[]): Map<string, TreePosition> {
  const counts = new Map<string | null, number>();
  const positions = new Map<string, TreePosition>();
  const order: [string, string | null, number][] = [];
  for (const row of rows) {
    const next = (counts.get(row.parentId) ?? 0) + 1;
    counts.set(row.parentId, next);
    order.push([row.id, row.parentId, next]);
  }
  for (const [id, parentId, posInSet] of order) positions.set(id, { setSize: counts.get(parentId)!, posInSet });
  return positions;
}
