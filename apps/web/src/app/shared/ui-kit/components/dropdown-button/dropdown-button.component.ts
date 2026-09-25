/* Our code, after the idea of the kit's `smt-dropdown-button` and `smt-menu`
 * (smartup-ui-kit@6472beb, components/dropdown-button, menu). See ADR-0015
 * rule 2 and NOTICE.
 *
 * Why not the kit's copy: its menu was a list of clickable divs in an
 * overlay with its own positions and no menu semantics or keyboard.
 *
 * This one is built on the CDK menu, which implements the WAI-ARIA APG menu
 * button: the trigger has aria-haspopup and aria-expanded, the popup is a
 * role="menu" whose items are role="menuitem", arrows, Home, End and typing a
 * letter move between items, Escape closes and focus returns to the trigger.
 * Items come as data; a chosen item's id is emitted. A destructive item is
 * marked `danger` and, as elsewhere, asks for confirmation on the caller's
 * side. `smtIconOnly` gives a compact "more actions" button, named by
 * `smtAriaLabel`.
 *
 * <smt-dropdown-button [items]="actions" label="Actions" (itemSelect)="run($event)" />
 * <smt-dropdown-button smtIconOnly icon="more_vert" [smtAriaLabel]="…" [items]="rowActions" (itemSelect)="run($event, row)" /> */
import { booleanAttribute, ChangeDetectionStrategy, Component, input, output, ViewEncapsulation } from '@angular/core';
import { CdkMenu, CdkMenuItem, CdkMenuTrigger } from '@angular/cdk/menu';
import type { ConnectedPosition } from '@angular/cdk/overlay';

export interface SMTMenuItem<T extends string = string> {
  readonly id: T;
  readonly label: string;
  /** Material Symbols ligature. */
  readonly icon?: string;
  readonly disabled?: boolean;
  /** Deletes or revokes something: shown in the danger colour. */
  readonly danger?: boolean;
  /** A line above this item, to set a group apart. */
  readonly separated?: boolean;
}

const MENU_POSITIONS: ConnectedPosition[] = [
  { originX: 'end', originY: 'bottom', overlayX: 'end', overlayY: 'top', offsetY: 4 },
  { originX: 'end', originY: 'top', overlayX: 'end', overlayY: 'bottom', offsetY: -4 },
  { originX: 'start', originY: 'bottom', overlayX: 'start', overlayY: 'top', offsetY: 4 },
];

@Component({
  selector: 'smt-dropdown-button',
  standalone: true,
  imports: [CdkMenuTrigger, CdkMenu, CdkMenuItem],
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  styleUrl: './dropdown-button.scss',
  host: { class: 'smt-dropdown-button' },
  template: `
    <button
      type="button"
      class="smt-dropdown-button__trigger"
      [class.smt-dropdown-button__trigger--icon]="iconOnly()"
      [class.smt-dropdown-button__trigger--primary]="variant() === 'primary'"
      [cdkMenuTriggerFor]="menu"
      [cdkMenuPosition]="positions"
      [disabled]="disabled()"
      [attr.aria-label]="ariaLabel() || null">
      @if (icon()) {
        <span class="material-symbols-outlined" aria-hidden="true">{{ icon() }}</span>
      }
      @if (!iconOnly()) {
        <span class="smt-dropdown-button__label">{{ label() }}</span>
        <span class="material-symbols-outlined smt-dropdown-button__arrow" aria-hidden="true">expand_more</span>
      }
    </button>
    <ng-template #menu>
      <div class="smt-dropdown-button__menu" cdkMenu>
        @for (item of items(); track item.id) {
          @if (item.separated) {
            <div class="smt-dropdown-button__separator" role="separator"></div>
          }
          <button
            type="button"
            class="smt-dropdown-button__item"
            [class.smt-dropdown-button__item--danger]="item.danger"
            cdkMenuItem
            [cdkMenuItemDisabled]="!!item.disabled"
            (cdkMenuItemTriggered)="itemSelect.emit(item.id)">
            @if (item.icon) {
              <span class="material-symbols-outlined" aria-hidden="true">{{ item.icon }}</span>
            }
            <span>{{ item.label }}</span>
          </button>
        }
      </div>
    </ng-template>
  `,
})
export class SMTDropdownButtonComponent<T extends string = string> {
  readonly items = input<readonly SMTMenuItem<T>[]>([]);

  readonly label = input('');

  readonly icon = input('');

  /** Only the icon shows; the button is then named by `smtAriaLabel`. */
  readonly iconOnly = input(false, { alias: 'smtIconOnly', transform: booleanAttribute });

  readonly ariaLabel = input('', { alias: 'smtAriaLabel' });

  readonly variant = input<'secondary' | 'primary'>('secondary', { alias: 'smtVariant' });

  readonly disabled = input(false, { transform: booleanAttribute });

  readonly itemSelect = output<T>();

  readonly positions = MENU_POSITIONS;
}
