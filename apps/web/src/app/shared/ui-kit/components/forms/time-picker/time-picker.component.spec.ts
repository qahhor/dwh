// @vitest-environment jsdom
import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormField, form, required } from '@angular/forms/signals';
import { afterEach, describe, expect, it } from 'vitest';
import { SMTI18nService } from '../../../i18n';
import { testI18n } from '../../../i18n/test-messages';
import { tickInZone } from '../../../testing/zone-tick';
import { SMTControlComponent } from '../control/control.component';
import { SMTTimePickerComponent } from './time-picker.component';
import { formatTime, nearestSlot, parseTime, timeSlots, withinBounds } from './time-utils';

@Component({
  standalone: true,
  imports: [SMTTimePickerComponent, SMTControlComponent, FormField],
  template: `
    <smt-control smtLabel="Starts at">
      <smt-time-picker [formField]="shift.start" [step]="60" minTime="08:00" maxTime="12:00" />
    </smt-control>
  `,
})
class FormHost {
  readonly model = signal({ start: '09:00' as string | null });
  readonly shift = form(this.model, path => required(path.start));
}

describe('time utils', () => {
  it.each([
    ['9', '09:00'], ['09', '09:00'], ['930', '09:30'], ['0930', '09:30'], ['9:30', '09:30'],
    ['9.30', '09:30'], ['9-30', '09:30'], ['9 30', '09:30'], ['21:5', '21:05'], ['9::30', '09:30'], [' 23:59 ', '23:59'], ['0', '00:00'],
  ])('reads %j as %s', (text, time) => {
    expect(parseTime(text)).toBe(time);
  });

  it.each(['', '24:00', '9:60', '12345', 'noon', '9:30:15', '-1'])('refuses %j', text => {
    expect(parseTime(text)).toBeNull();
  });

  it('lists the times every step minutes within the bounds, starting on a step', () => {
    expect(timeSlots(60, '08:30', '11:00')).toEqual(['09:00', '10:00', '11:00']);
    expect(timeSlots(0, null, '01:00')).toEqual(['00:00', '00:30', '01:00']);
    expect(timeSlots(15, null, null)).toHaveLength(96);
  });

  it('knows the bounds and the slot to open at', () => {
    expect(withinBounds('08:00', '08:00', '12:00')).toBe(true);
    expect(withinBounds('12:01', '08:00', '12:00')).toBe(false);
    const slots = ['08:00', '09:00', '10:00'];
    expect(nearestSlot(slots, '08:20')).toBe(1);
    expect(nearestSlot(slots, '23:00')).toBe(2);
    expect(nearestSlot(slots, null)).toBe(-1);
    expect(formatTime(-30)).toBe('23:30');
  });
});

describe('SMTTimePickerComponent', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach(node => node.remove());
    TestBed.resetTestingModule();
  });

  async function render() {
    TestBed.configureTestingModule({ providers: [{ provide: SMTI18nService, useValue: testI18n() }] });
    const fixture = TestBed.createComponent(FormHost);
    document.body.appendChild(fixture.nativeElement);
    const settle = async () => {
      tickInZone();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    await settle();
    const element = fixture.nativeElement as HTMLElement;
    const input = element.querySelector('input') as HTMLInputElement;
    const options = () => Array.from(document.querySelectorAll('[role="option"]')) as HTMLElement[];
    const key = async (name: string) => {
      input.dispatchEvent(new KeyboardEvent('keydown', { key: name, bubbles: true, cancelable: true }));
      await settle();
    };
    const type = async (text: string) => {
      input.value = text;
      input.dispatchEvent(new Event('input'));
      await settle();
    };
    const blur = async () => {
      input.dispatchEvent(new Event('blur'));
      await settle();
    };
    return { fixture, element, input, options, key, type, blur, settle };
  }

  it('is a combobox named by the smt-control label and shows the value', async () => {
    const { element, input } = await render();
    expect(input.getAttribute('role')).toBe('combobox');
    expect((element.querySelector('label') as HTMLLabelElement).htmlFor).toBe(input.id);
    expect(input.value).toBe('09:00');
    expect(input.getAttribute('aria-expanded')).toBe('false');
    expect(input.placeholder).toBe('hh:mm');
  });

  it('reads a time typed without a mask when the field is left', async () => {
    const { fixture, input, type, blur } = await render();
    await type('1030');
    expect(fixture.componentInstance.model().start).toBe('09:00');
    await blur();
    expect(fixture.componentInstance.model().start).toBe('10:30');
    expect(input.value).toBe('10:30');
  });

  it('opens the list at the value with the arrows and picks with Enter', async () => {
    const { fixture, input, options, key } = await render();
    await key('ArrowDown');
    expect(input.getAttribute('aria-expanded')).toBe('true');
    expect(options().map(option => option.textContent!.trim())).toEqual(['08:00', '09:00', '10:00', '11:00', '12:00']);
    expect(input.getAttribute('aria-activedescendant')).toBe(options()[1].id);
    expect(options()[1].getAttribute('aria-selected')).toBe('true');
    await key('ArrowDown');
    await key('Enter');
    expect(fixture.componentInstance.model().start).toBe('10:00');
    expect(input.getAttribute('aria-expanded')).toBe('false');
  });

  it('keeps a typed time on Enter even when the list highlights a neighbour', async () => {
    const { fixture, key, type } = await render();
    await type('9:45');
    await key('Enter');
    expect(fixture.componentInstance.model().start).toBe('09:45');
  });

  it('picks a time with a click on the list', async () => {
    const { fixture, element, options, settle } = await render();
    (element.querySelector('.smt-time-picker__toggle') as HTMLButtonElement).click();
    await settle();
    options()[4].click();
    await settle();
    expect(fixture.componentInstance.model().start).toBe('12:00');
  });

  it('says so under the field and clears the value for text that is not a time or lies outside the bounds', async () => {
    const { fixture, element, input, type, blur, key } = await render();
    await type('half past');
    await blur();
    expect(fixture.componentInstance.model().start).toBeNull();
    const problem = element.querySelector('.smt-time-picker__problem') as HTMLElement;
    expect(problem.textContent).toContain('Enter a time as hh:mm');
    expect(input.getAttribute('aria-describedby')).toContain(problem.id);
    expect(input.getAttribute('aria-invalid')).toBe('true');
    await type('18:00');
    await key('Enter');
    expect(element.querySelector('.smt-time-picker__problem')?.textContent).toContain('Enter a time from 08:00 to 12:00');
    await type('11');
    await key('Enter');
    expect(fixture.componentInstance.model().start).toBe('11:00');
    expect(element.querySelector('.smt-time-picker__problem')).toBeNull();
  });

  it('closes on Escape, then puts back the value on a second Escape', async () => {
    const { fixture, input, type, key } = await render();
    await type('7');
    expect(input.getAttribute('aria-expanded')).toBe('true');
    await key('Escape');
    expect(input.getAttribute('aria-expanded')).toBe('false');
    await key('Escape');
    expect(input.value).toBe('09:00');
    expect(fixture.componentInstance.model().start).toBe('09:00');
  });

  it('clears with the clear button and then shows the required error', async () => {
    const { fixture, element, settle } = await render();
    (element.querySelector('.smt-time-picker__clear') as HTMLButtonElement).click();
    await settle();
    expect(fixture.componentInstance.model().start).toBeNull();
    expect(element.querySelector('.smt-control__error')?.textContent).toContain('This field is required');
  });
});
