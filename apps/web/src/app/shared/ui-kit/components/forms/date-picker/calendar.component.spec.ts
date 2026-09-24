// @vitest-environment jsdom
import '@angular/compiler';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';
import { SMTI18nService } from '../../../i18n';
import { testI18n } from '../../../i18n/test-messages';
import { SMTCalendarComponent } from './calendar.component';
import { CalendarDate, parseIsoDate } from './date-utils';

const d = (iso: string): CalendarDate => parseIsoDate(iso)!;

describe('SMTCalendarComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  function render(inputs: Record<string, unknown> = {}) {
    TestBed.configureTestingModule({ providers: [{ provide: SMTI18nService, useValue: testI18n() }] });
    const fixture = TestBed.createComponent(SMTCalendarComponent);
    fixture.componentRef.setInput('locale', 'en-GB');
    fixture.componentRef.setInput('today', d('2026-09-24'));
    for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
    document.body.appendChild(fixture.nativeElement);
    const picked: CalendarDate[] = [];
    fixture.componentInstance.picked.subscribe(date => picked.push(date));
    fixture.componentInstance.focusDay();
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    const grid = element.querySelector('[role="grid"]') as HTMLTableElement;
    const key = (name: string, shiftKey = false) => {
      grid.dispatchEvent(new KeyboardEvent('keydown', { key: name, shiftKey, bubbles: true, cancelable: true }));
      TestBed.tick();
    };
    const focused = () => (document.activeElement as HTMLElement | null)?.getAttribute('data-date');
    return { fixture, element, grid, key, focused, picked };
  }

  it('is a grid named by its month heading, with weekday headers and full day names', () => {
    const { element, grid } = render();

    const title = element.querySelector('.smt-calendar__title')!;
    expect(grid.getAttribute('aria-labelledby')).toBe(title.id);
    expect(title.textContent?.trim()).toBe('September 2026');
    expect(Array.from(grid.querySelectorAll('th')).map(th => th.getAttribute('abbr'))[0]).toBe('Monday');
    const today = grid.querySelector('[data-date="2026-09-24"]')!;
    expect(today.getAttribute('aria-label')).toBe('Thursday, 24 September 2026');
    expect(today.getAttribute('aria-current')).toBe('date');
  });

  it('keeps exactly one day in the tab order and focuses it', () => {
    const { grid, focused } = render();

    const tabbable = grid.querySelectorAll('[tabindex="0"]');
    expect(tabbable).toHaveLength(1);
    expect(tabbable[0].getAttribute('data-date')).toBe('2026-09-24');
    expect(focused()).toBe('2026-09-24');
  });

  it('moves by day, week, week end and month from the keyboard, crossing months', () => {
    const { key, focused, element } = render();

    key('ArrowRight');
    expect(focused()).toBe('2026-09-25');
    key('ArrowDown');
    expect(focused()).toBe('2026-10-02');
    expect(element.querySelector('.smt-calendar__title')?.textContent?.trim()).toBe('October 2026');
    key('Home');
    expect(focused()).toBe('2026-09-28');
    key('End');
    expect(focused()).toBe('2026-10-04');
    key('PageUp');
    expect(focused()).toBe('2026-09-04');
    key('PageDown', true);
    expect(focused()).toBe('2027-09-04');
    key('ArrowUp');
    expect(focused()).toBe('2027-08-28');
  });

  it('picks the focused day with Enter and ignores days outside min and max', () => {
    const { key, picked, grid, fixture } = render({ min: d('2026-09-10'), max: d('2026-09-25') });

    key('Enter');
    expect(picked).toEqual([d('2026-09-24')]);

    key('ArrowRight');
    key('ArrowRight');
    const late = grid.querySelector('[data-date="2026-09-26"]')!;
    expect(late.getAttribute('aria-disabled')).toBe('true');
    key(' ');
    expect(picked).toHaveLength(1);

    (grid.querySelector('[data-date="2026-09-09"]') as HTMLElement).click();
    fixture.detectChanges();
    expect(picked).toHaveLength(1);
  });

  it('marks the ends of a range as selected and the days between as in range', () => {
    const { grid } = render({ rangeFrom: d('2026-09-10'), rangeTo: d('2026-09-13') });

    const cell = (iso: string) => grid.querySelector(`[data-date="${iso}"]`)!;
    expect(cell('2026-09-10').getAttribute('aria-selected')).toBe('true');
    expect(cell('2026-09-13').getAttribute('aria-selected')).toBe('true');
    expect(cell('2026-09-11').classList).toContain('smt-calendar__day--in-range');
    expect(cell('2026-09-11').getAttribute('aria-selected')).toBe('false');
    expect(cell('2026-09-14').classList).not.toContain('smt-calendar__day--in-range');
  });

  it('previews an open range up to the focused day', () => {
    const { grid, key } = render({ rangeFrom: d('2026-09-24') });

    key('ArrowRight');
    key('ArrowRight');

    expect(grid.querySelector('[data-date="2026-09-25"]')!.classList).toContain('smt-calendar__day--in-range');
  });
});
