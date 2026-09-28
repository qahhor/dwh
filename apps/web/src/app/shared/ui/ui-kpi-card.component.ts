import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { I18nService } from '@core/services/i18n.service';

/**
 * One key figure with its change against the period before (roadmap item 26).
 * Whether a rise is good depends on the figure — more rows applied is good,
 * more rejections is not — so the card is told which way is good and colours
 * the change accordingly. The whole reading ("Applied: 42, up 12% on the
 * previous period") is one sentence for screen readers.
 */
@Component({
  selector: 'ui-kpi-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="kpi" [class.kpi--alert]="alert()" [attr.aria-label]="sentence()" role="group">
      <span class="kpi__head">
        <span class="kpi__label" aria-hidden="true">{{ label() }}</span>
        @if (icon()) {
          <span
            class="material-symbols-outlined kpi__icon"
            [class]="'material-symbols-outlined kpi__icon kpi__icon--' + tone()"
            aria-hidden="true"
            >{{ icon() }}</span
          >
        }
      </span>
      <span class="kpi__value" aria-hidden="true">{{ shown() }}</span>
      @if (change(); as c) {
        <span
          class="kpi__change"
          [class]="'kpi__change kpi__change--' + c.tone"
          aria-hidden="true"
          data-testid="kpi-change"
        >
          <span class="material-symbols-outlined" aria-hidden="true">{{ c.icon }}</span
          >{{ c.text }}
        </span>
      }
      <span class="kpi__meta"><ng-content /></span>
    </div>
  `,
  styleUrl: './ui-kpi-card.component.css',
})
export class UiKpiCardComponent {
  private readonly i18n = inject(I18nService);

  readonly label = input.required<string>();
  /** A number is formatted for the language; text (a percentage, a size) is shown as it is. */
  readonly value = input.required<number | string>();

  /** The same figure in the period before; none — no comparison. */
  readonly previous = input<number | null>(null);
  /** Which way is good for this figure. */
  readonly goodWhen = input<'up' | 'down' | 'neutral'>('up');
  /** A Material Symbols icon at the top right, coloured by `tone`. */
  readonly icon = input('');
  readonly tone = input<'primary' | 'success' | 'info' | 'warning' | 'danger' | 'neutral'>('neutral');
  /** Draws attention to a figure that needs it (failed sign-ins, a quota nearly used). */
  readonly alert = input(false);

  readonly shown = computed(() => {
    const value = this.value();
    return typeof value === 'number' ? this.number(value) : value;
  });

  /** The change as a percentage, or as a count when the period before had none. */
  readonly change = computed(() => {
    const previous = this.previous();
    const value = this.value();
    if (previous === null || previous === undefined || typeof value !== 'number') return null;
    const diff = value - previous;
    if (diff === 0) {
      return { tone: 'same', icon: 'trending_flat', text: this.i18n.translate('ui.kpi.same') };
    }
    const up = diff > 0;
    const good = this.goodWhen() === 'neutral' ? 'same' : up === (this.goodWhen() === 'up') ? 'good' : 'bad';
    const amount =
      previous === 0 ? this.number(Math.abs(diff)) : `${this.number(Math.round((Math.abs(diff) / previous) * 100))}%`;
    return {
      tone: good,
      icon: up ? 'trending_up' : 'trending_down',
      text: this.i18n.translate(up ? 'ui.kpi.up' : 'ui.kpi.down', { amount }),
    };
  });

  readonly sentence = computed(() => {
    const change = this.change();
    const base = `${this.label()}: ${this.shown()}`;
    return change ? `${base}, ${change.text}` : base;
  });

  private number(value: number): string {
    return new Intl.NumberFormat(this.i18n.currentLang()).format(value);
  }
}
