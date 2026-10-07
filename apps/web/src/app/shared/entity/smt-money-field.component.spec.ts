import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import type { MoneyValue } from '@core/services/field-values';
import { SMTMoneyFieldComponent } from './smt-money-field.component';

describe('SMTMoneyFieldComponent', () => {
  function render(value: MoneyValue | null, currencies: readonly string[] = ['UZS', 'USD']) {
    const fixture = TestBed.createComponent(SMTMoneyFieldComponent);
    fixture.componentRef.setInput('label', 'Сумма');
    fixture.componentRef.setInput('currencies', currencies);
    fixture.componentRef.setInput('value', value);
    fixture.detectChanges();
    const field = fixture.componentInstance;
    const amount = fixture.nativeElement.querySelector('.money-amount') as HTMLInputElement;
    return { fixture, field, amount };
  }

  function type(input: HTMLInputElement, text: string): void {
    input.value = text;
    input.dispatchEvent(new Event('input'));
  }

  it('shows the amount as text and the first currency when the value has none', () => {
    const { field, amount } = render(null);

    expect(amount.value).toBe('');
    expect(field.currency()).toBe('UZS');
    expect(field.currencyOptions()).toEqual([
      { id: 'UZS', label: 'UZS' },
      { id: 'USD', label: 'USD' },
    ]);
    expect(render({ amount: '1250.50', currency: 'USD' }).amount.value).toBe('1250.50');
  });

  it('keeps the typed amount as text: a comma is a decimal point and spaces are dropped', () => {
    const { field, amount } = render(null);

    type(amount, '1 250,50');
    expect(field.value()).toEqual({ amount: '1250.50', currency: 'UZS' });

    type(amount, '');
    expect(field.value()).toEqual({ amount: null, currency: 'UZS' });
  });

  it('changes the currency without touching the amount, and ignores an empty choice', () => {
    const { field } = render({ amount: '10', currency: 'UZS' });

    field.setCurrency('USD');
    expect(field.value()).toEqual({ amount: '10', currency: 'USD' });

    field.setCurrency(null);
    expect(field.value()).toEqual({ amount: '10', currency: 'USD' });
  });

  it('labels the amount input and disables it with the field', () => {
    const { fixture, amount } = render(null, ['UZS']);
    const label = fixture.nativeElement.querySelector('label') as HTMLLabelElement | null;
    expect(label?.textContent).toContain('Сумма');

    fixture.componentRef.setInput('disabled', true);
    fixture.detectChanges();
    expect(amount.disabled).toBe(true);
  });
});
