/* Our code, after the idea of the kit's `smt-rating` (smartup-ui-kit@6472beb,
 * components/forms/rating). See ADR-0015 rule 2 and NOTICE.
 *
 * Why not the kit's copy: it drew stars from the kit's sprite in a palette
 * per theme and named every star "Rating".
 *
 * A rating is a choice of one value from 1 to `smtMax`, so it is a radio
 * group (WAI-ARIA APG): each star is a radio named "3 of 5", the chosen star
 * is the only tab stop, arrows move and choose, Home and End go to the ends.
 * `clearable` lets a click on the chosen star take the rating back.
 *
 * Signal Forms: <smt-rating [formField]="review.stars" smtAriaLabel="…" />
 * Plain:        <smt-rating [(value)]="stars" [smtMax]="10" /> */
import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  input,
  linkedSignal,
  model,
  numberAttribute,
  output,
  signal,
  viewChildren,
  ViewEncapsulation,
} from '@angular/core';
import type { FormValueControl, ValidationError, WithOptionalFieldTree } from '@angular/forms/signals';
import { SMTI18nService } from '../../../i18n';
import { SMT_FORM_FIELD_REGISTRY_HOST_DIRECTIVES } from '../../../forms/field-registry';
import { shouldShowSMTFormControlError } from '../../../forms/form-control-validation';

@Component({
  selector: 'smt-rating',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  styleUrl: './rating.scss',
  host: {
    class: 'smt-rating',
    '[class.smt-rating--disabled]': 'isDisabled()',
    '[class.smt-rating--invalid]': 'hasError()',
  },
  hostDirectives: [...SMT_FORM_FIELD_REGISTRY_HOST_DIRECTIVES],
  template: `
    <div
      class="smt-rating__group"
      role="radiogroup"
      [attr.aria-label]="ariaLabel() || i18n.messages().rating.name"
      [attr.aria-required]="required() ? 'true' : null"
      [attr.aria-invalid]="hasError() ? 'true' : null"
      [attr.aria-readonly]="readonly() ? 'true' : null"
      [attr.aria-disabled]="isDisabled() ? 'true' : null">
      @for (star of stars(); track star) {
        <button
          #star
          type="button"
          role="radio"
          class="smt-rating__star"
          [class.smt-rating__star--on]="star <= shown()"
          [attr.aria-checked]="value() === star ? 'true' : 'false'"
          [attr.aria-label]="i18n.messages().rating.star(star, starCount())"
          [attr.tabindex]="tabStop() === star ? 0 : -1"
          [disabled]="isDisabled()"
          (click)="choose(star)"
          (mouseenter)="hovered.set(star)"
          (mouseleave)="hovered.set(null)"
          (keydown)="onKeydown($event, star)"
          (blur)="markTouched()">
          <span class="material-symbols-outlined" aria-hidden="true">star</span>
        </button>
      }
    </div>
  `,
})
export class SMTRatingComponent implements FormValueControl<number | null> {
  readonly i18n = inject(SMTI18nService);

  readonly starCount = input(5, { alias: 'smtMax', transform: numberAttribute });

  readonly ariaLabel = input('', { alias: 'smtAriaLabel' });

  /** A click on the chosen star clears the rating. */
  readonly clearable = input(false, { transform: booleanAttribute });

  readonly disabled = input(false, { transform: booleanAttribute });

  readonly readonly = input(false, { transform: booleanAttribute });

  readonly required = input(false, { transform: booleanAttribute });

  readonly invalid = input(false, { transform: booleanAttribute });

  readonly errors = input<readonly WithOptionalFieldTree<ValidationError>[]>([]);

  /** Bound by Signal Forms; the field's touched state. */
  readonly touched = input(false, { transform: booleanAttribute });

  /** The person left the group (Angular 22 reads `touch`). */
  readonly touch = output<void>();

  /** 1 … max, or null for no rating. */
  readonly value = model<number | null>(null);

  private readonly buttons = viewChildren<ElementRef<HTMLButtonElement>>('star');

  /** The star under the pointer, previewed until it leaves. */
  readonly hovered = signal<number | null>(null);

  protected readonly wasTouched = linkedSignal(() => this.touched());

  private readonly formsDisabled = signal(false);

  readonly isDisabled = computed(() => this.disabled() || this.formsDisabled());

  readonly stars = computed(() => Array.from({ length: Math.max(1, this.starCount()) }, (_, index) => index + 1));

  readonly shown = computed(() => this.hovered() ?? this.value() ?? 0);

  /** The chosen star, or the first while there is no rating. */
  readonly tabStop = computed(() => this.value() ?? 1);

  readonly hasError = computed(() =>
    shouldShowSMTFormControlError({
      invalid: this.invalid(),
      errors: this.errors(),
      touched: this.wasTouched(),
      required: this.required(),
      empty: this.value() === null,
    })
  );

  choose(star: number): void {
    if (this.isDisabled() || this.readonly()) return;
    this.value.set(this.clearable() && this.value() === star ? null : star);
  }

  onKeydown(event: KeyboardEvent, star: number): void {
    const max = this.stars().length;
    const next: Record<string, number> = {
      ArrowRight: Math.min(max, star + 1),
      ArrowUp: Math.min(max, star + 1),
      ArrowLeft: Math.max(1, star - 1),
      ArrowDown: Math.max(1, star - 1),
      Home: 1,
      End: max,
    };
    if (!(event.key in next)) return;
    event.preventDefault();
    const target = next[event.key];
    this.buttons()[target - 1]?.nativeElement.focus();
    if (!this.readonly()) this.value.set(target);
  }

  markTouched(): void {
    this.wasTouched.set(true);
    this.touch.emit();
  }

  /** Called by SMTRatingValueAccessor. */
  setDisabledFromForms(disabled: boolean): void {
    this.formsDisabled.set(disabled);
  }
}
