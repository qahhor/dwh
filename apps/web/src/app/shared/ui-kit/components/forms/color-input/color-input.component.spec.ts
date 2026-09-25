// @vitest-environment jsdom
import '@angular/compiler';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { afterEach, describe, expect, it } from 'vitest';
import { SMTI18nService } from '../../../i18n';
import { testI18n } from '../../../i18n/test-messages';
import { tickInZone } from '../../../testing/zone-tick';
import { SMTControlComponent } from '../control/control.component';
import { normalizeHex, SMTColorInputComponent } from './color-input.component';
import { SMTColorInputValueAccessor } from './color-input-value-accessor';

@Component({
  standalone: true,
  imports: [SMTColorInputComponent, SMTColorInputValueAccessor, SMTControlComponent, FormsModule],
  template: `
    <smt-control smtLabel="Type colour">
      <smt-color-input [(ngModel)]="color" name="color" />
    </smt-control>
  `,
})
class Host {
  color = '#2563eb';
}

describe('normalizeHex', () => {
  it.each([
    ['#2563EB', '#2563eb'], ['2563eb', '#2563eb'], ['#abc', '#aabbcc'], [' #ABC ', '#aabbcc'],
  ])('reads %j as %s', (text, hex) => {
    expect(normalizeHex(text)).toBe(hex);
  });

  it.each(['', 'blue', '#12345', '#ggg', '#1234567'])('refuses %j', text => {
    expect(normalizeHex(text)).toBeNull();
  });
});

describe('SMTColorInputComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  async function render() {
    TestBed.configureTestingModule({ providers: [{ provide: SMTI18nService, useValue: testI18n() }] });
    const fixture = TestBed.createComponent(Host);
    document.body.appendChild(fixture.nativeElement);
    const settle = async () => {
      tickInZone();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    await settle();
    await settle();
    const element = fixture.nativeElement as HTMLElement;
    const radios = () => Array.from(element.querySelectorAll<HTMLElement>('[role="radio"]'));
    const hex = element.querySelector('.smt-color-input__hex') as HTMLInputElement;
    return { fixture, element, radios, hex, settle };
  }

  it('offers a named palette as a radio group named by the label, with the value chosen', async () => {
    const { element, radios, hex } = await render();
    const group = element.querySelector('[role="radiogroup"]') as HTMLElement;
    expect(group.getAttribute('aria-labelledby')).toBe(element.querySelector('label')!.id);
    expect(radios().map(radio => radio.textContent!.trim())).toContain('Blue');
    expect(radios().find(radio => radio.getAttribute('aria-checked') === 'true')!.textContent).toContain('Blue');
    expect(hex.value).toBe('#2563eb');
    expect(hex.getAttribute('aria-label')).toBe('Colour code');
  });

  it('writes a palette colour and a typed own colour, and shows the code of either', async () => {
    const { fixture, radios, hex, settle } = await render();
    radios().find(radio => radio.textContent!.includes('Red'))!.click();
    await settle();
    expect(fixture.componentInstance.color).toBe('#dc2626');
    expect(hex.value).toBe('#dc2626');

    hex.value = '#ABC';
    hex.dispatchEvent(new Event('input'));
    hex.dispatchEvent(new Event('blur'));
    await settle();
    expect(fixture.componentInstance.color).toBe('#aabbcc');
    expect(radios().some(radio => radio.getAttribute('aria-checked') === 'true')).toBe(false);
  });

  it('says so and keeps the value for a code that is not a colour', async () => {
    const { fixture, element, hex, settle } = await render();
    hex.value = 'blue-ish';
    hex.dispatchEvent(new Event('input'));
    hex.dispatchEvent(new Event('blur'));
    await settle();
    expect(fixture.componentInstance.color).toBe('#2563eb');
    const problem = element.querySelector('.smt-color-input__problem') as HTMLElement;
    expect(problem.textContent).toContain('#RRGGBB');
    expect(hex.getAttribute('aria-describedby')).toBe(problem.id);
  });
});
