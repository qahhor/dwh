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
import { SMTPhoneInputComponent } from './phone-input.component';
import { SMTPhoneInputValueAccessor } from './phone-input-value-accessor';
import { formatNational, joinPhone, SMT_PHONE_COUNTRIES, splitPhone } from './phone-utils';

@Component({
  standalone: true,
  imports: [SMTPhoneInputComponent, SMTPhoneInputValueAccessor, SMTControlComponent, FormsModule],
  template: `
    <smt-control smtLabel="Phone">
      <smt-phone-input [(ngModel)]="phone" name="phone" />
    </smt-control>
  `,
})
class Host {
  phone = '+998901234567';
}

const country = (iso: string) => SMT_PHONE_COUNTRIES.find(item => item.iso === iso)!;

describe('phone utils', () => {
  it('reads a stored number into its country and national digits, the longest code first', () => {
    expect(splitPhone('+998901234567')).toEqual({ country: country('UZ'), digits: '901234567' });
    expect(splitPhone('+7 701 123 45 67')).toEqual({ country: country('KZ'), digits: '7011234567' });
    expect(splitPhone('+79161234567', country('RU'))).toEqual({ country: country('RU'), digits: '9161234567' });
    expect(splitPhone('+4412345')).toEqual({ country: null, digits: '4412345' });
    expect(splitPhone('')).toEqual({ country: country('UZ'), digits: '' });
  });

  it('writes digits into the mask as far as they go and stores E.164', () => {
    expect(formatNational('901234567', country('UZ'))).toBe('(90) 123-45-67');
    expect(formatNational('9012', country('UZ'))).toBe('(90) 12');
    expect(formatNational('4412345', null)).toBe('4412345');
    expect(joinPhone(country('UZ'), '(90) 123-45-67')).toBe('+998901234567');
    expect(joinPhone(null, '4412345')).toBe('+4412345');
    expect(joinPhone(country('UZ'), '')).toBe('');
  });
});

describe('SMTPhoneInputComponent', () => {
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
    const number = element.querySelector('input[type="tel"]') as HTMLInputElement;
    const select = element.querySelector('select') as HTMLSelectElement;
    return { fixture, element, number, select, settle };
  }

  it('is named by the label as a number, with the country list named on its own', async () => {
    const { element, number, select } = await render();
    expect((element.querySelector('label') as HTMLLabelElement).htmlFor).toBe(number.id);
    expect(select.getAttribute('aria-label')).toBe('Country code');
    expect(select.value).toBe('UZ');
    expect(number.value).toBe('(90) 123-45-67');
    expect(number.placeholder).toBe('(__) ___-__-__');
  });

  it('formats as the person types, stops at the country\'s length and stores E.164', async () => {
    const { fixture, number, settle } = await render();
    number.value = '93 555 66 77 88';
    number.dispatchEvent(new Event('input'));
    await settle();
    expect(number.value).toBe('(93) 555-66-77');
    expect(fixture.componentInstance.phone).toBe('+998935556677');
  });

  it('changes the calling code with the country and keeps the digits', async () => {
    const { fixture, select, number, settle } = await render();
    select.value = 'KZ';
    select.dispatchEvent(new Event('change'));
    await settle();
    expect(fixture.componentInstance.phone).toBe('+7901234567');
    expect(number.value).toBe('(901) 234-56-7');
    select.value = 'other';
    select.dispatchEvent(new Event('change'));
    await settle();
    expect(select.value).toBe('other');
  });

  it('says an incomplete number once the field is left', async () => {
    const { element, number, settle } = await render();
    number.value = '9012';
    number.dispatchEvent(new Event('input'));
    number.dispatchEvent(new Event('blur'));
    await settle();
    const problem = element.querySelector('.smt-phone-input__problem') as HTMLElement;
    expect(problem.textContent).toBe('The number is not complete');
    expect(number.getAttribute('aria-describedby')).toContain(problem.id);
    expect(number.getAttribute('aria-invalid')).toBe('true');
  });
});
