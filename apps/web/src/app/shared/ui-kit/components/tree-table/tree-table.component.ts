/* Not vendored. The kit's tree mode lives inside `smt-local-table`, which the
 * kit itself marks deprecated and which brings its filter, settings and
 * modal stack with it. This is the tree alone, on the vendored `smt-table`,
 * following the WAI-ARIA APG treegrid pattern: one Tab stop, arrow keys to
 * move and to open or close a branch, Enter or Space to choose a row.
 * Search keeps every match inside its ancestors. See ADR-0015. */
import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  Injector,
  input,
  output,
  signal,
  TemplateRef,
  untracked,
  viewChild,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { SMTTableComponent } from '../table/table.component';
import { ColumnInfo, TableConfig, TableRowKeydownEvent } from '../table/table.types';
import { SMTI18nService } from '../../i18n';
import { expandableIds, searchTreeRows, TreeRow, treePositions, visibleTreeRows } from './tree.utils';

export interface TreeTableColumns<T> {
  columns: Record<string, ColumnInfo<TreeRow<T>>>;
  columnsOrder: string[];
  /** The column that carries indentation and the expand control. */
  treeColumn: string;
}

@Component({
  selector: 'smt-tree-table',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTTableComponent, NgTemplateOutlet],
  host: { class: 'flex flex-col gap-2 min-w-0' },
  template: `
    @if (showExpandControls()) {
      <div class="flex flex-wrap items-center justify-end gap-2">
        <button type="button" [class]="toolbarButton" [disabled]="disabled() || searching()" (click)="expandAll()">
          {{ i18n.messages().tree.expandAll }}
        </button>
        <button type="button" [class]="toolbarButton" [disabled]="disabled() || searching()" (click)="collapseAll()">
          {{ i18n.messages().tree.collapseAll }}
        </button>
      </div>
    }
    <smt-table
      [smtData]="displayRows()"
      [smtConfig]="tableConfig()"
      [smtVirtualRows]="false"
      [smtColumnResizeEnabled]="false"
      (smtRowClick)="choose($event)"
      (smtRowKeydown)="onKeydown($event)" />

    <ng-template #treeCell let-row>
      <div class="flex min-w-0 items-center gap-1" [style.padding-inline-start.px]="row.level * indentPx()">
        @if (row.hasChildren) {
          <!-- A pointer affordance only: the row's aria-expanded states it and
               the arrow keys operate it, so it is not a second Tab stop. -->
          <button
            type="button"
            tabindex="-1"
            aria-hidden="true"
            class="inline-flex h-[24px] w-[24px] shrink-0 cursor-pointer items-center justify-center rounded border-0 bg-transparent p-0 text-gray-600 hover:bg-gray-100"
            [disabled]="searching()"
            (click)="$event.stopPropagation(); toggle(row)">
            <span class="material-symbols-outlined" style="font-size: 18px">{{ isExpanded(row) ? 'expand_more' : 'chevron_right' }}</span>
          </button>
        } @else {
          <span class="inline-block h-[24px] w-[24px] shrink-0" aria-hidden="true"></span>
        }
        <div
          class="min-w-0 flex-1 break-normal [overflow-wrap:anywhere]"
          [class.font-semibold]="row.hasChildren"
          [class.text-gray-500]="searching() && !searchResult().matched.has(row.id)">
          @if (treeContent(); as content) {
            @if (content.type === 'templateRef') {
              <ng-container *ngTemplateOutlet="content.value(); context: { $implicit: row }" />
            } @else if (content.type === 'primitive') {
              {{ content.value(row) }}
            }
          }
        </div>
      </div>
    </ng-template>
  `,
})
export class SMTTreeTableComponent<T> {
  protected readonly i18n = inject(SMTI18nService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  readonly rows = input.required<TreeRow<T>[]>({ alias: 'smtRows' });
  readonly columns = input.required<TreeTableColumns<T>>({ alias: 'smtColumns' });
  readonly ariaLabel = input.required<string>({ alias: 'smtAriaLabel' });
  /** Case-insensitive substring search over `searchText`. Empty shows the tree as expanded by the user. */
  readonly search = input('', { alias: 'smtSearch' });
  readonly searchText = input<(row: TreeRow<T>) => string>(() => '', { alias: 'smtSearchText' });
  readonly selectedId = input<string | null>(null, { alias: 'smtSelectedId' });
  readonly disabled = input(false, { alias: 'smtDisabled' });
  readonly showExpandControls = input(true, { alias: 'smtShowExpandControls' });
  readonly indentPx = input(20, { alias: 'smtIndentPx' });

  readonly select = output<TreeRow<T>>({ alias: 'smtSelect' });

  protected readonly toolbarButton =
    'inline-flex min-h-[32px] items-center rounded border border-gray-300 bg-white px-3 text-gray-700 hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-60';

  private readonly treeCell = viewChild.required<TemplateRef<unknown>>('treeCell');
  private readonly treeCellContent = computed(() => this.treeCell());

  /** Ids the user has collapsed. Tracking the collapsed set keeps new branches open by default. */
  private readonly collapsed = signal<ReadonlySet<string>>(new Set());

  /** Row holding the roving tabindex: the single Tab stop of the grid. */
  private readonly activeId = signal<string | null>(null);

  protected readonly searching = computed(() => this.search().trim().length > 0);

  protected readonly searchResult = computed(() => {
    const query = this.search().trim().toLocaleLowerCase();
    const text = this.searchText();
    return searchTreeRows(this.rows(), row => !!query && text(row).toLocaleLowerCase().includes(query));
  });

  private readonly expanded = computed<ReadonlySet<string>>(() => {
    const collapsed = this.collapsed();
    return new Set(this.rows().filter(row => row.hasChildren && !collapsed.has(row.id)).map(row => row.id));
  });

  /** While searching, every branch on the way to a match is open: the result is the path. */
  protected readonly displayRows = computed(() =>
    this.searching() ? this.searchResult().rows : visibleTreeRows(this.rows(), this.expanded())
  );

  private readonly positions = computed(() => treePositions(this.displayRows()));

  /** Rows whose children are on screen. While searching, a match need not show its children. */
  private readonly openRows = computed<ReadonlySet<string>>(() =>
    this.searching() ? new Set(this.displayRows().map(row => row.parentId).filter(id => id !== null)) : this.expanded()
  );

  /** The active row if it is still shown, otherwise the selected row, otherwise the first. */
  private readonly focusableId = computed(() => {
    const shown = new Set(this.displayRows().map(row => row.id));
    for (const id of [this.activeId(), this.selectedId()]) if (id !== null && shown.has(id)) return id;
    return this.displayRows()[0]?.id ?? null;
  });

  protected readonly treeContent = computed(() => this.columns().columns[this.columns().treeColumn]?.content ?? null);

  protected readonly tableConfig = computed<TableConfig<TreeRow<T>>>(() => {
    const { columns, columnsOrder, treeColumn } = this.columns();
    const positions = this.positions();
    const selectedId = this.selectedId();
    const focusableId = this.focusableId();
    const open = this.openRows();
    return {
      trackBy: (_index, row) => row.id,
      columnsOrder,
      columns: {
        ...columns,
        [treeColumn]: { ...columns[treeColumn], content: { type: 'templateRef', value: this.treeCellContent } },
      },
      layout: 'fit',
      ariaRole: 'treegrid',
      ariaLabel: this.ariaLabel(),
      rowAria: row => ({
        id: row.id,
        level: row.level + 1,
        expanded: row.hasChildren ? open.has(row.id) : null,
        setSize: positions.get(row.id)?.setSize ?? 1,
        posInSet: positions.get(row.id)?.posInSet ?? 1,
        selected: row.id === selectedId,
        tabindex: row.id === focusableId ? 0 : -1,
      }),
    };
  });

  protected isExpanded(row: TreeRow<T>): boolean {
    return this.openRows().has(row.id);
  }

  toggle(row: TreeRow<T>): void {
    if (!row.hasChildren || this.searching()) return;
    this.setExpanded(row, !this.expanded().has(row.id));
  }

  expandAll(): void {
    this.collapsed.set(new Set());
  }

  collapseAll(): void {
    this.collapsed.set(new Set(expandableIds(this.rows())));
    // The active row may now be hidden inside a collapsed branch; keep focus on its root.
    const active = untracked(this.focusableId);
    const root = active === null ? null : this.rootOf(active);
    if (root !== null) this.activeId.set(root);
  }

  protected choose(row: TreeRow<T>): void {
    this.activeId.set(row.id);
    if (!this.disabled()) this.select.emit(row);
  }

  protected onKeydown({ row, event }: TableRowKeydownEvent<TreeRow<T>>): void {
    const rows = this.displayRows();
    const index = rows.findIndex(item => item.id === row.id);
    let target: TreeRow<T> | undefined;

    switch (event.key) {
      case 'ArrowDown': target = rows[index + 1]; break;
      case 'ArrowUp': target = rows[index - 1]; break;
      case 'Home': target = rows[0]; break;
      case 'End': target = rows[rows.length - 1]; break;
      case 'ArrowRight':
        if (!row.hasChildren) break;
        if (!this.isExpanded(row)) this.setExpanded(row, true);
        else if (rows[index + 1]?.parentId === row.id) target = rows[index + 1];
        break;
      case 'ArrowLeft':
        if (row.hasChildren && this.isExpanded(row) && !this.searching()) this.setExpanded(row, false);
        else target = rows.find(item => item.id === row.parentId);
        break;
      case 'Enter':
      case ' ':
        this.choose(row);
        break;
      default:
        return;
    }
    event.preventDefault();
    if (target) this.focusRow(target.id);
  }

  private setExpanded(row: TreeRow<T>, open: boolean): void {
    const next = new Set(this.collapsed());
    if (open) next.delete(row.id);
    else next.add(row.id);
    this.collapsed.set(next);
  }

  private rootOf(id: string): string | null {
    const byId = new Map(this.rows().map(row => [row.id, row]));
    let row = byId.get(id);
    while (row?.parentId != null && byId.has(row.parentId)) row = byId.get(row.parentId);
    return row?.id ?? null;
  }

  private focusRow(id: string): void {
    this.activeId.set(id);
    afterNextRender(
      () =>
        [...this.host.nativeElement.querySelectorAll<HTMLElement>('[data-smt-row-id]')]
          .find(element => element.dataset['smtRowId'] === id)
          ?.focus(),
      { injector: this.injector }
    );
  }
}
