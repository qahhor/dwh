// @vitest-environment jsdom
import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormField, form } from '@angular/forms/signals';
import { afterEach, describe, expect, it } from 'vitest';
import { SMTI18nService } from '../../../i18n';
import { testI18n } from '../../../i18n/test-messages';
import { tickInZone } from '../../../testing/zone-tick';
import { SMTControlComponent } from '../control/control.component';
import { SMTSwitchComponent } from './switch.component';

@Component({
  standalone: true,
  imports: [SMTSwitchComponent, FormField],
  template: `<smt-switch [formField]="settings.enabled" smtLabel="Send reminders" (smtUserChange)="flips.push($event)" />`,
})
class FormHost {
  readonly model = signal({ enabled: false });
  readonly settings = form(this.model);
  readonly flips: boolean[] = [];
}

@Component({
  standalone: true,
  imports: [SMTSwitchComponent, SMTControlComponent],
  template: `
    <smt-control smtLabel="Active">
      <smt-switch [(checked)]="on" [disabled]="off()" [readonly]="locked()" />
    </smt-control>
    <smt-switch smtAriaLabel="Row 4 active" [checked]="true" />
  `,
})
class PlainHost {
  on = true;
  readonly off = signal(false);
  readonly locked = signal(false);
}

describe('SMTSwitchComponent', () => {
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
    const switches = () => Array.from(element.querySelectorAll('[role="switch"]')) as HTMLButtonElement[];
    return { fixture, element, switches, settle };
  }

  it('is an on/off switch named by the label beside it', async () => {
    const { element, switches } = await render(FormHost);
    const [control] = switches();
    expect(control.tagName).toBe('BUTTON');
    expect(control.getAttribute('aria-checked')).toBe('false');
    const label = element.querySelector('.smt-switch__label') as HTMLElement;
    expect(control.getAttribute('aria-labelledby')).toBe(label.id);
    expect(label.textContent).toBe('Send reminders');
  });

  it('flips the form value on a click of the switch or of its label, and says the person did it', async () => {
    const { fixture, element, switches, settle } = await render(FormHost);
    switches()[0].click();
    await settle();
    expect(fixture.componentInstance.model().enabled).toBe(true);
    expect(switches()[0].getAttribute('aria-checked')).toBe('true');
    (element.querySelector('.smt-switch__label') as HTMLElement).click();
    await settle();
    expect(fixture.componentInstance.model().enabled).toBe(false);
    expect(fixture.componentInstance.flips).toEqual([true, false]);
    expect(fixture.componentInstance.settings.enabled().touched()).toBe(true);
  });

  it('does not tell the person it flipped when the value changes from outside', async () => {
    const { fixture, switches, settle } = await render(FormHost);
    fixture.componentInstance.model.set({ enabled: true });
    await settle();
    expect(switches()[0].getAttribute('aria-checked')).toBe('true');
    expect(fixture.componentInstance.flips).toEqual([]);
  });

  it('takes its name from a surrounding smt-control label, or from smtAriaLabel', async () => {
    const { element, switches } = await render(PlainHost);
    const [inControl, inRow] = switches();
    const label = element.querySelector('label') as HTMLLabelElement;
    expect(label.htmlFor).toBe(inControl.id);
    expect(inControl.hasAttribute('aria-labelledby')).toBe(false);
    expect(inRow.getAttribute('aria-label')).toBe('Row 4 active');
  });

  it('stays put when disabled or read-only', async () => {
    const { fixture, switches, settle } = await render(PlainHost);
    fixture.componentInstance.locked.set(true);
    await settle();
    switches()[0].click();
    await settle();
    expect(fixture.componentInstance.on).toBe(true);
    expect(switches()[0].getAttribute('aria-readonly')).toBe('true');
    fixture.componentInstance.locked.set(false);
    fixture.componentInstance.off.set(true);
    await settle();
    expect(switches()[0].disabled).toBe(true);
  });
});
