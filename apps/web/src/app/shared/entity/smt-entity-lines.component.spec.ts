import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import type { FormCollectionMeta, FormValues } from '@core/models/form-meta.models';
import { formField } from '@testing/form-meta';
import { translateTest } from '@testing/i18n-test.stub';
import { SMTEntityLinesComponent } from './smt-entity-lines.component';

const LINES: FormCollectionMeta = {
  key: 'lines',
  labelKey: 'ui.entity_lines.number',
  maxRows: 3,
  fields: [
    formField('product', 'text'),
    formField('qty', 'number'),
    formField('price', 'money', { currencyFrom: 'currency', currencies: ['UZS', 'USD'] }),
    formField('amount', 'money', { computed: true, currencyFrom: 'currency' }),
  ],
};

/* The rows of a document on its form (ADR-0032 9.1). */
describe('SMTEntityLinesComponent', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] }));

  function render(rows: FormValues[], inputs: Record<string, unknown> = {}) {
    const fixture = TestBed.createComponent(SMTEntityLinesComponent);
    fixture.componentRef.setInput('collection', LINES);
    fixture.componentRef.setInput('values', { currency: 'UZS' });
    fixture.componentRef.setInput('rows', rows);
    for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const click = (testId: string, index = 0) => {
      (host.querySelectorAll(`[data-testid="${testId}"]`)[index] as HTMLButtonElement).click();
      fixture.detectChanges();
    };
    return { fixture, lines: fixture.componentInstance, host, click };
  }

  const row = (product: string): FormValues => ({ id: null, product, qty: 1, price: null, amount: null });

  it('titles the section and says there are no rows', () => {
    const { host } = render([]);
    const section = host.querySelector('section') as HTMLElement;
    expect(host.querySelector(`#${section.getAttribute('aria-labelledby')}`)?.textContent?.trim()).toBe(
      translateTest('ui.entity_lines.number'),
    );
    expect(host.textContent).toContain(translateTest('ui.entity_lines.empty'));
  });

  it('adds empty rows up to the most the collection takes', () => {
    const { lines, host, click } = render([]);

    click('entity-line-add');
    expect(lines.rows()).toHaveLength(1);
    expect(lines.rows()[0]).toMatchObject({ id: null, product: null, qty: null });
    click('entity-line-add');
    click('entity-line-add');
    expect(lines.rows()).toHaveLength(3);
    expect((host.querySelector('[data-testid="entity-line-add"]') as HTMLButtonElement).disabled).toBe(true);
    lines.add();
    expect(lines.rows()).toHaveLength(3);
  });

  it('moves a row up and down within the list and removes it', () => {
    const { lines, host, click } = render([row('A'), row('B'), row('C')]);

    expect((host.querySelectorAll('[data-testid="entity-line-up"]')[0] as HTMLButtonElement).disabled).toBe(true);
    expect((host.querySelectorAll('[data-testid="entity-line-down"]')[2] as HTMLButtonElement).disabled).toBe(true);
    expect(host.querySelectorAll('[data-testid="entity-line-up"]')[1].getAttribute('aria-label')).toBe(
      translateTest('ui.entity_lines.move_up', { n: 2 }),
    );

    click('entity-line-down', 0);
    expect(lines.rows().map((item) => item['product'])).toEqual(['B', 'A', 'C']);
    click('entity-line-up', 2);
    expect(lines.rows().map((item) => item['product'])).toEqual(['B', 'C', 'A']);
    lines.move(0, -1);
    expect(lines.rows().map((item) => item['product'])).toEqual(['B', 'C', 'A']);
    click('entity-line-remove', 1);
    expect(lines.rows().map((item) => item['product'])).toEqual(['B', 'A']);
  });

  it('changes one field of one row', () => {
    const { lines } = render([row('A'), row('B')]);
    lines.set(1, 'qty', 5);
    expect(lines.rows()[1]['qty']).toBe(5);
    expect(lines.rows()[0]['qty']).toBe(1);
  });

  it('offers only the document currency for its money, and the field own list without one', () => {
    const { lines, fixture } = render([row('A')]);
    const price = LINES.fields[2];
    expect(lines.currenciesOf(price)).toEqual(['UZS']);
    expect(lines.moneyOf(price, { price: '2.50' })).toEqual({ amount: '2.50', currency: 'UZS' });

    fixture.componentRef.setInput('values', {});
    expect(lines.currenciesOf(price)).toEqual(['UZS', 'USD']);
    expect(lines.currenciesOf(LINES.fields[0])).toEqual([]);
  });

  it('shows a computed value as the server gave it, a dash before', () => {
    const { lines, host } = render([{ ...row('A'), amount: '30.00' }, row('B')]);

    expect(host.querySelector('[data-line="1"] output')?.textContent?.trim()).toBe('—');
    expect(lines.computedText(LINES.fields[3], { amount: '30.00' })).toContain('30');
  });

  it('shows the problems of a row, of its fields and of the collection by their address', () => {
    const { lines, host } = render([row('A')], {
      problems: { 'lines[0]': 'Строка неполна', 'lines[0].qty': 'Больше нуля', lines: 'Нужна строка' },
    });

    expect(host.querySelector('.entity-line-problem')?.textContent?.trim()).toBe('Строка неполна');
    expect(lines.problemOf(0, 'qty')).toBe('Больше нуля');
    expect(host.querySelector('[data-testid="entity-lines-problem"]')?.textContent?.trim()).toBe('Нужна строка');
  });

  it('offers no changes while a state of the process locks the rows', () => {
    const { host } = render([row('A')], { readonly: true });
    expect(host.querySelector('[data-testid="entity-line-add"]')).toBeNull();
    expect(host.querySelector('[data-testid="entity-line-remove"]')).toBeNull();
  });
});
