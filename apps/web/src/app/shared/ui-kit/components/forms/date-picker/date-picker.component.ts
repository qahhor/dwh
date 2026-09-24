/* Our code, after the idea of the kit's `smt-date-picker`
 * (smartup-ui-kit@6472beb, components/forms/date-picker). See ADR-0015
 * rule 2: the kit's copy leaned on dayjs and Biruni's string formats and
 * had no keyboard grid, so this is written afresh.
 *
 * A text field in the language's date format, a button that opens a
 * calendar dialog (WAI-ARIA APG date picker dialog), and with `smtWithTime`
 * a native time field. The value is ISO: `YYYY-MM-DD`, or
 * `YYYY-MM-DDTHH:mm` with time.
 *
 * Signal Forms bind it directly (it is a FormValueControl):
 *   <smt-date-picker [formField]="filters.from" />
 * Legacy `ngModel` screens go through SMTDatePickerValueAccessor. */
import {
  afterRenderEffect,
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  input,
  model,
  output,
  signal,
  viewChild,
  ViewEncapsulation,
} from '@angular/core';
import { CdkConnectedOverlay, CdkOverlayOrigin, ConnectedPosition } from '@angular/cdk/overlay';
import { CdkTrapFocus } from '@angular/cdk/a11y';
import type { FormValueControl } from '@angular/forms/signals';
import { SMTI18nService } from '../../../i18n';
import { SMTCalendarComponent } from './calendar.component';
import { datePattern, formatDate, parseDate } from './date-format';
import { CalendarDate, isWithin, parseIsoDate, parseIsoTime, toIsoDate } from './date-utils';

export const DATE_POPUP_POSITIONS: ConnectedPosition[] = [
  { originX: 'start', originY: 'bottom', overlayX: 'start', overlayY: 'top', offsetY: 4 },
  { originX: 'start', originY: 'top', overlayX: 'start', overlayY: 'bottom', offsetY: -4 },
  { originX: 'end', originY: 'bottom', overlayX: 'end', overlayY: 'top', offsetY: 4 },
];

let nextPickerId = 0;

@Component({
  selector: 'smt-date-picker',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  imports: [CdkConnectedOverlay, CdkOverlayOrigin, CdkTrapFocus, SMTCalendarComponent],
  templateUrl: './date-picker.component.html',
  styleUrl: './date-picker.scss',
  host: {
    class: 'smt-date-picker',
    '[class.smt-date-picker--disabled]': 'isDisabled()',
    '(focusout)': 'onFocusOut($event)',
  },
})
export class SMTDatePickerComponent implements FormValueControl<string | null> {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly i18n = inject(SMTI18nService);

  readonly value = model<string | null>(null);

  readonly disabled = input(false, { transform: booleanAttribute });

  readonly readonly = input(false, { transform: booleanAttribute });

  readonly required = input(false, { transform: booleanAttribute });

  readonly invalid = input(false, { transform: booleanAttribute });

  readonly touched = input(false, { transform: booleanAttribute });

  /**
   * Earliest and latest pickable day, ISO. Not `min`/`max`: with
   * `[formField]` those belong to the form schema and cannot be set here.
   */
  readonly minBound = input<string | undefined>(undefined, { alias: 'smtMin' });

  readonly maxBound = input<string | undefined>(undefined, { alias: 'smtMax' });

  readonly withTime = input(false, { alias: 'smtWithTime', transform: booleanAttribute });

  /** Id for the text field, so an outside label can point at it. */
  readonly inputId = input<string | undefined>(undefined, { alias: 'smtInputId' });

  /** Emitted when focus leaves the picker, so forms mark it touched. */
  readonly touch = output<void>();

  readonly id = nextPickerId++;

  readonly dialogId = `smt-date-dialog-${this.id}`;

  readonly hintId = `smt-date-hint-${this.id}`;

  readonly open = signal(false);

  /** What the person is typing, until it is committed. */
  private readonly draft = signal<string | null>(null);

  /** Set when the committed text is not a date; the value is left as it was. */
  readonly textInvalid = signal(false);

  /** Set by the legacy value accessor; `disabled` stays an input for Signal Forms. */
  private readonly disabledByForms = signal(false);

  private readonly calendar = viewChild(SMTCalendarComponent);

  private readonly textField = viewChild<ElementRef<HTMLInputElement>>('textField');

  private readonly toggleButton = viewChild<ElementRef<HTMLButtonElement>>('toggle');

  readonly positions = DATE_POPUP_POSITIONS;

