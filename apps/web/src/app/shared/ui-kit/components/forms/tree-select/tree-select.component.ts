/* Our code, after the idea of the kit's `smt-tree-select`
 * (smartup-ui-kit@6472beb, components/forms/tree-select). See ADR-0015
 * rule 2: the kit's copy was written for Signal Forms only, with its own
 * data source contract; this one shares smt-select's overlay, search and
 * keyboard model so the pickers behave alike.
 *
 * A combobox whose popup is a tree (WAI-ARIA APG combobox with a tree
 * popup): the search box keeps focus and points at the highlighted node
 * with aria-activedescendant; Up/Down move over the visible nodes, Right
 * opens a node or steps into it, Left closes it or steps to its parent,
 * Home/End jump, Enter picks, Escape closes, Tab leaves. Nodes carry
 * aria-level, aria-setsize, aria-posinset and aria-expanded. A search keeps
 * every match inside its ancestors, opened, as smt-tree-table does.
 *
 * Signal Forms: <smt-tree-select [formField]="unit.parentId" [nodes]="tree" />
 * Plain bindings: [value]="id" (valueChange)="id = $event" */
import {
  afterNextRender,
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  Injector,
  input,
  model,
  output,
  signal,
  viewChild,
  ViewEncapsulation,
} from '@angular/core';
import { CdkConnectedOverlay, CdkOverlayOrigin, ConnectedPosition } from '@angular/cdk/overlay';
import type { FormValueControl } from '@angular/forms/signals';
import { SMTI18nService } from '../../../i18n';

export interface SMTTreeOption<T = unknown> {
  readonly id: T;
  readonly label: string;
  readonly subLabel?: string;
  readonly disabled?: boolean;
  readonly children?: readonly SMTTreeOption<T>[];
}

interface TreeRow<T> {
  readonly option: SMTTreeOption<T>;
  readonly key: string;
  readonly parentKey: string | null;
  readonly level: number;
  readonly setSize: number;
  readonly posInSet: number;
  readonly hasChildren: boolean;
  readonly expanded: boolean;
}

const POPUP_POSITIONS: ConnectedPosition[] = [
  { originX: 'start', originY: 'bottom', overlayX: 'start', overlayY: 'top', offsetY: 4 },
  { originX: 'start', originY: 'top', overlayX: 'start', overlayY: 'bottom', offsetY: -4 },
];

let nextTreeId = 0;

const keyOf = (id: unknown) => String(id);

function matches<T>(option: SMTTreeOption<T>, query: string): boolean {
  return option.label.toLowerCase().includes(query) || !!option.subLabel?.toLowerCase().includes(query);
}

@Component({
  selector: 'smt-tree-select',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  imports: [CdkConnectedOverlay, CdkOverlayOrigin],
  templateUrl: './tree-select.component.html',
  styleUrls: ['../select/select.scss', './tree-select.scss'],
  host: {
    class: 'smt-select smt-tree-select',
    '[class.smt-select--disabled]': 'isDisabled()',
    '(focusout)': 'onFocusOut($event)',
  },
})
export class SMTTreeSelectComponent<T = unknown> implements FormValueControl<T | null> {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  private readonly injector = inject(Injector);

  readonly i18n = inject(SMTI18nService);

  readonly value = model<T | null>(null);

  readonly nodes = input<readonly SMTTreeOption<T>[]>([]);

  readonly disabled = input(false, { transform: booleanAttribute });

  readonly readonly = input(false, { transform: booleanAttribute });

  readonly required = input(false, { transform: booleanAttribute });

  readonly placeholder = input('');

  readonly searchPlaceholder = input('');

  /** Shows a button that clears the choice. */
  readonly allowClear = input(false, { transform: booleanAttribute });

  readonly ariaLabel = input('');

  /** Id for the trigger, so an outside label can point at it. */
  readonly triggerId = input<string | undefined>(undefined, { alias: 'smtTriggerId' });

  /** Marks the field invalid for assistive technology (a screen that shows its own error). */
  readonly invalid = input(false, { transform: booleanAttribute });

  /** Ids of elements that describe the field, such as its error message. */
  readonly describedBy = input<string | null | undefined>(undefined, { alias: 'smtDescribedBy' });

  readonly touch = output<void>();

  readonly id = nextTreeId++;

  readonly treeId = `smt-tree-select-tree-${this.id}`;

  readonly positions = POPUP_POSITIONS;

  readonly open = signal(false);

  readonly query = signal('');

  /** Key of the highlighted node. */
  readonly activeKey = signal<string | null>(null);

  /** Keys of the nodes the user opened (ignored while searching, when every match's path is open). */
  readonly expanded = signal<ReadonlySet<string>>(new Set());

  private readonly disabledByForms = signal(false);

