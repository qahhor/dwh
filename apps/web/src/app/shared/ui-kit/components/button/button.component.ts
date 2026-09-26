/* Our code, after the idea of the kit's `smt-button` (smartup-ui-kit@6472beb,
 * components/button). See ADR-0015 rule 2 and NOTICE.
 *
 * Why not the kit's copy: it painted fourteen variants from a palette per
 * theme in utility classes and drew icons from the kit's sprite; the
 * application needs four variants from our tokens and Material Symbols.
 *
 * The kit's good idea stays: the component is an attribute on a real
 * <button> or <a>, not a wrapper around one. Type, form, disabled, the click
 * event and every aria-* attribute belong to the element a person uses, and
 * nothing sits between a label, a menu trigger or a test and the button.
 * While `smtLoading` the button is disabled and busy (aria-busy), and a
 * spinner with a hidden "in progress" text replaces the icon.
 *
 * <button smt-button type="submit" smtVariant="primary" smtIcon="save" [smtLoading]="saving">Save</button>
 * <a smt-button smtVariant="ghost" routerLink="/help">Help</a> */
import { booleanAttribute, ChangeDetectionStrategy, Component, computed, DestroyRef, ElementRef, inject, input, ViewEncapsulation } from '@angular/core';
import { SMTI18nService } from '../../i18n';

export type SMTButtonVariant = 'primary' | 'secondary' | 'danger' | 'ghost';

export type SMTButtonSize = 'sm' | 'md' | 'lg';

@Component({
  selector: 'button[smt-button], a[smt-button]',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  styleUrl: './button.scss',
  host: {
    class: 'smt-button',
    '[class.smt-button--primary]': "variant() === 'primary'",
    '[class.smt-button--secondary]': "variant() === 'secondary'",
    '[class.smt-button--danger]': "variant() === 'danger'",
    '[class.smt-button--ghost]': "variant() === 'ghost'",
    '[class.smt-button--sm]': "size() === 'sm'",
    '[class.smt-button--md]': "size() === 'md'",
    '[class.smt-button--lg]': "size() === 'lg'",
    '[class.smt-button--full]': 'fullWidth()',
    '[attr.disabled]': 'nativeDisabled()',
    '[attr.aria-disabled]': 'anchorDisabled()',
    '[attr.tabindex]': "anchorDisabled() ? '-1' : null",
    '[attr.aria-busy]': "loading() ? 'true' : null",
  },
  template: `
    @if (loading()) {
      <span class="smt-button__spinner" aria-hidden="true"></span>
      <span class="smt-button__sr-only">{{ i18n.messages().button.busy }}</span>
    } @else if (icon()) {
      <span class="material-symbols-outlined smt-button__icon" aria-hidden="true">{{ icon() }}</span>
    }
    <ng-content />
  `,
})
export class SMTButtonComponent {
  readonly i18n = inject(SMTI18nService);

  readonly variant = input<SMTButtonVariant>('primary', { alias: 'smtVariant' });

  readonly size = input<SMTButtonSize>('md', { alias: 'smtSize' });

  /** Material Symbols ligature before the text. */
  readonly icon = input<string | null | undefined>(null, { alias: 'smtIcon' });

  readonly loading = input(false, { alias: 'smtLoading', transform: booleanAttribute });

  readonly fullWidth = input(false, { alias: 'smtFullWidth', transform: booleanAttribute });

  readonly disabled = input(false, { transform: booleanAttribute });

  /** A <button> is disabled natively; a link cannot be, so it says so and leaves the tab order. */
  readonly nativeDisabled = computed(() => (!this.isAnchor && (this.disabled() || this.loading()) ? '' : null));

  readonly anchorDisabled = computed(() => (this.isAnchor && (this.disabled() || this.loading()) ? 'true' : null));

  private readonly host = inject(ElementRef<HTMLElement>).nativeElement as HTMLElement;

  private readonly isAnchor = this.host.tagName === 'A';

  constructor() {
    // Captured on the link itself, so it runs before the click handlers bound in a template.
    const guard = (event: Event) => this.guard(event);
    this.host.addEventListener('click', guard, true);
    inject(DestroyRef).onDestroy(() => this.host.removeEventListener('click', guard, true));
  }

  /** A disabled link must not navigate or fire its click handlers. */
  guard(event: Event): void {
    if (this.anchorDisabled()) {
      event.preventDefault();
      event.stopImmediatePropagation();
    }
  }
}
