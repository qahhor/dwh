/* Our code, after the idea of the kit's `smt-tab-bar` (smartup-ui-kit@6472beb,
 * components/tab-bar). See ADR-0015 rule 2 and NOTICE.
 *
 * Why not the kit's copy: it mixed router links, nested tabs and badges from
 * the kit's sprite; the application instead had twelve tab bars of its own,
 * most without arrow keys or a single tab stop.
 *
 * This one follows the WAI-ARIA APG tabs pattern with automatic activation:
 * role="tablist" named by `smtAriaLabel`, role="tab" items with
 * aria-selected and, when the caller names its panel, aria-controls; the
 * chosen tab is the only tab stop, arrows move and choose (Home and End go to
 * the ends), disabled tabs are skipped. A count is read as part of the tab's
 * name. A focused tab in a bar wider than its place scrolls to the middle
 * (only the latest one, once focus settles). The panels stay the caller's: give each `role="tabpanel"` and
 * `aria-labelledby` the tab's id (`tabId(value)` or the item's own `id`).
 *
 * <smt-tab-bar [tabs]="sections" [(value)]="section" smtAriaLabel="Settings" /> */
import { ChangeDetectionStrategy, Component, computed, DestroyRef, ElementRef, inject, input, model, viewChildren, ViewEncapsulation } from '@angular/core';

export interface SMTTabItem<K extends string = string> {
  readonly value: K;
  readonly label: string;
  /** Material Symbols ligature before the label. */
  readonly icon?: string;
  /** A number after the label, read as part of the tab's name. */
  readonly count?: number;
  /** `attention` marks the count, e.g. unread notifications. */
  readonly countTone?: 'muted' | 'attention';
  readonly disabled?: boolean;
  /** The tab's id when the caller needs a fixed one; otherwise `{idPrefix}-{value}-tab`. */
  readonly id?: string;
  /** The id of the panel this tab shows, for aria-controls. */
  readonly panelId?: string;
}

let nextTabBarId = 0;

@Component({
  selector: 'smt-tab-bar',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  styleUrl: './tab-bar.scss',
  host: { class: 'smt-tab-bar' },
  template: `
    <div class="smt-tab-bar__list" role="tablist" [attr.aria-label]="ariaLabel() || null">
      @for (tab of tabs(); track tab.value; let index = $index) {
        <button
          #tab
          type="button"
          role="tab"
          class="smt-tab-bar__tab"
          [class.smt-tab-bar__tab--active]="tab.value === value()"
          [id]="idOf(tab)"
          [attr.aria-selected]="tab.value === value() ? 'true' : 'false'"
          [attr.aria-controls]="tab.panelId || null"
          [attr.tabindex]="tabStop() === index ? 0 : -1"
          [disabled]="!!tab.disabled"
          (click)="choose(index)"
          (focus)="centre($event)"
          (keydown)="onKeydown($event, index)">
          @if (tab.icon) {
            <span class="material-symbols-outlined smt-tab-bar__icon" aria-hidden="true">{{ tab.icon }}</span>
          }
          <span class="smt-tab-bar__label">{{ tab.label }}</span>
          @if (tab.count !== undefined) {
            <!-- The space keeps the name "Audit 3", not "Audit3"; flex drops it from the layout. -->
            &ngsp;<span class="smt-tab-bar__count" [class.smt-tab-bar__count--attention]="tab.countTone === 'attention'">{{ tab.count }}</span>
          }
        </button>
      }
    </div>
  `,
})
export class SMTTabBarComponent<K extends string = string> {
  readonly tabs = input<readonly SMTTabItem<K>[]>([]);

  readonly ariaLabel = input('', { alias: 'smtAriaLabel' });

  /** Prefix of generated tab ids. */
  readonly idPrefix = input(`smt-tab-bar-${nextTabBarId++}`, { alias: 'smtIdPrefix' });

  readonly value = model<K | null>(null);

  private readonly buttons = viewChildren<ElementRef<HTMLButtonElement>>('tab');

  /** The chosen tab, or the first available one while nothing is chosen. */
  readonly tabStop = computed(() => {
    const tabs = this.tabs();
    const chosen = tabs.findIndex(tab => tab.value === this.value() && !tab.disabled);
    return chosen >= 0 ? chosen : tabs.findIndex(tab => !tab.disabled);
  });

  private pendingCentre: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      if (this.pendingCentre !== null) clearTimeout(this.pendingCentre);
    });
  }

  /** Brings a focused tab to the middle of a scrolled bar, once focus has settled on it. */
  centre(event: FocusEvent): void {
    const tab = event.target as HTMLElement;
    if (this.pendingCentre !== null) clearTimeout(this.pendingCentre);
    this.pendingCentre = setTimeout(() => {
      this.pendingCentre = null;
      if (!tab.isConnected || document.activeElement !== tab || typeof tab.scrollIntoView !== 'function') return;
      tab.scrollIntoView({ behavior: 'instant', block: 'nearest', inline: 'center' });
    }, 0);
  }

  /** The id a panel's aria-labelledby names. */
  tabId(value: K): string {
    const tab = this.tabs().find(item => item.value === value);
    return tab ? this.idOf(tab) : `${this.idPrefix()}-${value}-tab`;
  }

  idOf(tab: SMTTabItem<K>): string {
    return tab.id ?? `${this.idPrefix()}-${tab.value}-tab`;
  }

  choose(index: number): void {
    const tab = this.tabs()[index];
    if (!tab || tab.disabled) return;
    this.value.set(tab.value);
  }

  onKeydown(event: KeyboardEvent, index: number): void {
    const tabs = this.tabs();
    const available = tabs.map((tab, i) => (tab.disabled ? -1 : i)).filter(i => i >= 0);
    if (available.length === 0) return;
    const position = available.indexOf(index);
    let target: number | undefined;
    switch (event.key) {
      case 'ArrowRight':
      case 'ArrowDown':
        target = available[(position + 1) % available.length];
        break;
      case 'ArrowLeft':
      case 'ArrowUp':
        target = available[(position - 1 + available.length) % available.length];
        break;
      case 'Home':
        target = available[0];
        break;
      case 'End':
        target = available[available.length - 1];
        break;
      default:
        return;
    }
    event.preventDefault();
    this.buttons()[target]?.nativeElement.focus();
    this.choose(target);
  }
}
