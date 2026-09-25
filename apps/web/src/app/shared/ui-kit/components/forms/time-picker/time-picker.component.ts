/* Our code, after the idea of the kit's `smt-time-picker` (smartup-ui-kit@6472beb,
 * components/forms/time-picker). See ADR-0015 rule 2 and NOTICE.
 *
 * Why not the kit's copy: it needed the kit's select trigger, time-unit
 * grid, input mask directive and ControlService, spread over six files; its
 * hour/minute/second columns took three pointer trips for one time, had no
 * listbox semantics and painted a palette per theme.
 *
 * This one is an editable combobox with a listbox popup (WAI-ARIA APG):
 * - type the time the way you think of it — 930, 9:30, 9.30 all mean 09:30;
 *   it is read when the field is left or Enter is pressed;
 * - or pick from the list of times every `step` minutes, opened by arrows,
 *   typing or the clock button; the list opens at the current value;
 * - something that is not a time, or lies outside minTime/maxTime, is said under the
 *   field and clears the value, so a form never keeps a time nobody sees.
 * The value is `HH:mm` or null.
 *
 * Signal Forms: <smt-control [smtLabel]="…"><smt-time-picker [formField]="form.start" [step]="15" /></smt-control>
 * Plain:        <smt-time-picker [(value)]="start" minTime="08:00" maxTime="20:00" /> */
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
  viewChild,
  ViewEncapsulation,
} from '@angular/core';
import { CdkConnectedOverlay, CdkOverlayOrigin, ConnectedPosition } from '@angular/cdk/overlay';
import type { FormValueControl, ValidationError, WithOptionalFieldTree } from '@angular/forms/signals';
import { SMTI18nService } from '../../../i18n';
import { SMT_FORM_FIELD_REGISTRY_HOST_DIRECTIVES } from '../../../forms/field-registry';
import { shouldShowSMTFormControlError } from '../../../forms/form-control-validation';
import { nearestSlot, parseTime, timeSlots, withinBounds, type SMTTime } from './time-utils';

const POPUP_POSITIONS: ConnectedPosition[] = [
  { originX: 'start', originY: 'bottom', overlayX: 'start', overlayY: 'top', offsetY: 4 },
  { originX: 'start', originY: 'top', overlayX: 'start', overlayY: 'bottom', offsetY: -4 },
];

type TimeProblem = 'invalid' | 'range';

let nextTimePickerId = 0;

@Component({
  selector: 'smt-time-picker',
  standalone: true,
  imports: [CdkConnectedOverlay, CdkOverlayOrigin],
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  templateUrl: './time-picker.component.html',
  styleUrl: './time-picker.scss',
  host: {
    class: 'smt-time-picker',
    '[class.smt-time-picker--invalid]': 'hasError()',
  },
  hostDirectives: [...SMT_FORM_FIELD_REGISTRY_HOST_DIRECTIVES],
})
export class SMTTimePickerComponent implements FormValueControl<SMTTime | null> {
  readonly i18n = inject(SMTI18nService);

  /** The inner field's id, for an outside label; not `id`, which would also land on the host element. */
  readonly customId = input<string | null>(null, { alias: 'smtFieldId' });

  readonly name = input('');

  readonly placeholder = input('');

  /** Minutes between the times the list offers. */
  readonly step = input(30, { transform: numberAttribute });

  /** Earliest allowed time, `HH:mm`. Not `min`: Signal Forms keeps that name for its numeric bound. */
  readonly minTime = input<SMTTime | null>(null);

  /** Latest allowed time, `HH:mm`. */
  readonly maxTime = input<SMTTime | null>(null);

  readonly disabled = input(false, { transform: booleanAttribute });

  readonly readonly = input(false, { transform: booleanAttribute });

  readonly required = input(false, { transform: booleanAttribute });

  readonly invalid = input(false, { transform: booleanAttribute });

  readonly errors = input<readonly WithOptionalFieldTree<ValidationError>[]>([]);

  /** Bound by Signal Forms; the field's touched state. */
  readonly touched = input(false, { transform: booleanAttribute });

  /** Tells Signal Forms the person has left the field (Angular 22 reads `touch`, not `touchedChange`). */
  readonly touch = output<void>();

  readonly value = model<SMTTime | null>(null);

  private readonly field = viewChild.required<ElementRef<HTMLInputElement>>('field');

  /** What the field shows: the typed text until it is read, else the value. */
  readonly draft = linkedSignal(() => this.value() ?? '');

  readonly open = signal(false);

  readonly activeIndex = signal(-1);

  readonly problem = signal<TimeProblem | null>(null);

  /** Touched here or by the form; follows the form again when it resets `touched`. */
  protected readonly wasTouched = linkedSignal(() => this.touched());

  /** Disabled by a reactive form or ngModel through the value accessor. */
  private readonly formsDisabled = signal(false);

  readonly isDisabled = computed(() => this.disabled() || this.formsDisabled());

