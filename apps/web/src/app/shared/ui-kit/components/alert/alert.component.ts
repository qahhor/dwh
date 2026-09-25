/* Our code, after the idea of the kit's `smt-alert` (smartup-ui-kit@6472beb,
 * components/alert). See ADR-0015 rule 2 and NOTICE.
 *
 * Why not the kit's copy: it drew the kit's icon sprite and palette and
 * left the live region to the caller; the application had twenty-two
 * hand-made `.alert` blocks, each deciding its role on its own.
 *
 * A message in a tone — danger, warning, success, info — with the tone's
 * icon, an optional title and, when the caller allows, a close button named
 * "Close". Its live role follows the tone: a danger message is an alert read
 * at once, the others a polite status; `smtLive="off"` for a message that is
 * part of the page rather than news (a result shown as it loads). */
import { booleanAttribute, ChangeDetectionStrategy, Component, computed, inject, input, output, ViewEncapsulation } from '@angular/core';
import { SMTI18nService } from '../../i18n';

export type SMTAlertTone = 'danger' | 'warning' | 'success' | 'info';

const ICONS: Readonly<Record<SMTAlertTone, string>> = {
  danger: 'error',
  warning: 'warning',
  success: 'check_circle',
  info: 'info',
};

@Component({
  selector: 'smt-alert',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  styleUrl: './alert.scss',
  host: {
    class: 'smt-alert',
    '[class.smt-alert--danger]': "tone() === 'danger'",
    '[class.smt-alert--warning]': "tone() === 'warning'",
    '[class.smt-alert--success]': "tone() === 'success'",
    '[class.smt-alert--info]': "tone() === 'info'",
    '[attr.role]': 'role()',
  },
  template: `
    <span class="material-symbols-outlined smt-alert__icon" aria-hidden="true">{{ icon() }}</span>
    <div class="smt-alert__body">
      @if (title()) {
        <p class="smt-alert__title">{{ title() }}</p>
      }
      <div class="smt-alert__content"><ng-content /></div>
    </div>
    @if (dismissible()) {
      <button type="button" class="smt-alert__close" [attr.aria-label]="i18n.messages().common.close" (click)="dismiss.emit()">
        <span class="material-symbols-outlined" aria-hidden="true">close</span>
      </button>
    }
  `,
})
export class SMTAlertComponent {
  readonly i18n = inject(SMTI18nService);

  readonly tone = input<SMTAlertTone>('info', { alias: 'smtTone' });

  readonly title = input('', { alias: 'smtTitle' });

  /** `auto` follows the tone; `off` for a message that is part of the page, not news. */
  readonly live = input<'auto' | 'assertive' | 'polite' | 'off'>('auto', { alias: 'smtLive' });

  readonly dismissible = input(false, { alias: 'smtDismissible', transform: booleanAttribute });

  readonly dismiss = output<void>({ alias: 'smtDismiss' });

  readonly icon = computed(() => ICONS[this.tone()]);

  readonly role = computed(() => {
    const live = this.live() === 'auto' ? (this.tone() === 'danger' ? 'assertive' : 'polite') : this.live();
    return live === 'assertive' ? 'alert' : live === 'polite' ? 'status' : null;
  });
}
