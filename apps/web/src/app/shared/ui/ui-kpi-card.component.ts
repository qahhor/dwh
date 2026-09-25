import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { I18nService } from '../../core/services/i18n.service';

/**
 * One key figure with its change against the period before (roadmap item 26).
 * Whether a rise is good depends on the figure — more rows applied is good,
 * more rejections is not — so the card is told which way is good and colours
 * the change accordingly. The whole reading ("Applied: 42, up 12% on the
 * previous period") is one sentence for screen readers.
 */
@Component({
  selector: 'ui-kpi-card',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="kpi" [attr.aria-label]="sentence()" role="group">
      <span class="kpi__label" aria-hidden="true">{{ label() }}</span>
      <span class="kpi__value" aria-hidden="true">{{ shown() }}</span>
      @if (change(); as c) {
        <span class="kpi__change" [class]="'kpi__change kpi__change--' + c.tone" aria-hidden="true" data-testid="kpi-change">
          <span class="material-symbols-outlined" aria-hidden="true">{{ c.icon }}</span>{{ c.text }}
        </span>
      }
    </div>
  `,
  styles: [`
    :host { display: block; min-width: 0; }
    .kpi { display: flex; flex-direction: column; gap: 4px; padding: 12px; border-radius: var(--radius-sm); background: var(--bg-hover); height: 100%; }
    .kpi__label { font-size: 12px; color: var(--text-muted); }
    .kpi__value { font-size: 24px; font-weight: 700; color: var(--text-main); font-variant-numeric: tabular-nums; }
    .kpi__change { display: inline-flex; align-items: center; gap: 2px; font-size: 12px; color: var(--text-muted); }
    .kpi__change .material-symbols-outlined { font-size: 16px; }
    .kpi__change--good { color: var(--success-text, var(--success)); }
    .kpi__change--bad { color: var(--danger-text); }
  `]
})
export class UiKpiCardComponent {
  readonly label = input.required<string>();
  readonly value = input.required<number>();
  /** The same figure in the period before; none — no comparison. */
  readonly previous = input<number | null>(null);
  /** Which way is good for this figure. */
  readonly goodWhen = input<'up' | 'down' | 'neutral'>('up');

  private readonly i18n = inject(I18nService);

  readonly shown = computed(() => this.number(this.value()));

  /** The change as a percentage, or as a count when the period before had none. */
  readonly change = computed(() => {
    const previous = this.previous();
    const value = this.value();
    if (previous === null || previous === undefined) return null;
    const diff = value - previous;
    if (diff === 0) {
      return { tone: 'same', icon: 'trending_flat', text: this.i18n.translate('ui.kpi.same') };
    }
    const up = diff > 0;
    const good = this.goodWhen() === 'neutral' ? 'same' : (up === (this.goodWhen() === 'up') ? 'good' : 'bad');
    const amount = previous === 0
      ? this.number(Math.abs(diff))
      : `${this.number(Math.round(Math.abs(diff) / previous * 100))}%`;
    return {
      tone: good,
      icon: up ? 'trending_up' : 'trending_down',
      text: this.i18n.translate(up ? 'ui.kpi.up' : 'ui.kpi.down', { amount })
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
