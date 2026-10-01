import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import type { FormMeta, FormValues } from '@core/models/form-meta.models';
import { ApiService } from '@core/services/api.service';
import { formField } from '@testing/form-meta';
import { translateTest } from '@testing/i18n-test.stub';
import { SMTEntityFormComponent } from './smt-entity-form.component';

const FILE_ID = '6f1c2a52-6b0e-4d3e-9a51-1f2d3c4b5a69';

const META: FormMeta = {
  code: 'test.orders',
  fields: [
    formField('kind', 'select', { options: ['retail', 'wholesale'] }),
    formField('ownerId', 'number', { visibleWhen: [{ field: 'kind', op: 'eq', values: ['wholesale'] }] }),
    formField('code', 'text', { readonlyOnUpdate: true }),
    formField('doubled', 'number', { readonly: true, computed: true }),
    formField('total', 'money', { currencies: ['UZS', 'USD'] }),
    formField('unit', 'enum', { options: ['kg', 'pc'], optionLabels: { kg: 'Kilogram', pc: 'Piece' } }),
    formField('photo', 'image', { contentTypes: ['image/png', 'image/jpeg', 'image/webp'] }),
    formField('site', 'url'),
  ],
  layout: [
    {
      key: 'main',
      labelKey: 'entity.section.main',
      fields: ['kind', 'ownerId', 'code', 'doubled', 'total', 'unit', 'photo', 'site'],
    },
  ],
  actions: [],
  capabilities: [],
};

@Component({
  imports: [SMTEntityFormComponent],
  template: `<smt-entity-form [meta]="meta" [recordId]="recordId()" [(value)]="values" />`,
})
class HostComponent {
  readonly meta = META;
  readonly recordId = signal<number | null>(null);
  readonly values = signal<FormValues>({ kind: 'retail', total: { amount: null, currency: 'UZS' } });
}

/** Plan 10/10, item 5.2 (ADR-0032 4.1–4.4): the controls of the new types and the form flags on screen. */
describe('SMTEntityFormComponent with the types of item 5.2', () => {
  async function render(
    post = vi.fn(() => of({ id: FILE_ID, originalName: 'shelf.png', sizeBytes: 3, mimeType: 'image/png' })),
  ) {
    const api = { get: vi.fn(() => of({ items: [], nextCursor: null, hasMore: false })), post };
    await TestBed.configureTestingModule({
      imports: [HostComponent],
      providers: [{ provide: ApiService, useValue: api }],
    }).compileComponents();
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, root: fixture.nativeElement as HTMLElement, host: fixture.componentInstance, post };
  }

  it('shows a field only while its condition holds', async () => {
    const { fixture, root, host } = await render();
    expect(root.querySelector('[data-field="ownerId"]')).toBeNull();

    host.values.update((values) => ({ ...values, kind: 'wholesale' }));
    fixture.detectChanges();

    expect(root.querySelector('[data-field="ownerId"]')).not.toBeNull();
  });

  it('locks a computed field always and a field read-only on update once the record exists', async () => {
    const { fixture, root, host } = await render();
    expect((root.querySelector('[data-field="doubled"] input') as HTMLInputElement).disabled).toBe(true);
    expect((root.querySelector('[data-field="code"] input') as HTMLInputElement).disabled).toBe(false);

    host.recordId.set(7);
    fixture.detectChanges();

    expect((root.querySelector('[data-field="code"] input') as HTMLInputElement).disabled).toBe(true);
  });

  it('writes money as its amount and currency, a comma as the point', async () => {
    const { fixture, root, host } = await render();
    const amount = root.querySelector('[data-field="total"] input[inputmode="decimal"]') as HTMLInputElement;
    expect(amount.getAttribute('aria-required')).toBeNull();

    amount.value = '1 250,50';
    amount.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(host.values()['total']).toEqual({ amount: '1250.50', currency: 'UZS' });
    expect(root.querySelector('[data-field="total"] smt-select')?.getAttribute('data-smt-field-part')).not.toBeNull();
  });

  it('draws a web address as a url input', async () => {
    const { root } = await render();
    const site = root.querySelector('[data-field="site"] input') as HTMLInputElement;
    expect(site.type).toBe('url');
    expect(site.getAttribute('autocomplete')).toBe('url');
  });

  it('uploads a picked file, holds it and takes it away', async () => {
    const { fixture, root, host, post } = await render();
    const input = root.querySelector('[data-field="photo"] input[type="file"]') as HTMLInputElement;
    expect(input.accept).toBe('image/png,image/jpeg,image/webp');

    Object.defineProperty(input, 'files', { value: [new File(['abc'], 'shelf.png', { type: 'image/png' })] });
    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(post).toHaveBeenCalledWith('/files/upload', expect.any(FormData), { notifyError: false });
    expect(host.values()['photo']).toEqual({ id: FILE_ID, name: 'shelf.png', size: 3, contentType: 'image/png' });
    expect(root.querySelector('[data-field="photo"] .file-name')?.textContent).toContain('shelf.png');

    const remove = root.querySelector('[data-field="photo"] button') as HTMLButtonElement;
    expect(remove.getAttribute('aria-label')).toBe(translateTest('ui.entity_form.file_remove', { name: 'shelf.png' }));
    remove.click();
    fixture.detectChanges();
    expect(host.values()['photo']).toBeNull();
  });

  it('says why an upload failed', async () => {
    const { fixture, root } = await render(vi.fn(() => throwError(() => ({ detail: 'Слишком большой файл' }))));
    const input = root.querySelector('[data-field="photo"] input[type="file"]') as HTMLInputElement;
    Object.defineProperty(input, 'files', { value: [new File(['abc'], 'big.png', { type: 'image/png' })] });
    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(root.querySelector('[data-field="photo"]')?.textContent).toContain('Слишком большой файл');
  });
});
