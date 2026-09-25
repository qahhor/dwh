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
import { SMTRadioGroupComponent, SMTRadioOption } from './radio-group.component';

type Period = 'day' | 'week' | 'month' | 'year';

const PERIODS: SMTRadioOption<Period>[] = [
  { value: 'day', label: 'Day' },
  { value: 'week', label: 'Week', disabled: true },
  { value: 'month', label: 'Month', hint: 'Calendar month' },
  { value: 'year', label: 'Year' },
];

@Component({
  standalone: true,
  imports: [SMTRadioGroupComponent, SMTControlComponent, FormField],
  template: `
    <smt-control smtLabel="Period">
      <smt-radio-group [formField]="source.period" [options]="periods" />
    </smt-control>
  `,
})
class FormHost {
  readonly model = signal({ period: null as Period | null });
  readonly source = form(this.model, path => required(path.period));
  readonly periods = PERIODS;
}

@Component({
  standalone: true,
  imports: [SMTRadioGroupComponent],
  template: `<smt-radio-group [(value)]="period" [options]="periods" smtAriaLabel="Period" readonly />`,
})
class ReadonlyHost {
  period: Period | null = null;
  readonly periods = PERIODS;
}

@Component({
  standalone: true,
  imports: [SMTRadioGroupComponent],
  template: `<smt-radio-group [(value)]="size" [options]="sizes" smtAriaLabel="Size" smtOrientation="horizontal" [compareWith]="sameId" />`,
})
class ObjectHost {
  size: { id: number } | null = { id: 2 };
  readonly sizes: SMTRadioOption<{ id: number }>[] = [
    { value: { id: 1 }, label: 'Small' },
    { value: { id: 2 }, label: 'Large' },
  ];
  readonly sameId = (a: { id: number } | null, b: { id: number } | null) => a?.id === b?.id;
}

describe('SMTRadioGroupComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

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
    const group = element.querySelector('[role="radiogroup"]') as HTMLElement;
    const radios = () => Array.from(element.querySelectorAll('[role="radio"]')) as HTMLElement[];
    const key = async (target: HTMLElement, name: string) => {
      target.dispatchEvent(new KeyboardEvent('keydown', { key: name, bubbles: true, cancelable: true }));
      await settle();
    };
    return { fixture, element, group, radios, key, settle };
  }

  it('is a radio group named by the smt-control label, with one tab stop', async () => {
    const { element, group, radios } = await render(FormHost);
    expect(group.getAttribute('aria-labelledby')).toBe(element.querySelector('label')!.id);
    expect(group.getAttribute('aria-required')).toBe('true');
    expect(radios().map(radio => radio.tabIndex)).toEqual([0, -1, -1, -1]);
    expect(radios()[1].getAttribute('aria-disabled')).toBe('true');
    expect(radios()[2].getAttribute('aria-describedby')).toBe(radios()[2].querySelector('.smt-radio-group__hint')!.id);
  });

  it('chooses with a click and moves the tab stop to the choice', async () => {
    const { fixture, radios, settle } = await render(FormHost);
    radios()[2].click();
    await settle();
    expect(fixture.componentInstance.model().period).toBe('month');
    expect(radios()[2].getAttribute('aria-checked')).toBe('true');
    expect(radios().map(radio => radio.tabIndex)).toEqual([-1, -1, 0, -1]);
  });

  it('moves and chooses with the arrows, skipping a disabled item and wrapping around', async () => {
    const { fixture, radios, key } = await render(FormHost);
    radios()[0].focus();
    await key(radios()[0], 'ArrowDown');
    expect(fixture.componentInstance.model().period).toBe('month');
    expect(document.activeElement).toBe(radios()[2]);
    await key(radios()[2], 'ArrowDown');
    await key(radios()[3], 'ArrowRight');
    expect(fixture.componentInstance.model().period).toBe('day');
    await key(radios()[0], 'ArrowUp');
    expect(fixture.componentInstance.model().period).toBe('year');
    await key(radios()[3], 'Home');
    expect(fixture.componentInstance.model().period).toBe('day');
    await key(radios()[0], 'End');
    expect(fixture.componentInstance.model().period).toBe('year');
  });

  it('shows the required error once focus leaves the group', async () => {
    const { element, group, radios, settle } = await render(FormHost);
    radios()[0].focus();
    radios()[0].dispatchEvent(new FocusEvent('focusout', { bubbles: true, relatedTarget: document.body }));
    await settle();
    expect(element.querySelector('.smt-control__error')?.textContent).toContain('This field is required');
    expect(group.getAttribute('aria-invalid')).toBe('true');
  });

  it('only moves the focus when read-only', async () => {
    const { fixture, radios, key } = await render(ReadonlyHost);
    radios()[0].click();
    await key(radios()[0], 'ArrowDown');
    expect(fixture.componentInstance.period).toBeNull();
    expect(document.activeElement).toBe(radios()[2]);
  });

  it('matches object values with compareWith and lays items out in a row', async () => {
    const { fixture, element, group, radios, settle } = await render(ObjectHost);
    expect(radios()[1].getAttribute('aria-checked')).toBe('true');
    expect(group.getAttribute('aria-label')).toBe('Size');
    expect(group.getAttribute('aria-orientation')).toBe('horizontal');
    expect(element.querySelector('smt-radio-group')!.classList).toContain('smt-radio-group--horizontal');
    radios()[0].click();
    await settle();
    expect(fixture.componentInstance.size).toEqual({ id: 1 });
  });
});
