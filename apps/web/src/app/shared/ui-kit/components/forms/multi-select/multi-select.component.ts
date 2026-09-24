/* Our code, after the idea of the kit's `smt-multi-select`
 * (smartup-ui-kit@6472beb, components/forms/multi-select) and our own
 * `ui-user-multi-select`, which it replaces. See ADR-0015 rule 2.
 *
 * Both earlier versions made each option a tab stop and gave the search
 * box no combobox semantics; ours was also bound to the User model and drew
 * its list inline, where a modal's overflow cut it off. This one works on
 * plain options (the caller maps its records) and follows the WAI-ARIA APG
 * combobox with a multi-selectable listbox:
 * - chosen values show as chips, each with a labelled remove button;
 * - the trigger is a combobox; the popup (a CDK overlay) holds a search box
 *   that keeps focus and points at the highlighted option with
 *   aria-activedescendant;
 * - arrows move, Enter or Space toggles and keeps the list open, Escape
 *   closes, Tab leaves; Backspace in an empty search removes the last chip;
 * - inside an aria-modal dialog the open popup is added to its aria-owns.
 * Remote search, "load more" and retry keep the old component's contract.
 *
 * Signal Forms: <smt-multi-select [formField]="task.observers" [options]="…" />
 * Plain bindings: [value]="ids" (valueChange)="ids = $event" */
import {
  afterNextRender,
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  Injector,
  input,
  model,
  output,
  signal,
  untracked,
  viewChild,
  ViewEncapsulation,
} from '@angular/core';
import { CdkConnectedOverlay, CdkOverlayOrigin, ConnectedPosition } from '@angular/cdk/overlay';
import type { FormValueControl } from '@angular/forms/signals';
import { SMTI18nService } from '../../../i18n';
import type { SMTSelectOption } from '../select/select.component';

const POPUP_POSITIONS: ConnectedPosition[] = [
  { originX: 'start', originY: 'bottom', overlayX: 'start', overlayY: 'top', offsetY: 4 },
  { originX: 'start', originY: 'top', overlayX: 'start', overlayY: 'bottom', offsetY: -4 },
];

let nextMultiId = 0;

function key(id: unknown): string {
  return String(id);
}

@Component({
  selector: 'smt-multi-select',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  imports: [CdkConnectedOverlay, CdkOverlayOrigin],
  templateUrl: './multi-select.component.html',
  styleUrls: ['../select/select.scss', './multi-select.scss'],
  host: {
    class: 'smt-multi-select',
    '[class.smt-multi-select--disabled]': 'isDisabled()',
    '(focusout)': 'onFocusOut($event)',
  },
})
export class SMTMultiSelectComponent<T = unknown> implements FormValueControl<readonly T[]> {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  private readonly injector = inject(Injector);

  readonly i18n = inject(SMTI18nService);

  readonly value = model<readonly T[]>([]);

  readonly options = input<readonly SMTSelectOption<T>[]>([]);

  /**
   * Options that name chips but are not offered in the list, such as an
   * inactive person who is already chosen.
   */
  readonly knownOptions = input<readonly SMTSelectOption<T>[]>([]);

  readonly disabled = input(false, { transform: booleanAttribute });

  readonly readonly = input(false, { transform: booleanAttribute });

  readonly required = input(false, { transform: booleanAttribute });

  readonly placeholder = input('');

  readonly searchPlaceholder = input('');

  /** Accessible name of the field (the trigger and the list). */
  readonly ariaLabel = input('');

  readonly remoteSearch = input(false, { transform: booleanAttribute });

  readonly loading = input(false, { transform: booleanAttribute });

  readonly loadError = input(false, { transform: booleanAttribute });

  readonly hasMore = input(false, { transform: booleanAttribute });

  readonly searchChange = output<string>();

  readonly loadMore = output<void>();

  readonly retry = output<void>();

  readonly touch = output<void>();

  readonly id = nextMultiId++;

  readonly listboxId = `smt-multi-select-listbox-${this.id}`;

  readonly positions = POPUP_POSITIONS;

  readonly open = signal(false);

  readonly query = signal('');

  readonly activeIndex = signal<number | null>(null);

  private readonly disabledByForms = signal(false);