  private readonly trigger = viewChild<ElementRef<HTMLElement>>('trigger');

  private readonly searchBox = viewChild<ElementRef<HTMLInputElement>>('searchBox');

  private ownedBy: Element | null = null;

  readonly isDisabled = computed(() => this.disabled() || this.disabledByForms());

  readonly fieldId = computed(() => this.triggerId() ?? `smt-tree-select-trigger-${this.id}`);

  /** Every node by key, with its parent's key, for lookups and paths. */
  private readonly index = computed(() => {
    const byKey = new Map<string, { option: SMTTreeOption<T>; parentKey: string | null }>();
    const walk = (options: readonly SMTTreeOption<T>[], parentKey: string | null) => {
      for (const option of options) {
        const key = keyOf(option.id);
        if (byKey.has(key)) continue;
        byKey.set(key, { option, parentKey });
        walk(option.children ?? [], key);
      }
    };
    walk(this.nodes(), null);
    return byKey;
  });

  readonly selectedOption = computed(() => {
    const value = this.value();
    return value === null || value === undefined ? null : (this.index().get(keyOf(value))?.option ?? null);
  });

  readonly rows = computed<readonly TreeRow<T>[]>(() => {
    const query = this.query().trim().toLowerCase();
    const expanded = this.expanded();
    const rows: TreeRow<T>[] = [];
    // While searching, a node shows when it or a descendant matches.
    const visibleInSearch = (option: SMTTreeOption<T>): boolean =>
      matches(option, query) || (option.children ?? []).some(visibleInSearch);
    const walk = (options: readonly SMTTreeOption<T>[], level: number, parentKey: string | null) => {
      const shown = query ? options.filter(visibleInSearch) : options;
      shown.forEach((option, index) => {
        const key = keyOf(option.id);
        const children = option.children ?? [];
        const hasChildren = (query ? children.filter(visibleInSearch) : children).length > 0;
        const isOpen = hasChildren && (query ? true : expanded.has(key));
        rows.push({
          option,
          key,
          parentKey,
          level,
          setSize: shown.length,
          posInSet: index + 1,
          hasChildren,
          expanded: isOpen,
        });
        if (isOpen) walk(children, level + 1, key);
      });
    };
    walk(this.nodes(), 1, null);
    return rows;
  });

  readonly activeId = computed(() => {
    const key = this.activeKey();
    return key === null ? null : this.rowId(key);
  });

  setDisabledFromForms(disabled: boolean): void {
    this.disabledByForms.set(disabled);
  }

  rowId(key: string): string {
    return `${this.treeId}-node-${key.replace(/[^A-Za-z0-9_-]/g, '_')}`;
  }

  isSelected(row: TreeRow<T>): boolean {
    const value = this.value();
    return value !== null && value !== undefined && keyOf(value) === row.key;
  }

  toggle(): void {
    if (this.open()) this.close(true);
    else this.openPopup();
  }

  openPopup(initialQuery = ''): void {
    if (this.isDisabled() || this.readonly() || this.open()) return;
    this.query.set(initialQuery);
    // Open the path to the chosen node, so it is in view.
    const next = new Set(this.expanded());
    let parentKey = this.value() === null || this.value() === undefined
      ? null
      : (this.index().get(keyOf(this.value()))?.parentKey ?? null);
    while (parentKey !== null) {
      next.add(parentKey);
      parentKey = this.index().get(parentKey)?.parentKey ?? null;
    }
    this.expanded.set(next);
    const selected = this.selectedOption();
    this.activeKey.set(selected ? keyOf(selected.id) : (this.rows()[0]?.key ?? null));
    this.open.set(true);
  }

  onAttached(): void {
    this.claimModalOwnership();
    // After the render: moving focus inside change detection would re-enter it.
    afterNextRender(
      {
        write: () => {
          const box = this.searchBox()?.nativeElement;
          if (!box) return;
          box.focus();
          box.setSelectionRange(box.value.length, box.value.length);
          this.scrollActiveIntoView();
        },
      },
      { injector: this.injector }
    );
  }

  close(restoreFocus: boolean): void {
    if (!this.open()) return;
    this.open.set(false);
    this.activeKey.set(null);
    this.releaseModalOwnership();
    if (restoreFocus) this.trigger()?.nativeElement.focus();
  }

  pick(row: TreeRow<T>): void {
    if (row.option.disabled) return;
    this.value.set(row.option.id);
    this.close(true);
  }

  clear(event: MouseEvent): void {
    event.stopPropagation();
    this.value.set(null);
    this.trigger()?.nativeElement.focus();
  }