  readonly isDisabled = computed(() => this.disabled() || this.disabledByForms());

  readonly format = computed(() => datePattern(this.i18n.language()));

  readonly selectedDate = computed(() => parseIsoDate(this.value()));

  readonly minDate = computed(() => parseIsoDate(this.minBound()));

  readonly maxDate = computed(() => parseIsoDate(this.maxBound()));

  readonly time = computed(() => parseIsoTime(this.value()) ?? '');

  readonly text = computed(() => this.draft() ?? formatDate(this.selectedDate(), this.format()));

  /** Form errors show once the field is touched, as in smt-control; unreadable text shows at once. */
  readonly showInvalid = computed(() => this.textInvalid() || (this.invalid() && this.touched()));

  readonly fieldId = computed(() => this.inputId() ?? `smt-date-input-${this.id}`);

  constructor() {
    // Only our own tokens are touched: an smt-control around the picker also
    // writes aria-describedby and aria-invalid on the same text field.
    let ownInvalid = false;
    afterRenderEffect({
      write: () => {
        const field = this.textField()?.nativeElement;
        const invalid = this.showInvalid();
        const describe = this.textInvalid();
        if (!field) return;
        const tokens = (field.getAttribute('aria-describedby') ?? '').split(/\s+/).filter(t => t && t !== this.hintId);
        if (describe) tokens.push(this.hintId);
        if (tokens.length) field.setAttribute('aria-describedby', tokens.join(' '));
        else field.removeAttribute('aria-describedby');
        if (invalid) {
          field.setAttribute('aria-invalid', 'true');
          ownInvalid = true;
        } else if (ownInvalid) {
          field.removeAttribute('aria-invalid');
          ownInvalid = false;
        }
      },
    });
  }

  setDisabledFromForms(disabled: boolean): void {
    this.disabledByForms.set(disabled);
  }

  onInput(event: Event): void {
    this.draft.set((event.target as HTMLInputElement).value);
  }

  /** Reads the typed text on Enter or when the field loses focus. */
  commitText(): void {
    const draft = this.draft();
    if (draft === null) return;
    const text = draft.trim();
    if (!text) {
      this.draft.set(null);
      this.textInvalid.set(false);
      this.setValue(null);
      return;
    }
    const date = parseDate(text, this.format());
    if (!date || !isWithin(date, this.minDate(), this.maxDate())) {
      this.textInvalid.set(true);
      return;
    }
    this.draft.set(null);
    this.textInvalid.set(false);
    this.setValue(date);
  }

  onTextKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter') {
      this.commitText();
    } else if (event.key === 'ArrowDown' && event.altKey) {
      event.preventDefault();
      this.openCalendar();
    }
  }

  onTimeChange(event: Event): void {
    const date = this.selectedDate();
    const time = (event.target as HTMLInputElement).value;
    if (!date) return;
    this.value.set(time ? `${toIsoDate(date)}T${time}` : toIsoDate(date));
  }

  toggleCalendar(): void {
    if (this.open()) this.closeCalendar(true);
    else this.openCalendar();
  }

  openCalendar(): void {
    if (this.isDisabled() || this.readonly()) return;
    this.commitText();
    this.open.set(true);
  }

  /** Called once the dialog is in the page: focus the picked day, or today. */
  onAttached(): void {
    this.calendar()?.focusDay(this.selectedDate());
  }

  closeCalendar(restoreFocus: boolean): void {
    if (!this.open()) return;
    this.open.set(false);
    if (restoreFocus) this.toggleButton()?.nativeElement.focus();
  }

  onPicked(date: CalendarDate): void {
    this.textInvalid.set(false);
    this.draft.set(null);
    this.setValue(date);
    this.open.set(false);
    this.textField()?.nativeElement.focus();
  }

  onDialogKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      this.closeCalendar(true);
    }
  }

  onFocusOut(event: FocusEvent): void {
    const next = event.relatedTarget as Node | null;
    if (next && this.host.nativeElement.contains(next)) return;
    // The dialog lives in the overlay container, outside the host.
    if (next instanceof Element && next.closest(`#${this.dialogId}`)) return;
    this.commitText();
    if (!this.open()) this.touch.emit();
  }

  private setValue(date: CalendarDate | null): void {
    if (!date) {
      this.value.set(null);
      return;
    }
    const iso = toIsoDate(date);
    if (!this.withTime()) {
      this.value.set(iso);
      return;
    }
    const time = parseIsoTime(this.value()) ?? '00:00';
    this.value.set(`${iso}T${time}`);
  }
}