  /** Every option seen, so a chip keeps its label when a remote search no longer lists it. */
  private readonly known = signal<ReadonlyMap<string, SMTSelectOption<T>>>(new Map());

  private readonly trigger = viewChild<ElementRef<HTMLElement>>('trigger');

  private readonly searchBox = viewChild<ElementRef<HTMLInputElement>>('searchBox');

  private ownedBy: Element | null = null;

  readonly isDisabled = computed(() => this.disabled() || this.disabledByForms());

  private readonly selectedKeys = computed(() => new Set((this.value() ?? []).map(key)));

  readonly chips = computed(() =>
    (this.value() ?? []).map(id => this.known().get(key(id)) ?? { id, label: `#${String(id)}` })
  );

  readonly visibleOptions = computed<readonly SMTSelectOption<T>[]>(() => {
    const options = this.options();
    const query = this.query().trim().toLowerCase();
    if (this.remoteSearch() || !query) return options;
    return options.filter(
      option => option.label.toLowerCase().includes(query) || !!option.subLabel?.toLowerCase().includes(query)
    );
  });

  readonly activeId = computed(() => {
    const index = this.activeIndex();
    return index === null ? null : this.optionId(index);
  });

  readonly showEmpty = computed(
    () => this.visibleOptions().length === 0 && !this.loading() && !this.loadError()
  );

  constructor() {
    effect(() => {
      const options = [...this.knownOptions(), ...this.options()];
      untracked(() => {
        const next = new Map(this.known());
        for (const option of options) next.set(key(option.id), option);
        this.known.set(next);
      });
    });
  }

  setDisabledFromForms(disabled: boolean): void {
    this.disabledByForms.set(disabled);
  }

  optionId(index: number): string {
    return `${this.listboxId}-opt-${index}`;
  }

  isSelected(option: SMTSelectOption<T>): boolean {
    return this.selectedKeys().has(key(option.id));
  }

  toggle(): void {
    if (this.open()) this.close(true);
    else this.openPopup();
  }

  openPopup(initialQuery = ''): void {
    if (this.isDisabled() || this.readonly() || this.open()) return;
    this.query.set(initialQuery);
    if (this.remoteSearch()) this.searchChange.emit(initialQuery);
    this.activeIndex.set(this.visibleOptions().length ? 0 : null);
    this.open.set(true);
  }

  onAttached(): void {
    this.claimModalOwnership();
    // After the render, not during it: moving focus inside change detection would re-enter it.
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

  toggleOption(option: SMTSelectOption<T>): void {
    if (option.disabled) return;
    const current = this.value() ?? [];
    this.value.set(
      this.isSelected(option) ? current.filter(id => key(id) !== key(option.id)) : [...current, option.id]
    );
  }

  remove(id: T): void {
    this.value.set((this.value() ?? []).filter(item => key(item) !== key(id)));
    this.trigger()?.nativeElement.focus();
  }

  highlight(index: number): void {
    this.activeIndex.set(index);
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
    const text = (event.target as HTMLInputElement).value;
    this.query.set(text);
    if (this.remoteSearch()) this.searchChange.emit(text);
    this.activeIndex.set(this.visibleOptions().length ? 0 : null);
  }

  clearSearch(): void {
    this.query.set('');
    if (this.remoteSearch()) this.searchChange.emit('');
    this.activeIndex.set(this.visibleOptions().length ? 0 : null);
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
        const option = index === null ? undefined : this.visibleOptions()[index];
        if (option) this.toggleOption(option);
        break;
      }
      case 'Backspace': {
        const current = this.value() ?? [];
        if (!this.query() && current.length) {
          event.preventDefault();
          this.value.set(current.slice(0, -1));
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

  onFocusOut(event: FocusEvent): void {
    const next = event.relatedTarget as Node | null;
    if (next && this.host.nativeElement.contains(next)) return;
    if (next instanceof Element && next.closest(`#${this.listboxId}-popup`)) return;
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

  private moveActive(delta: number): void {
    const last = this.visibleOptions().length - 1;
    if (last < 0) return;
    const current = this.activeIndex() ?? (delta > 0 ? -1 : last + 1);
    this.activeIndex.set(Math.min(Math.max(current + delta, 0), last));
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
}
