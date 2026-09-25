/* Our code, after the idea of the kit's `smt-calendar-view`
 * (smartup-ui-kit@6472beb). See ADR-0015 rule 2.
 *
 * The kit's grid was a set of clickable divs without keyboard support. This
 * one follows the WAI-ARIA APG date picker grid: a table with role="grid",
 * one day in the tab order, arrows move by day and week, Home and End to the
 * week's ends, PageUp and PageDown by month (with Shift by year), Enter or
 * Space picks. Every day is named in full ("четверг, 24 сентября 2026 г."). */
import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  Injector,
  input,
  output,
  signal,
  untracked,
  ViewEncapsulation,
} from '@angular/core';
import { SMTI18nService } from '../../../i18n';
import { fullDateLabel, monthLabel, weekdayNames } from './date-format';
import {
  addDays,
  addMonths,
  CalendarDate,
  compareDates,
  endOfWeek,
  fromJsDate,
  isSameDate,
  isWithin,
  monthGrid,
  orderedRange,
  startOfWeek,
  toIsoDate,
} from './date-utils';

interface CalendarCell {
  readonly date: CalendarDate;
  readonly iso: string;
  readonly inMonth: boolean;
  readonly label: string;
  readonly disabled: boolean;
  readonly today: boolean;
}

let nextCalendarId = 0;

@Component({
  selector: 'smt-calendar',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  templateUrl: './calendar.component.html',
  styleUrl: './date-picker.scss',
  host: { class: 'smt-calendar' },
})
export class SMTCalendarComponent {
  readonly i18n = inject(SMTI18nService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  private readonly injector = inject(Injector);

  /** The picked day (single mode). */
  readonly selected = input<CalendarDate | null>(null);

  /** Ends of a range, when the calendar shows one. `rangeTo` null while picking. */
  readonly rangeFrom = input<CalendarDate | null>(null);

  readonly rangeTo = input<CalendarDate | null>(null);

  readonly min = input<CalendarDate | null>(null);

  readonly max = input<CalendarDate | null>(null);

  readonly locale = input('ru-RU');

  /** Overrides "today", for tests. */
  readonly today = input<CalendarDate>(fromJsDate(new Date()));

  readonly picked = output<CalendarDate>();

  /** The day that holds keyboard focus; its month is the one shown. */
  readonly active = signal<CalendarDate>(fromJsDate(new Date()));

  /** Day under the pointer or keyboard focus, for the range preview. */
  private readonly previewEnd = signal<CalendarDate | null>(null);

  readonly title = computed(() => monthLabel(this.active().year, this.active().month, this.locale()));

  readonly weekdays = computed(() => {
    const short = weekdayNames(this.locale(), 'short');
    const long = weekdayNames(this.locale(), 'long');
    return short.map((label, index) => ({ label, full: long[index] }));
  });

  readonly weeks = computed<CalendarCell[][]>(() => {
    const { year, month } = this.active();
    const locale = this.locale();
    const today = this.today();
    return monthGrid(year, month).map(week =>
      week.map(date => ({
        date,
        iso: toIsoDate(date),
        inMonth: date.month === month,
        label: fullDateLabel(date, locale),
        disabled: !isWithin(date, this.min(), this.max()),
        today: isSameDate(date, today),
      }))
    );
  });

  private readonly shownRange = computed(() => {
    const from = this.rangeFrom();
    if (!from) return null;
    const to = this.rangeTo() ?? this.previewEnd();
    return to ? orderedRange(from, to) : { from, to: from };
  });

  readonly titleId = `smt-calendar-title-${nextCalendarId++}`;

  /** Shows the month of `date` (or of the value, or today) and focuses that day. */
  focusDay(date?: CalendarDate | null): void {
    const target = date ?? untracked(this.selected) ?? untracked(this.rangeFrom) ?? untracked(this.today);
    this.active.set(target);
    this.focusActiveAfterRender();
  }

  isSelected(cell: CalendarCell): boolean {
    if (isSameDate(cell.date, this.selected())) return true;
    const range = this.shownRange();
    return !!range && (isSameDate(cell.date, range.from) || isSameDate(cell.date, range.to));
  }

  isInRange(cell: CalendarCell): boolean {
    const range = this.shownRange();
    return !!range && compareDates(cell.date, range.from) > 0 && compareDates(cell.date, range.to) < 0;
  }

  isActive(cell: CalendarCell): boolean {
    return isSameDate(cell.date, this.active());
  }

  moveMonth(delta: number): void {
    this.active.update(date => addMonths(date, delta));
  }

  pick(cell: CalendarCell): void {
    if (cell.disabled) return;
    this.active.set(cell.date);
    this.picked.emit(cell.date);
  }

  preview(cell: CalendarCell | null): void {
    if (this.rangeFrom() && !this.rangeTo()) this.previewEnd.set(cell?.date ?? null);
  }

  onKeydown(event: KeyboardEvent): void {
    const current = this.active();
    let next: CalendarDate | null = null;
    switch (event.key) {
      case 'ArrowLeft':
        next = addDays(current, -1);
        break;
      case 'ArrowRight':
        next = addDays(current, 1);
        break;
      case 'ArrowUp':
        next = addDays(current, -7);
        break;
      case 'ArrowDown':
        next = addDays(current, 7);
        break;
      case 'Home':
        next = startOfWeek(current);
        break;
      case 'End':
        next = endOfWeek(current);
        break;
      case 'PageUp':
        next = addMonths(current, event.shiftKey ? -12 : -1);
        break;
      case 'PageDown':
        next = addMonths(current, event.shiftKey ? 12 : 1);
        break;
      case 'Enter':
      case ' ': {
        event.preventDefault();
        const cell = this.weeks()
          .flat()
          .find(candidate => isSameDate(candidate.date, current));
        if (cell) this.pick(cell);
        return;
      }
      default:
        return;
    }
    event.preventDefault();
    this.active.set(next);
    if (this.rangeFrom() && !this.rangeTo()) this.previewEnd.set(next);
    this.focusActiveAfterRender();
  }

  /** Focuses the active day once the view shows it (its month may have just changed). */
  private focusActiveAfterRender(): void {
    afterNextRender(
      {
        write: () => {
          const iso = toIsoDate(this.active());
          this.host.nativeElement.querySelector<HTMLElement>(`[data-date="${iso}"]`)?.focus();
        },
      },
      { injector: this.injector }
    );
  }
}
