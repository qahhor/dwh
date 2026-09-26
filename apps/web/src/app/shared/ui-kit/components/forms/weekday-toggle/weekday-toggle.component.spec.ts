// @vitest-environment jsdom
import '@angular/compiler';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { afterEach, describe, expect, it } from 'vitest';
import { SMTI18nService } from '../../../i18n';
import { testI18n } from '../../../i18n/test-messages';
import { tickInZone } from '../../../testing/zone-tick';
import { SMTWeekday, SMTWeekdayToggleComponent } from './weekday-toggle.component';
import { SMTWeekdayToggleValueAccessor } from './weekday-toggle-value-accessor';

@Component({
  standalone: true,
  imports: [SMTWeekdayToggleComponent, SMTWeekdayToggleValueAccessor, FormsModule],
  template: `<smt-weekday-toggle smtAriaLabel="Delivery days" [(ngModel)]="days" />`,
})
class Host {
  days: SMTWeekday[] = [1, 5];
}

describe('SMTWeekdayToggleComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  async function render() {
    TestBed.configureTestingModule({ providers: [{ provide: SMTI18nService, useValue: testI18n() }] });
    const fixture = TestBed.createComponent(Host);
    const settle = async () => {
      tickInZone();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    await settle();
    await settle();
    const element = fixture.nativeElement as HTMLElement;
    const days = Array.from(element.querySelectorAll('button')) as HTMLButtonElement[];
    return { fixture, element, days, settle };
  }

  it('is a named group of seven toggle buttons, Monday first, read by their full names', async () => {
    const { element, days } = await render();
    const group = element.querySelector('smt-weekday-toggle')!;
    expect(group.getAttribute('role')).toBe('group');
    expect(group.getAttribute('aria-label')).toBe('Delivery days');
    expect(days).toHaveLength(7);
    expect(days[0].getAttribute('aria-label')!.toLowerCase()).toContain('monday');
    expect(days[6].getAttribute('aria-label')!.toLowerCase()).toContain('sunday');
    expect(days.map(day => day.getAttribute('aria-pressed'))).toEqual(['true', 'false', 'false', 'false', 'true', 'false', 'false']);
  });

  it('toggles days and gives ngModel the chosen ISO days in week order', async () => {
    const { fixture, days, settle } = await render();
    days[6].click();
    await settle();
    days[2].click();
    await settle();
    expect(fixture.componentInstance.days).toEqual([1, 3, 5, 7]);
    days[0].click();
    await settle();
    expect(fixture.componentInstance.days).toEqual([3, 5, 7]);
    expect(days[0].getAttribute('aria-pressed')).toBe('false');
  });
});