  toggleNode(row: TreeRow<T>, event?: Event): void {
    event?.stopPropagation();
    if (!row.hasChildren || this.query().trim()) return;
    const next = new Set(this.expanded());
    if (next.has(row.key)) next.delete(row.key);
    else next.add(row.key);
    this.expanded.set(next);
    this.activeKey.set(row.key);
  }

  highlight(row: TreeRow<T>): void {
    this.activeKey.set(row.key);
  }

  onTriggerKeydown(event: KeyboardEvent): void {
    if (this.open()) return;
    if (['ArrowDown', 'ArrowUp', 'Enter', ' '].includes(event.key)) {
      event.preventDefault();
      this.openPopup();
      return;
    }
    if (event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
      event.preventDefault();
      this.openPopup(event.key);
    }
  }

  onSearchInput(event: Event): void {
    this.query.set((event.target as HTMLInputElement).value);
    const rows = this.rows();
    const firstMatch = rows.find(row => matches(row.option, this.query().trim().toLowerCase()));
    this.activeKey.set((firstMatch ?? rows[0])?.key ?? null);
  }

  clearSearch(): void {
    this.query.set('');
    this.activeKey.set(this.selectedOption() ? keyOf(this.selectedOption()!.id) : (this.rows()[0]?.key ?? null));
    this.searchBox()?.nativeElement.focus();
  }

  onSearchKeydown(event: KeyboardEvent): void {
    const rows = this.rows();
    const index = rows.findIndex(row => row.key === this.activeKey());
    const active = index >= 0 ? rows[index] : undefined;
    const go = (target: TreeRow<T> | undefined) => {
      if (!target) return;
      this.activeKey.set(target.key);
      this.scrollActiveIntoView();
    };
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        go(rows[index < 0 ? 0 : Math.min(index + 1, rows.length - 1)]);
        break;
      case 'ArrowUp':
        event.preventDefault();
        go(rows[index < 0 ? rows.length - 1 : Math.max(index - 1, 0)]);
        break;
      case 'ArrowRight':
        if (!active?.hasChildren) break;
        event.preventDefault();
        if (active.expanded) go(rows[index + 1]);
        else this.toggleNode(active);
        break;
      case 'ArrowLeft':
        if (!active) break;
        if (active.expanded && !this.query().trim()) {
          event.preventDefault();
          this.toggleNode(active);
        } else if (active.parentKey !== null) {
          event.preventDefault();
          go(rows.find(row => row.key === active.parentKey));
        }
        break;
      case 'Home':
        event.preventDefault();
        go(rows[0]);
        break;
      case 'End':
        event.preventDefault();
        go(rows[rows.length - 1]);
        break;
      case 'Enter':
        event.preventDefault();
        if (active) this.pick(active);
        break;
      case 'Escape':
        event.preventDefault();
        event.stopPropagation();
        this.close(true);
        break;
      case 'Tab':
        this.close(false);
        break;
    }
  }

  onFocusOut(event: FocusEvent): void {
    const next = event.relatedTarget as Node | null;
    if (next && this.host.nativeElement.contains(next)) return;
    if (next instanceof Element && next.closest(`#${this.treeId}-popup`)) return;
    if (this.open()) this.close(false);
    this.touch.emit();
  }

  onPopupFocusOut(event: FocusEvent): void {
    // Removing the popup on close blurs its search box; that is not the user leaving.
    if (!this.open()) return;
    const next = event.relatedTarget as Node | null;
    if (next && (this.host.nativeElement.contains(next) || (event.currentTarget as Element).contains(next))) return;
    this.close(false);
    this.touch.emit();
  }

  private scrollActiveIntoView(): void {
    queueMicrotask(() => {
      const id = this.activeId();
      const element = id ? document.getElementById(id) : null;
      if (element && typeof element.scrollIntoView === 'function') element.scrollIntoView({ block: 'nearest' });
    });
  }

  /** See smt-select: keeps the overlay popup reachable inside an aria-modal dialog. */
  private claimModalOwnership(): void {
    const modal = this.host.nativeElement.closest('[aria-modal="true"]');
    if (!modal) return;
    const popupId = `${this.treeId}-popup`;
    const owned = (modal.getAttribute('aria-owns') ?? '').split(/\s+/).filter(Boolean);
    if (!owned.includes(popupId)) modal.setAttribute('aria-owns', [...owned, popupId].join(' '));
    this.ownedBy = modal;
  }

  private releaseModalOwnership(): void {
    const modal = this.ownedBy;
    this.ownedBy = null;
    if (!modal) return;
    const popupId = `${this.treeId}-popup`;
    const owned = (modal.getAttribute('aria-owns') ?? '').split(/\s+/).filter(token => token && token !== popupId);
    if (owned.length) modal.setAttribute('aria-owns', owned.join(' '));
    else modal.removeAttribute('aria-owns');
  }
}
