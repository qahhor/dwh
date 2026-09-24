/* Our code, after the idea of the kit's `smt-select` (smartup-ui-kit@6472beb,
 * components/forms/select) and our own `ui-searchable-select`, which it
 * replaces. See ADR-0015 rule 2.
 *
 * Both earlier versions put every option in the tab order as a button and
 * had no combobox semantics; ours also rendered its list inline, where a
 * modal's overflow cut it off. This one follows the WAI-ARIA APG combobox
 * with a listbox popup:
 * - the trigger is a select-only combobox (role="combobox") showing the
 *   chosen label, so an outside <label> names it and its text is its value;
 * - the popup (a CDK overlay, never clipped) holds a search box that keeps
 *   focus and points at the highlighted option with aria-activedescendant;
 * - arrows move the highlight, Enter picks, Escape closes, Tab leaves;
 *   typing on the closed trigger opens it and starts the search.
 * Remote search, "load more" and retry keep the contract of the old
 * component, so the keyset lookups behind it did not change.
 *
 * Signal Forms bind it directly: <smt-select [formField]="task.responsible" />
 * Plain bindings: <smt-select [value]="id" (valueChange)="id = $event" /> */
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

export interface SMTSelectOption<T = unknown> {
  readonly id: T;
  readonly label: string;
  readonly subLabel?: string;
  /** Material Symbols ligature shown before the label. */
  readonly icon?: string;
  readonly color?: string;
  readonly disabled?: boolean;
}

const POPUP_POSITIONS: ConnectedPosition[] = [
  { originX: 'start', originY: 'bottom', overlayX: 'start', overlayY: 'top', offsetY: 4 },
  { originX: 'start', originY: 'top', overlayX: 'start', overlayY: 'bottom', offsetY: -4 },
];

/** Index of the "none" row, which stands before the options when clearing is allowed. */
const NONE = -1;

let nextSelectId = 0;

function sameId(a: unknown, b: unknown): boolean {
  if (a === null || a === undefined || b === null || b === undefined) return false;
  return String(a) === String(b);
}

@Component({
  selector: 'smt-select',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  imports: [CdkConnectedOverlay, CdkOverlayOrigin],
  templateUrl: './select.component.html',
  styleUrl: './select.scss',
  host: {
    class: 'smt-select',
    '[class.smt-select--disabled]': 'isDisabled()',
    '(focusout)': 'onFocusOut($event)',
  },
})
export class SMTSelectComponent<T = unknown> implements FormValueControl<T | null> {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  private readonly injector = inject(Injector);

  readonly i18n = inject(SMTI18nService);

  readonly value = model<T | null>(null);

  readonly options = input<readonly SMTSelectOption<T>[]>([]);

  readonly disabled = input(false, { transform: booleanAttribute });

  readonly readonly = input(false, { transform: booleanAttribute });

  readonly required = input(false, { transform: booleanAttribute });

  readonly placeholder = input('');

  readonly searchPlaceholder = input('');

  /** Label of the row that clears the choice. */
  readonly emptyLabel = input('');

  readonly allowClear = input(true, { transform: booleanAttribute });

  /** Accessible name when no outside label names the field. */
  readonly ariaLabel = input('');

  /** Id for the trigger, so an outside label can point at it. */
  readonly triggerId = input<string | undefined>(undefined, { alias: 'smtTriggerId' });

  /** The parent filters: typing emits searchChange and `options` is shown as given. */
  readonly remoteSearch = input(false, { transform: booleanAttribute });

  readonly loading = input(false, { transform: booleanAttribute });

  readonly loadError = input(false, { transform: booleanAttribute });

  readonly hasMore = input(false, { transform: booleanAttribute });

  readonly searchChange = output<string>();

  readonly loadMore = output<void>();

  readonly retry = output<void>();

  readonly touch = output<void>();

  readonly id = nextSelectId++;

  readonly listboxId = `smt-select-listbox-${this.id}`;