  readonly editable = computed(() => !this.isDisabled() && !this.readonly());

  readonly slots = computed(() => timeSlots(this.step(), this.minTime(), this.maxTime()));

  readonly activeId = computed(() => (this.open() && this.activeIndex() >= 0 ? this.optionId(this.activeIndex()) : null));

  readonly problemText = computed(() => {
    const problem = this.problem();
    const messages = this.i18n.messages().time;
    if (problem === 'invalid') return messages.invalid;
    if (problem === 'range') return messages.range(this.minTime() ?? '00:00', this.maxTime() ?? '23:59');
    return '';
  });

  readonly hasError = computed(() =>
    shouldShowSMTFormControlError({
      invalid: this.invalid(),
      errors: this.errors(),
      touched: this.wasTouched(),
      required: this.required(),
      empty: !this.value(),
      localError: this.problem() !== null,
    })
  );

  readonly baseId = `smt-time-picker-${nextTimePickerId++}`;

  readonly listboxId = `${this.baseId}-list`;

  readonly problemId = `${this.baseId}-problem`;

  readonly positions = POPUP_POSITIONS;

  /** Whether the highlight was moved with the arrows since the last keystroke. */
  private navigated = false;

  optionId(index: number): string {
    return `${this.baseId}-option-${index}`;
  }

  onInput(event: Event): void {
    const text = (event.target as HTMLInputElement).value;
    this.draft.set(text);
    this.navigated = false;
    this.problem.set(null);
    if (!this.open()) this.open.set(true);
    const typed = parseTime(text);
    if (typed) this.setActive(nearestSlot(this.slots(), typed));
  }

  onKeydown(event: KeyboardEvent): void {
    if (!this.editable()) return;
    switch (event.key) {
      case 'ArrowDown':
      case 'ArrowUp': {
        event.preventDefault();
        if (!this.open()) {
          this.openList();
          return;
        }
        const last = this.slots().length - 1;
        const current = this.activeIndex();
        const next = current < 0 ? (event.key === 'ArrowDown' ? 0 : last) : current + (event.key === 'ArrowDown' ? 1 : -1);
        this.navigated = true;
        this.setActive(Math.min(Math.max(next, 0), last));
        return;
      }
      case 'Enter':
        // A time picked with the arrows wins; otherwise what was typed is read as typed.
        if (this.open()) event.preventDefault();
        if (this.open() && this.navigated && this.activeIndex() >= 0) {
          this.choose(this.activeIndex());
        } else {
          this.commit();
          this.open.set(false);
        }
        return;
      case 'Escape':
        if (this.open()) {
          event.preventDefault();
          this.open.set(false);
        } else if (this.draft() !== (this.value() ?? '')) {
          event.preventDefault();
          this.draft.set(this.value() ?? '');
          this.problem.set(null);
        }
        return;
    }
  }

  onBlur(): void {
    this.commit();
    this.open.set(false);
    this.markTouched();
  }

  toggleList(): void {
    if (!this.editable()) return;
    if (this.open()) this.open.set(false);
    else this.openList();
    this.field().nativeElement.focus();
  }

  choose(index: number): void {
    const slot = this.slots()[index];
    if (!slot) return;
    this.value.set(slot);
    this.draft.set(slot);
    this.problem.set(null);
    this.open.set(false);
    this.markTouched();
  }

  clear(): void {
    this.value.set(null);
    this.draft.set('');
    this.problem.set(null);
    this.markTouched();
    this.field().nativeElement.focus();
  }

  /** The person left the field. */
  markTouched(): void {
    this.wasTouched.set(true);
    this.touch.emit();
  }

  /** Called by SMTTimePickerValueAccessor. */
  setDisabledFromForms(disabled: boolean): void {
    this.formsDisabled.set(disabled);
  }

  /** Reads the typed text into the value. */
  private commit(): void {
    const text = this.draft().trim();
    if (!text) {
      this.problem.set(null);
      if (this.value() !== null) this.value.set(null);
      this.draft.set('');
      return;
    }
    const time = parseTime(text);
    if (time === null || !withinBounds(time, this.minTime(), this.maxTime())) {
      this.problem.set(time === null ? 'invalid' : 'range');
      if (this.value() !== null) this.value.set(null);
      return;
    }
    this.problem.set(null);
    if (time !== this.value()) this.value.set(time);
    this.draft.set(time);
  }

  private openList(): void {
    this.open.set(true);
    this.navigated = false;
    const current = nearestSlot(this.slots(), parseTime(this.draft()) ?? this.value());
    this.setActive(current >= 0 ? current : 0);
  }

  private setActive(index: number): void {
    this.activeIndex.set(index);
    queueMicrotask(() => {
      const element = index >= 0 ? document.getElementById(this.optionId(index)) : null;
      if (element && typeof element.scrollIntoView === 'function') element.scrollIntoView({ block: 'nearest' });
    });
  }
}
