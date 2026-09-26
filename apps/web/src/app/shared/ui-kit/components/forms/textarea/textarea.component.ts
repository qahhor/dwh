/* Our code, after the idea of the kit's `smt-textarea` (smartup-ui-kit@6472beb,
 * components/forms/textarea). See ADR-0015 rule 2 and NOTICE.
 *
 * Why not the kit's copy: it drew its own label and messages next to
 * smt-control's, read the kit's ControlService, painted a palette per theme
 * instead of tokens, and showed the length only in the last five characters
 * as a red badge a screen reader never heard.
 *
 * This one is only the field; smt-control around it gives the label, hint
 * and error. It grows with its text up to `maxRows` (then scrolls), and with
 * a `maxLength` shows "used of allowed", linked to the field through
 * aria-describedby so assistive technology reads it too.
 *
 * Signal Forms: <smt-control [smtLabel]="…"><smt-textarea [formField]="form.note" /></smt-control>
 * Plain:        <smt-textarea [(value)]="note" [maxLength]="500" /> */
import {
  afterRenderEffect,
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
  viewChild,
  ViewEncapsulation,
} from '@angular/core';
import type { FormValueControl, ValidationError, WithOptionalFieldTree } from '@angular/forms/signals';
import { SMTI18nService } from '../../../i18n';
import { SMT_FORM_FIELD_REGISTRY_HOST_DIRECTIVES } from '../../../forms/field-registry';
import { shouldShowSMTFormControlError } from '../../../forms/form-control-validation';

let nextTextareaId = 0;

function optionalNumber(value: unknown): number | undefined {
  return value === undefined || value === null || value === '' ? undefined : numberAttribute(value);
}

@Component({
  selector: 'smt-textarea',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  templateUrl: './textarea.component.html',
  styleUrl: './textarea.scss',
  host: {
    class: 'smt-textarea',
    '[class.smt-textarea--invalid]': 'hasError()',
    '[class.smt-textarea--disabled]': 'isDisabled()',
  },
  hostDirectives: [...SMT_FORM_FIELD_REGISTRY_HOST_DIRECTIVES],
})
export class SMTTextareaComponent implements FormValueControl<string> {
  readonly i18n = inject(SMTI18nService);

  /** The inner field's id, for an outside label; not `id`, which would also land on the host element. */
  readonly customId = input<string | null>(null, { alias: 'smtFieldId' });

  readonly name = input('');

  readonly placeholder = input('');

  /** Ids of hints outside any smt-control that describe the field; the counter is added after them. */
  readonly describedBy = input('', { alias: 'smtDescribedBy' });

  /** Height before any text; the field grows from here. */
  readonly rows = input(3, { transform: numberAttribute });

  /** Where growing stops and the field starts to scroll. */
  readonly maxRows = input(12, { transform: numberAttribute });

  /** false keeps the height fixed at `rows`; the person can still drag it taller. */
  readonly autoResize = input(true, { transform: booleanAttribute });

  readonly maxLength = input<number | undefined, unknown>(undefined, { transform: optionalNumber });

  readonly minLength = input<number | undefined, unknown>(undefined, { transform: optionalNumber });

  readonly disabled = input(false, { transform: booleanAttribute });

  readonly readonly = input(false, { transform: booleanAttribute });

  readonly required = input(false, { transform: booleanAttribute });

  readonly invalid = input(false, { transform: booleanAttribute });

  /** An error the screen decides itself (after a failed save), shown at once; Signal Forms fills `invalid`. */
  readonly screenInvalid = input(false, { alias: 'smtInvalid', transform: booleanAttribute });

  readonly errors = input<readonly WithOptionalFieldTree<ValidationError>[]>([]);

  /** Bound by Signal Forms; the field's touched state. */
  readonly touched = input(false, { transform: booleanAttribute });

  /** Tells Signal Forms the person has left the field (Angular 22 reads `touch`, not `touchedChange`). */
  readonly touch = output<void>();

  /** The text; `null` from a form is shown as empty. */
  readonly value = model<string>('');

  private readonly field = viewChild.required<ElementRef<HTMLTextAreaElement>>('field');

  /** Touched here or by the form; follows the form again when it resets `touched`. */
  protected readonly wasTouched = linkedSignal(() => this.touched());

  /** Disabled by a reactive form or ngModel through the value accessor. */
  private readonly formsDisabled = signal(false);

  readonly isDisabled = computed(() => this.disabled() || this.formsDisabled());

  readonly text = computed(() => this.value() ?? '');

  readonly hasError = computed(() =>
    shouldShowSMTFormControlError({
      invalid: this.invalid(),
      legacyInvalid: this.screenInvalid(),
      errors: this.errors(),
      touched: this.wasTouched(),
      required: this.required(),
      empty: this.text().trim() === '',
    })
  );

  readonly counter = computed(() => {
    const max = this.maxLength();
    return max === undefined ? null : this.i18n.messages().textarea.counter(this.text().length, max);
  });

  readonly describedByIds = computed(() => [this.describedBy().trim(), this.counter() ? this.counterId : ''].filter(Boolean).join(' ') || null);

  /** The last tenth of the allowed length is marked, so the limit is not a surprise. */
  readonly nearLimit = computed(() => {
    const max = this.maxLength();
    return max !== undefined && this.text().length >= max * 0.9;
  });

  readonly fieldId = `smt-textarea-${nextTextareaId++}`;

  readonly counterId = `${this.fieldId}-counter`;

  constructor() {
    afterRenderEffect({
      write: () => {
        this.text();
        const element = this.field().nativeElement;
        if (!this.autoResize()) {
          element.style.removeProperty('height');
          return;
        }
        this.fit(element);
      },
    });
  }

  onInput(event: Event): void {
    this.value.set((event.target as HTMLTextAreaElement).value);
  }

  onBlur(): void {
    this.markTouched();
  }

  /** The person left the field. */
  markTouched(): void {
    this.wasTouched.set(true);
    this.touch.emit();
  }

  /** Called by SMTTextareaValueAccessor. */
  setDisabledFromForms(disabled: boolean): void {
    this.formsDisabled.set(disabled);
  }

  /** Height to the text, between `rows` and `maxRows` lines. */
  private fit(element: HTMLTextAreaElement): void {
    const style = getComputedStyle(element);
    const line = parseFloat(style.lineHeight) || 20;
    const chrome = (parseFloat(style.paddingTop) || 0) + (parseFloat(style.paddingBottom) || 0)
      + (parseFloat(style.borderTopWidth) || 0) + (parseFloat(style.borderBottomWidth) || 0);
    const min = this.rows() * line + chrome;
    const max = Math.max(this.maxRows(), this.rows()) * line + chrome;
    element.style.height = 'auto';
    const wanted = element.scrollHeight + (parseFloat(style.borderTopWidth) || 0) + (parseFloat(style.borderBottomWidth) || 0);
    element.style.height = `${Math.min(Math.max(wanted, min), max)}px`;
    element.style.overflowY = wanted > max ? 'auto' : 'hidden';
  }
}