  readonly positions = POPUP_POSITIONS;

  readonly open = signal(false);

  readonly query = signal('');

  /** Highlighted row: an index into `visibleOptions`, or NONE for the clearing row. */
  readonly activeIndex = signal<number | null>(null);

  /** Set by the legacy value accessor; `disabled` stays an input for Signal Forms. */
  private readonly disabledByForms = signal(false);

  /** The chosen option, remembered so its label survives a remote search that no longer lists it. */
  private readonly rememberedOption = signal<SMTSelectOption<T> | null>(null);

  private readonly trigger = viewChild<ElementRef<HTMLElement>>('trigger');

  private readonly searchBox = viewChild<ElementRef<HTMLInputElement>>('searchBox');

  readonly isDisabled = computed(() => this.disabled() || this.disabledByForms());

  readonly fieldId = computed(() => this.triggerId() ?? `smt-select-trigger-${this.id}`);

  readonly selectedOption = computed<SMTSelectOption<T> | null>(() => {
    const value = this.value();
    if (value === null || value === undefined) return null;
    const listed = this.options().find(option => sameId(option.id, value));
    if (listed) return listed;
    const remembered = this.rememberedOption();
    return remembered && sameId(remembered.id, value) ? remembered : null;
  });

  readonly visibleOptions = computed<readonly SMTSelectOption<T>[]>(() => {
    const options = this.options();
    const query = this.query().trim().toLowerCase();
    if (this.remoteSearch() || !query) return options;
    return options.filter(
      option => option.label.toLowerCase().includes(query) || !!option.subLabel?.toLowerCase().includes(query)
    );
  });

  readonly showNone = computed(() => this.allowClear() && !this.query().trim());

  readonly activeId = computed(() => {
    const index = this.activeIndex();
    if (index === null) return null;
    return index === NONE ? `${this.listboxId}-none` : `${this.listboxId}-opt-${index}`;
  });

  readonly showEmpty = computed(
    () => this.visibleOptions().length === 0 && !this.loading() && !this.loadError()
  );

  readonly none = NONE;

  setDisabledFromForms(disabled: boolean): void {
    this.disabledByForms.set(disabled);
  }

  optionId(index: number): string {
    return `${this.listboxId}-opt-${index}`;
  }

  isSelected(option: SMTSelectOption<T>): boolean {
    return sameId(option.id, this.value());
  }

  toggle(): void {
    if (this.open()) this.close(true);
    else this.openPopup();
  }

  openPopup(initialQuery = ''): void {
    if (this.isDisabled() || this.readonly() || this.open()) return;
    this.query.set(initialQuery);
    if (this.remoteSearch()) this.searchChange.emit(initialQuery);
    const selectedIndex = this.visibleOptions().findIndex(option => this.isSelected(option));
    this.activeIndex.set(selectedIndex >= 0 ? selectedIndex : this.firstIndex());
    this.open.set(true);
  }

  /** Called once the popup is in the page. */
  onAttached(): void {
    this.claimModalOwnership();
    // After the render, not during it: moving focus fires focus events,
    // and handling them inside change detection would re-enter it.
    afterNextRender(
      {
        write: () => {
          const box = this.searchBox()?.nativeElement;
          if (!box) return;
          box.focus();
          box.setSelectionRange(box.value.length, box.value.length);
        },
      },
      { injector: this.injector }
    );
  }

  close(restoreFocus: boolean): void {
    if (!this.open()) return;
    this.open.set(false);
    this.activeIndex.set(null);
    this.releaseModalOwnership();
    if (restoreFocus) this.trigger()?.nativeElement.focus();
  }

  /**
   * Inside an aria-modal dialog, assistive technology treats everything
   * outside the dialog as inert, and the popup lives in the overlay outside
   * it. Listing the popup in the dialog's aria-owns keeps it reachable, as
   * Angular Material does for its panels.
   */
  private claimModalOwnership(): void {
    const modal = this.host.nativeElement.closest('[aria-modal="true"]');
    if (!modal) return;
    const popupId = `${this.listboxId}-popup`;
    const owned = (modal.getAttribute('aria-owns') ?? '').split(/\s+/).filter(Boolean);
    if (!owned.includes(popupId)) modal.setAttribute('aria-owns', [...owned, popupId].join(' '));
    this.ownedBy = modal;
  }

