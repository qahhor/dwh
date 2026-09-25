/* Our code, after the idea of the kit's `smt-control` (smartup-ui-kit@6472beb,
 * components/forms/control). See ADR-0015 rule 2 and NOTICE.
 *
 * Why not the kit's copy: it read only a projected `NgControl`, computed an
 * error message it never rendered, hard-coded English text, and its label
 * had no `for`, so the field stayed unnamed for assistive technology.
 *
 * This wrapper reads the projected field itself:
 * - a Signal Forms `[formField]` (the default for new screens, ADR-0015
 *   decision on forms, variant A) — required, touched and errors come from
 *   its `FieldState`;
 * - a legacy `ngModel` / `formControl` — the same from `NgControl`.
 * It names the field with its label, links hint and error through
 * `aria-describedby`, and sets `aria-invalid` and `aria-required`.
 *
 * Usage (errors appear once the field is touched; call
 * `markSMTFormFieldsTouched(form)` before `submit()` to reveal all):
 *
 *   <smt-control [smtLabel]="'projects.name' | t" [smtError]="serverError()">
 *     <input class="form-input" [formField]="projectForm.name" />
 *   </smt-control> */
import {
  afterRenderEffect,
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  contentChild,
  effect,
  ElementRef,
  inject,
  input,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { NgControl, Validators } from '@angular/forms';
import { FormField } from '@angular/forms/signals';
import { SMTI18nService } from '../../../i18n';
import { shouldShowSMTFormControlError } from '../../../forms/form-control-validation';
import { fromLegacyErrors, messageForError, type SMTControlError } from './control-messages';

// A radio group, a switch and a tag group are fields too (smt-radio-group, smt-switch, smt-tag-group): the label names them.
const FIELD_SELECTOR = 'input:not([type="hidden"]), select, textarea, [role="combobox"], [role="textbox"], [role="radiogroup"], [role="switch"], [role="group"], [contenteditable="true"]';
const LABELABLE = new Set(['INPUT', 'SELECT', 'TEXTAREA', 'BUTTON', 'METER', 'OUTPUT', 'PROGRESS']);

let nextControlId = 0;

function optionalBoolean(value: unknown): boolean | undefined {
  return value === undefined || value === null ? undefined : booleanAttribute(value);
}

@Component({
  selector: 'smt-control',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  templateUrl: './control.component.html',
  styleUrl: './control.scss',
  host: {
    class: 'smt-control',
    '[class.smt-control--invalid]': 'showError()',
  },
})
export class SMTControlComponent {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  private readonly i18n = inject(SMTI18nService);

  readonly label = input('', { alias: 'smtLabel' });

  readonly hint = input('', { alias: 'smtHint' });

  /** Overrides the required state read from the field. */
  readonly required = input<boolean | undefined, unknown>(undefined, { transform: optionalBoolean });

  /** An error from outside the field, such as a server response; shown at once. */
  readonly error = input('', { alias: 'smtError' });

  private readonly formField = contentChild(FormField, { descendants: true });

  private readonly ngControl = contentChild(NgControl, { descendants: true });

  /** Bumped on every legacy control event, so computeds re-read it. */
  private readonly legacyVersion = signal(0);

  /** `required` attribute found on a native field (template-driven forms). */
  private readonly nativeRequired = signal(false);

  private readonly id = nextControlId++;

  readonly labelId = `smt-control-label-${this.id}`;

  readonly hintId = `smt-control-hint-${this.id}`;

  readonly errorId = `smt-control-error-${this.id}`;

  /** The id the label points at: the field's own id, or ours when it has none. */
  readonly fieldId = signal(`smt-control-field-${this.id}`);

  private readonly fieldState = computed(() => this.formField()?.state());

  readonly isRequired = computed(() => {
    const explicit = this.required();
    if (explicit !== undefined) return explicit;
    const state = this.fieldState();
    if (state) return state.required();
    this.legacyVersion();
    return !!this.ngControl()?.control?.hasValidator(Validators.required) || this.nativeRequired();
  });

  readonly errors = computed<readonly SMTControlError[]>(() => {
    const state = this.fieldState();
    if (state) return state.errors();
    this.legacyVersion();
    const errors = this.ngControl()?.control?.errors;
    return errors ? fromLegacyErrors(errors) : [];
  });

  private readonly touched = computed(() => {
    const state = this.fieldState();
    if (state) return state.touched();
    this.legacyVersion();
    return !!this.ngControl()?.control?.touched;
  });

  readonly showError = computed(
    () => !!this.error() || shouldShowSMTFormControlError({ errors: this.errors(), touched: this.touched() })
  );

  readonly errorMessage = computed(() => {
    if (this.error()) return this.error();
    return this.showError() ? messageForError(this.errors()[0], this.i18n.messages().control) : '';
  });

  /** Our tokens last written to the field's aria-describedby, so a rerun replaces only them. */
  private ownDescribedBy: string[] = [];

  constructor() {
    effect(onCleanup => {
      // `[formField]` also provides an NgControl for interop; its control has
      // no event stream, and the field state is read from signals anyway.
      if (this.formField()) return;
      const events = this.ngControl()?.control?.events;
      if (!events) return;
      const subscription = events.subscribe(() => this.legacyVersion.update(version => version + 1));
      onCleanup(() => subscription.unsubscribe());
    });

    afterRenderEffect({
      write: () => {
        const field = this.findField();
        const describedBy = [
          ...(this.hint() ? [this.hintId] : []),
          ...(this.showError() && this.errorMessage() ? [this.errorId] : []),
        ];
        const invalid = this.showError();
        const required = this.isRequired();
        const labelled = !!this.label();
        if (!field) return;

        if (field.id) {
          if (field.id !== this.fieldId()) this.fieldId.set(field.id);
        } else {
          field.id = this.fieldId();
        }

        if (labelled && !LABELABLE.has(field.tagName)) {
          field.setAttribute('aria-labelledby', this.labelId);
        }

        const kept = (field.getAttribute('aria-describedby') ?? '')
          .split(/\s+/)
          .filter(token => token && !this.ownDescribedBy.includes(token));
        const next = [...kept, ...describedBy];
        if (next.length) field.setAttribute('aria-describedby', next.join(' '));
        else field.removeAttribute('aria-describedby');
        this.ownDescribedBy = describedBy;

        if (invalid) field.setAttribute('aria-invalid', 'true');
        else field.removeAttribute('aria-invalid');

        if (required) field.setAttribute('aria-required', 'true');
        else field.removeAttribute('aria-required');

        const nativeRequired = (field as HTMLInputElement).required === true;
        if (nativeRequired !== this.nativeRequired()) this.nativeRequired.set(nativeRequired);
      },
    });
  }

  private findField(): HTMLElement | null {
    const slot = this.host.nativeElement.querySelector('.smt-control__field');
    return slot?.querySelector<HTMLElement>(FIELD_SELECTOR) ?? null;
  }
}
