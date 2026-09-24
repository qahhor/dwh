/* Our code, after the idea of the kit's `smt-date-range-picker`
 * (smartup-ui-kit@6472beb, components/forms/date-range-picker). See
 * ADR-0015 rule 2.
 *
 * A button that shows the chosen period and opens a dialog with built-in
 * presets (today, last 7 days, this month…) and a range calendar. Picking
 * works on a draft: the value changes only on Apply or a preset, so a
 * filter does not refetch on every click; Escape and Cancel drop the draft.
 * The kit had presets only when the caller supplied them.
 *
 * The value is `{ from, to }` of ISO dates, or null for "any period". */
import {
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
import { CdkConnectedOverlay, CdkOverlayOrigin } from '@angular/cdk/overlay';
import { CdkTrapFocus } from '@angular/cdk/a11y';
import type { FormValueControl } from '@angular/forms/signals';
import { SMTI18nService } from '../../../i18n';
import { SMTCalendarComponent } from './calendar.component';
import { datePattern, formatDate } from './date-format';
import { DATE_POPUP_POSITIONS } from './date-picker.component';
import {
  CalendarDate,
  DATE_RANGE_PRESETS,
  DateRange,
  DateRangePresetKey,
  fromJsDate,
  isSameDate,
  orderedRange,
  parseIsoDate,
  presetRange,
  toIsoDate,
} from './date-utils';

let nextRangeId = 0;

@Component({
  selector: 'smt-date-range-picker',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  imports: [CdkConnectedOverlay, CdkOverlayOrigin, CdkTrapFocus, SMTCalendarComponent],
  templateUrl: './date-range-picker.component.html',
  styleUrl: './date-picker.scss',
  host: {
    class: 'smt-date-range-picker',
    '(focusout)': 'onFocusOut($event)',
  },
})
export class SMTDateRangePickerComponent implements FormValueControl<DateRange | null> {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly i18n = inject(SMTI18nService);

  readonly value = model<DateRange | null>(null);

  readonly disabled = input(false, { transform: booleanAttribute });

  readonly readonly = input(false, { transform: booleanAttribute });

  /** Earliest and latest pickable day, ISO. Named so they do not clash with the form's own min/max. */
  readonly minDate = input<string | undefined>(undefined, { alias: 'smtMin' });

  readonly maxDate = input<string | undefined>(undefined, { alias: 'smtMax' });

  /** Accessible name of the trigger when no outside label names it. */
  readonly ariaLabel = input<string | undefined>(undefined, { alias: 'smtAriaLabel' });

  /** Overrides "today", for tests. */
  readonly today = input<CalendarDate>(fromJsDate(new Date()), { alias: 'smtToday' });

  readonly touch = output<void>();

  readonly id = nextRangeId++;

  readonly dialogId = `smt-date-range-dialog-${this.id}`;

  readonly presetsLabelId = `smt-date-range-presets-${this.id}`;

  readonly presets = DATE_RANGE_PRESETS;

  readonly positions = DATE_POPUP_POSITIONS;

  readonly open = signal(false);

  readonly draftFrom = signal<CalendarDate | null>(null);

  readonly draftTo = signal<CalendarDate | null>(null);

  private readonly disabledByForms = signal(false);

  private readonly calendar = viewChild(SMTCalendarComponent);

  private readonly trigger = viewChild<ElementRef<HTMLButtonElement>>('trigger');

  readonly isDisabled = computed(() => this.disabled() || this.disabledByForms());

  readonly from = computed(() => parseIsoDate(this.value()?.from));

  readonly to = computed(() => parseIsoDate(this.value()?.to));

  readonly minDay = computed(() => parseIsoDate(this.minDate()));

  readonly maxDay = computed(() => parseIsoDate(this.maxDate()));

  readonly summary = computed(() => {
    const pattern = datePattern(this.i18n.language());
    const from = this.from();
    const to = this.to();
    if (!from && !to) return this.i18n.messages().date.anyPeriod;
    const left = formatDate(from, pattern);
    const right = formatDate(to, pattern);
    return from && to && isSameDate(from, to) ? left : `${left} – ${right}`;
  });

  readonly hasValue = computed(() => !!this.from() || !!this.to());

  /** The preset the current value matches, if any. */
  readonly activePreset = computed<DateRangePresetKey | null>(() => {
    const from = this.from();
    const to = this.to();
    if (!from || !to) return null;
    return (
      this.presets.find(key => {
        const range = presetRange(key, this.today());
        return isSameDate(range.from, from) && isSameDate(range.to, to);
      }) ?? null
    );
  });

  setDisabledFromForms(disabled: boolean): void {
    this.disabledByForms.set(disabled);
  }

  toggle(): void {
    if (this.open()) this.cancel();
    else this.openDialog();
  }

  openDialog(): void {
    if (this.isDisabled() || this.readonly()) return;
    this.draftFrom.set(this.from());
    this.draftTo.set(this.to());
    this.open.set(true);
  }

  onAttached(): void {
    this.calendar()?.focusDay(this.from() ?? this.today());
  }

  /** First pick starts a new range, the second closes it (in either order). */
  onPicked(date: CalendarDate): void {
    const from = this.draftFrom();
    if (!from || this.draftTo()) {
      this.draftFrom.set(date);
      this.draftTo.set(null);
      return;
    }
    const range = orderedRange(from, date);
    this.draftFrom.set(range.from);
    this.draftTo.set(range.to);
  }

  applyPreset(key: DateRangePresetKey): void {
    const range = presetRange(key, this.today());
    this.commit(range.from, range.to);
  }

  apply(): void {
    const from = this.draftFrom();
    if (!from) return;
    this.commit(from, this.draftTo() ?? from);
  }

  cancel(): void {
    this.close(true);
  }

  clear(): void {
    this.value.set(null);
    this.trigger()?.nativeElement.focus();
  }

  onDialogKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      this.cancel();
    }
  }

  onFocusOut(event: FocusEvent): void {
    const next = event.relatedTarget as Node | null;
    if (next && this.host.nativeElement.contains(next)) return;
    if (next instanceof Element && next.closest(`#${this.dialogId}`)) return;
    if (!this.open()) this.touch.emit();
  }

  close(restoreFocus: boolean): void {
    if (!this.open()) return;
    this.open.set(false);
    if (restoreFocus) this.trigger()?.nativeElement.focus();
  }

  private commit(from: CalendarDate, to: CalendarDate): void {
    this.value.set({ from: toIsoDate(from), to: toIsoDate(to) });
    this.close(true);
  }
}