  private releaseModalOwnership(): void {
    const modal = this.ownedBy;
    this.ownedBy = null;
    if (!modal) return;
    const popupId = `${this.listboxId}-popup`;
    const owned = (modal.getAttribute('aria-owns') ?? '').split(/\s+/).filter(token => token && token !== popupId);
    if (owned.length) modal.setAttribute('aria-owns', owned.join(' '));
    else modal.removeAttribute('aria-owns');
  }

  private ownedBy: Element | null = null;

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
    const text = (event.target as HTMLInputElement).value;
    this.query.set(text);
    if (this.remoteSearch()) this.searchChange.emit(text);
    this.activeIndex.set(this.firstIndex());
  }

  clearSearch(): void {
    this.query.set('');
    if (this.remoteSearch()) this.searchChange.emit('');
    this.activeIndex.set(this.firstIndex());
    this.searchBox()?.nativeElement.focus();
  }

  onSearchKeydown(event: KeyboardEvent): void {
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        this.moveActive(1);
        break;
      case 'ArrowUp':
        event.preventDefault();
        this.moveActive(-1);
        break;
      case 'PageDown':
        event.preventDefault();
        this.moveActive(10);
        break;
      case 'PageUp':
        event.preventDefault();
        this.moveActive(-10);
        break;
      case 'Enter': {
        event.preventDefault();
        const index = this.activeIndex();
        if (index === NONE) this.pickNone();
        else if (index !== null) {
          const option = this.visibleOptions()[index];
          if (option) this.pick(option);
        }
        break;
      }
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

  pick(option: SMTSelectOption<T>): void {
    if (option.disabled) return;
    this.rememberedOption.set(option);
    this.value.set(option.id);
    this.close(true);
  }

  pickNone(): void {
    this.value.set(null);
    this.close(true);
  }

  clear(event: MouseEvent): void {
    event.stopPropagation();
    this.value.set(null);
    this.trigger()?.nativeElement.focus();
  }

  highlight(index: number): void {
    this.activeIndex.set(index);
  }

  onFocusOut(event: FocusEvent): void {
    const next = event.relatedTarget as Node | null;
    if (next && this.host.nativeElement.contains(next)) return;
    if (next instanceof Element && next.closest(`#${this.listboxId}-popup`)) return;
    if (this.open()) this.close(false);
    this.touch.emit();
  }

  /** Focus left the popup (it lives in the overlay, outside the host). */
  onPopupFocusOut(event: FocusEvent): void {
    // Removing the popup on close blurs its search box; that is not the user leaving.
    if (!this.open()) return;
    const next = event.relatedTarget as Node | null;
    if (next && (this.host.nativeElement.contains(next) || (event.currentTarget as Element).contains(next))) return;
    this.close(false);
    this.touch.emit();
  }

  private firstIndex(): number | null {
    if (this.showNone()) return NONE;
    return this.visibleOptions().length ? 0 : null;
  }

  private moveActive(delta: number): void {
    const last = this.visibleOptions().length - 1;
    const first = this.showNone() ? NONE : 0;
    if (last < 0 && first === 0) return;
    const current = this.activeIndex() ?? first - Math.sign(delta);
    const next = Math.min(Math.max(current + delta, first), Math.max(last, first));
    this.activeIndex.set(next);
    this.scrollActiveIntoView();
  }

  private scrollActiveIntoView(): void {
    queueMicrotask(() => {
      const id = this.activeId();
      const element = id ? document.getElementById(id) : null;
      if (element && typeof element.scrollIntoView === 'function') element.scrollIntoView({ block: 'nearest' });
    });
  }
}
