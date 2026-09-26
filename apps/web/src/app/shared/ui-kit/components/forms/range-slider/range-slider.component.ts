/* Our code, after the idea of the kit's `smt-range-slider` (smartup-ui-kit@6472beb,
 * components/forms/range-slider). See ADR-0015 rule 2 and NOTICE.
 *
 * Why not the kit's copy: it chose one value with a single thumb; the
 * screens that need a slider filter by a band ("from 10 to 40 days"), and
 * its track was painted per theme instead of from tokens.
 *
 * Two native range inputs share one track, so each thumb is a real slider
 * for assistive technology and the keyboard (arrows, Page Up/Down, Home,
 * End), named "From" and "To" after the group's name. A thumb stops at the
 * other one, so `from` never passes `to`. The chosen band is filled; the
 * values are shown beside the track with an optional suffix.
 *
 * Signal Forms: <smt-range-slider [formField]="filter.days" [smtMin]="0" [smtMax]="90" />
 * Plain:        <smt-range-slider [(value)]="band" smtAriaLabel="Days" smtSuffix=" d" /> */
import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  model,
  numberAttribute,
  output,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import type { FormValueControl, ValidationError, WithOptionalFieldTree } from '@angular/forms/signals';
import { SMTI18nService } from '../../../i18n';
import { SMT_FORM_FIELD_REGISTRY_HOST_DIRECTIVES } from '../../../forms/field-registry';
import { shouldShowSMTFormControlError } from '../../../forms/form-control-validation';

/** The chosen band, both ends included. */
export interface SMTRange {
  readonly from: number;
  readonly to: number;
}

@Component({
  selector: 'smt-range-slider',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  styleUrl: './range-slider.scss',
  host: {
    class: 'smt-range-slider',
    role: 'group',
    '[attr.aria-label]': 'ariaLabel() || null',
    '[class.smt-range-slider--disabled]': 'isDisabled()',
    '[class.smt-range-slider--invalid]': 'hasError()',
  },
  hostDirectives: [...SMT_FORM_FIELD_REGISTRY_HOST_DIRECTIVES],
  template: `
    <div class="smt-range-slider__track" [style.--smt-range-from]="percent(band().from)" [style.--smt-range-to]="percent(band().to)">
      <input
        type="range"
        class="smt-range-slider__thumb"
        [min]="minimum()"
        [max]="maximum()"
        [step]="step()"
        [value]="band().from"
        [disabled]="isDisabled() || readonly()"
        [attr.aria-label]="thumbName(i18n.messages().range.from)"
        [attr.aria-valuetext]="band().from + suffix()"
        (input)="setFrom($event)"
        (blur)="markTouched()" />
      <input
        type="range"
        class="smt-range-slider__thumb"
        [min]="minimum()"
        [max]="maximum()"
        [step]="step()"
        [value]="band().to"
        [disabled]="isDisabled() || readonly()"
        [attr.aria-label]="thumbName(i18n.messages().range.to)"
        [attr.aria-valuetext]="band().to + suffix()"
        (input)="setTo($event)"
        (blur)="markTouched()" />
    </div>
    @if (showValues()) {
      <span class="smt-range-slider__values" aria-hidden="true">{{ band().from }}{{ suffix() }} – {{ band().to }}{{ suffix() }}</span>
    }
  `,
})
export class SMTRangeSliderComponent implements FormValueControl<SMTRange | null> {
  readonly i18n = inject(SMTI18nService);

  readonly minimum = input(0, { alias: 'smtMin', transform: numberAttribute });

  readonly maximum = input(100, { alias: 'smtMax', transform: numberAttribute });

  readonly step = input(1, { alias: 'smtStep', transform: numberAttribute });

  /** Names the group; each thumb is "<name>: From" and "<name>: To". */
  readonly ariaLabel = input('', { alias: 'smtAriaLabel' });

  /** Shown after each value, e.g. " d" or " %". */
  readonly suffix = input('', { alias: 'smtSuffix' });

  readonly showValues = input(true, { alias: 'smtShowValues', transform: booleanAttribute });

  readonly disabled = input(false, { transform: booleanAttribute });

  readonly readonly = input(false, { transform: booleanAttribute });

  readonly invalid = input(false, { transform: booleanAttribute });

  readonly errors = input<readonly WithOptionalFieldTree<ValidationError>[]>([]);

  /** Bound by Signal Forms; the field's touched state. */
  readonly touched = input(false, { transform: booleanAttribute });

  /** The person left a thumb (Angular 22 reads `touch`). */
  readonly touch = output<void>();

  /** null means the whole range. */
  readonly value = model<SMTRange | null>(null);

  protected readonly wasTouched = linkedSignal(() => this.touched());

  private readonly formsDisabled = signal(false);

  readonly isDisabled = computed(() => this.disabled() || this.formsDisabled());

  /** The band shown: the value held inside the bounds, or the whole range. */
  readonly band = computed<SMTRange>(() => {
    const min = this.minimum();
    const max = Math.max(min, this.maximum());
    const value = this.value();
    const from = Math.min(Math.max(value?.from ?? min, min), max);
    const to = Math.min(Math.max(value?.to ?? max, from), max);
    return { from, to };
  });

  readonly hasError = computed(() =>
    shouldShowSMTFormControlError({
      invalid: this.invalid(),
      errors: this.errors(),
      touched: this.wasTouched(),
    })
  );

  thumbName(part: string): string {
    return this.ariaLabel() ? `${this.ariaLabel()}: ${part}` : part;
  }

  percent(value: number): string {
    const span = this.maximum() - this.minimum();
    return `${span > 0 ? ((value - this.minimum()) / span) * 100 : 0}%`;
  }

  setFrom(event: Event): void {
    const input = event.target as HTMLInputElement;
    const from = Math.min(Number(input.value), this.band().to);
    input.value = String(from);
    this.value.set({ from, to: this.band().to });
  }

  setTo(event: Event): void {
    const input = event.target as HTMLInputElement;
    const to = Math.max(Number(input.value), this.band().from);
    input.value = String(to);
    this.value.set({ from: this.band().from, to });
  }

  markTouched(): void {
    this.wasTouched.set(true);
    this.touch.emit();
  }

  /** Called by SMTRangeSliderValueAccessor. */
  setDisabledFromForms(disabled: boolean): void {
    this.formsDisabled.set(disabled);
  }
}
