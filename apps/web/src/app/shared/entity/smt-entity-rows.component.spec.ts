import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import type { FormCollectionMeta } from '@core/models/form-meta.models';
import { formField } from '@testing/form-meta';
import { translateTest } from '@testing/i18n-test.stub';
import { SMTEntityRowsComponent } from './smt-entity-rows.component';

const ITEMS: FormCollectionMeta = {
  key: 'items',
  labelKey: 'ui.entity_lines.number',
  maxRows: 50,
  fields: [
    formField('product', 'text', { label: 'Товар' }),
    formField('qty', 'number', { label: 'Кол-во' }),
    formField('price', 'money', { label: 'Цена', currencyFrom: 'currency' }),
  ],
};

/* The rows of a document on its card (ADR-0032 9.1, 9.3). */
describe('SMTEntityRowsComponent', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] }));

  function render(rows: Record<string, unknown>[], record: Record<string, unknown> = { currency: 'UZS' }) {
    const fixture = TestBed.createComponent(SMTEntityRowsComponent);
    fixture.componentRef.setInput('collection', ITEMS);
    fixture.componentRef.setInput('rows', rows);
    fixture.componentRef.setInput('record', record);
    fixture.detectChanges();
    return { fixture, rows: fixture.componentInstance, host: fixture.nativeElement as HTMLElement };
  }

  it('says there are no rows', () => {
    const { host } = render([]);
    expect(host.querySelector('table')).toBeNull();
    expect(host.querySelector('.entity-rows-empty')?.textContent?.trim()).toBe(translateTest('ui.entity_lines.empty'));
  });

  it('draws a column per field after the row place, with the collection as caption', () => {
    const { host } = render([{ id: 11, product: 'Мука', qty: 3, price: '10.00' }]);

    const headers = Array.from(host.querySelectorAll('th')).map((cell) => cell.textContent?.trim());
    // A declared field is labelled by its dictionary key (here a test key, shown as it is).
    expect(headers).toEqual([translateTest('ui.entity_lines.number'), 'test.product', 'test.qty', 'test.price']);
    expect(host.querySelector('caption')?.textContent?.trim()).toBe(translateTest('ui.entity_lines.number'));
    expect(host.getAttribute('data-collection')).toBe('items');
  });

  it('shows each value in words: an empty one as a dash, money in the document currency, numbers aligned', () => {
    const { rows, host } = render([
      { id: 11, product: 'Мука', qty: 3, price: '10.00' },
      { product: '', qty: null, price: null },
    ]);

    expect(rows.shown().map((row) => row.key)).toEqual(['11', 'new-1']);
    const first = Array.from(host.querySelectorAll('tr[data-line="0"] td')).map((cell) => cell.textContent?.trim());
    expect(first[0]).toBe('1');
    expect(first[1]).toBe('Мука');
    expect(first[3]).toContain('10');
    expect(first[3]).toContain('UZS');
    expect(host.querySelector('tr[data-line="0"] td[data-field="qty"]')?.classList).toContain('entity-rows-numeric');
    expect(host.querySelector('tr[data-line="0"] td[data-field="product"]')?.classList).not.toContain(
      'entity-rows-numeric',
    );

    const second = Array.from(host.querySelectorAll('tr[data-line="1"] td')).map((cell) => cell.textContent?.trim());
    expect(second.slice(1)).toEqual(['—', '—', '—']);
  });
});
