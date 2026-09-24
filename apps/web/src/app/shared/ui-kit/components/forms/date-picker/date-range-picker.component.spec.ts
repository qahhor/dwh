// @vitest-environment jsdom
import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { tickInZone } from '../../../testing/zone-tick';
import { FormsModule } from '@angular/forms';
import { afterEach, describe, expect, it } from 'vitest';
import { SMTI18nService } from '../../../i18n';
import { testI18n } from '../../../i18n/test-messages';
import { SMTDateRangePickerComponent } from './date-range-picker.component';
import { SMTDateRangePickerValueAccessor } from './date-picker-value-accessor';
import { DateRange, parseIsoDate } from './date-utils';

const TODAY = parseIsoDate('2026-09-24')!;

@Component({
  standalone: true,
  imports: [SMTDateRangePickerComponent],
  template: `<smt-date-range-picker [(value)]="period" [smtToday]="today" smtAriaLabel="Period" />`,
})
class Host {
  readonly period = signal<DateRange | null>(null);
  readonly today = TODAY;
}

@Component({
  standalone: true,
  imports: [SMTDateRangePickerComponent, SMTDateRangePickerValueAccessor, FormsModule],
  template: `<smt-date-range-picker [(ngModel)]="period" [smtToday]="today" name="period" />`,
})
class NgModelHost {
  period: DateRange | null = { from: '2026-09-01', to: '2026-09-15' };
  readonly today = TODAY;
}

describe('SMTDateRangePickerComponent', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach(node => node.remove());
    TestBed.resetTestingModule();
  });

  async function render<T>(host: new () => T) {
    TestBed.configureTestingModule({ providers: [{ provide: SMTI18nService, useValue: testI18n() }] });
    const fixture = TestBed.createComponent(host);
    document.body.appendChild(fixture.nativeElement);
    const settle = async () => {
      tickInZone();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    await settle();
    const element = fixture.nativeElement as HTMLElement;
    const trigger = element.querySelector('.smt-date-range-picker__trigger') as HTMLButtonElement;
    const open = async () => {
      trigger.click();
      await settle();
      return document.querySelector('[role="dialog"]') as HTMLElement;
    };
    const button = (dialog: HTMLElement, label: string) =>
      Array.from(dialog.querySelectorAll('button')).find(b => b.textContent?.trim() === label) as HTMLButtonElement;
    return { fixture, element, trigger, open, button, settle };
  }

  it('names the trigger with its label and the current period', async () => {
    const { trigger, fixture, settle } = await render(Host);

    expect(trigger.getAttribute('aria-label')).toBe('Period: Any period');
    expect(trigger.getAttribute('aria-haspopup')).toBe('dialog');

    fixture.componentInstance.period.set({ from: '2026-09-01', to: '2026-09-15' });
    await settle();
    expect(trigger.textContent).toContain('01.09.2026 – 15.09.2026');
  });

  it('applies a preset at once, marks it pressed next time and returns focus to the trigger', async () => {
    const { fixture, open, button, trigger, settle } = await render(Host);

    let dialog = await open();
    expect(dialog.getAttribute('aria-label')).toBe('Choose a period');
    button(dialog, 'Last 7 days').click();
    await settle();

    expect(fixture.componentInstance.period()).toEqual({ from: '2026-09-18', to: '2026-09-24' });
    expect(document.querySelector('[role="dialog"]')).toBeNull();
    expect(document.activeElement).toBe(trigger);

    dialog = await open();
    expect(button(dialog, 'Last 7 days').getAttribute('aria-pressed')).toBe('true');
    expect(button(dialog, 'Today').getAttribute('aria-pressed')).toBe('false');
  });

  it('changes nothing until Apply, then applies the picked range in order', async () => {
    const { fixture, open, button, settle } = await render(Host);

    const dialog = await open();
    expect(button(dialog, 'Apply').disabled).toBe(true);
    (dialog.querySelector('[data-date="2026-09-20"]') as HTMLElement).click();
    await settle();
    (dialog.querySelector('[data-date="2026-09-10"]') as HTMLElement).click();
    await settle();
    expect(fixture.componentInstance.period()).toBeNull();

    button(dialog, 'Apply').click();
    await settle();
    expect(fixture.componentInstance.period()).toEqual({ from: '2026-09-10', to: '2026-09-20' });
  });

  it('drops the draft on Escape and on Cancel', async () => {
    const { fixture, open, button, settle } = await render(Host);
    fixture.componentInstance.period.set({ from: '2026-09-01', to: '2026-09-02' });
    await settle();

    let dialog = await open();
    (dialog.querySelector('[data-date="2026-09-20"]') as HTMLElement).click();
    await settle();
    dialog.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    await settle();
    expect(document.querySelector('[role="dialog"]')).toBeNull();
    expect(fixture.componentInstance.period()).toEqual({ from: '2026-09-01', to: '2026-09-02' });

    dialog = await open();
    (dialog.querySelector('[data-date="2026-09-21"]') as HTMLElement).click();
    await settle();
    button(dialog, 'Cancel').click();
    await settle();
    expect(fixture.componentInstance.period()).toEqual({ from: '2026-09-01', to: '2026-09-02' });
  });

  it('clears the period with a labelled button', async () => {
    const { fixture, element, settle } = await render(Host);
    fixture.componentInstance.period.set({ from: '2026-09-01', to: '2026-09-02' });
    await settle();

    const clear = element.querySelector('[aria-label="Clear the period"]') as HTMLButtonElement;
    clear.click();
    await settle();

    expect(fixture.componentInstance.period()).toBeNull();
    expect(element.querySelector('[aria-label="Clear the period"]')).toBeNull();
  });

  it('works with ngModel through the value accessor', async () => {
    const { fixture, trigger, open, button, settle } = await render(NgModelHost);

    expect(trigger.textContent).toContain('01.09.2026 – 15.09.2026');
    const dialog = await open();
    button(dialog, 'Yesterday').click();
    await settle();

    expect(fixture.componentInstance.period).toEqual({ from: '2026-09-23', to: '2026-09-23' });
    expect(trigger.textContent).toContain('23.09.2026');
  });
});
