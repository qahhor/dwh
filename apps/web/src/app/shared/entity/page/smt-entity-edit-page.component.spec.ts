import { of, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';
import type { FormMeta } from '@core/models/form-meta.models';
import { formField } from '@testing/form-meta';
import { entityRecord, problem, renderEntityScreen } from '@testing/entity-page';
import { translateTest } from '@testing/i18n-test.stub';

const CODE = 'test.orders';

const META: FormMeta = {
  code: CODE,
  listCode: CODE,
  fields: [formField('number', 'text', { label: 'Номер', labelKey: '', required: true })],
  layout: [{ key: 'main', labelKey: 'entity.section.main', fields: ['number'] }],
  actions: ['create', 'update'],
  capabilities: [],
};

const ORDER = entityRecord(4, { number: 'ЗК-4' });

function stateTitle(root: HTMLElement): string | undefined {
  return root.querySelector('[data-testid="entity-page-state"] h2')?.textContent?.trim();
}

/* Creating or changing a record of a declared entity (ADR-0032 7.1). */
describe('SMTEntityEditPageComponent', () => {
  it('patches a title without rounding untouched moments or sending the read-only resolved time', async () => {
    const fields = [
      formField('title', 'text', { required: true }),
      formField('startsAt', 'datetime'),
      formField('resolvedTime', 'datetime', { readonly: true }),
      formField('cfDueAt', 'datetime', { attribute: 'due_at' }),
    ];
    const record = entityRecord(4, {
      title: 'Before',
      startsAt: '2026-10-13T04:15:37.123456Z',
      resolvedTime: '2026-10-13T04:15:48.987654Z',
      attributes: { due_at: '2026-10-13T04:15:59.654321Z' },
    });
    const { root, api, router, settle } = await renderEntityScreen(`/e/${CODE}/4/edit`, {
      meta: {
        ...META,
        fields,
        layout: [{ key: 'main', labelKey: 'entity.section.main', fields: fields.map((f) => f.key) }],
      },
      records: [record],
    });
    const title = root.querySelector('[data-field="title"] input') as HTMLInputElement;
    title.value = 'After';
    title.dispatchEvent(new Event('input'));
    await settle();
    (root.querySelector('[data-testid="form-submit"]') as HTMLButtonElement).click();
    await settle();

    expect(api.patch).toHaveBeenCalledExactlyOnceWith(
      `/entities/${CODE}/4`,
      {
        title: 'After',
        startsAt: '2026-10-13T04:15:37.123456Z',
        attributes: { due_at: '2026-10-13T04:15:59.654321Z' },
      },
      { notifyError: false, ifMatch: 1 },
    );
    expect(router.url).toBe(`/e/${CODE}/4`);
  });

  it('keeps every editable field checked after a failed submit, including previously valid fields', async () => {
    const { root, settle } = await renderEntityScreen(`/e/${CODE}/new`, {
      meta: {
        ...META,
        fields: [...META.fields, formField('title', 'text', { required: true })],
        layout: [{ key: 'main', labelKey: 'entity.section.main', fields: ['number', 'title'] }],
      },
    });
    const number = root.querySelector('[data-field="number"] input') as HTMLInputElement;
    number.value = 'Valid';
    number.dispatchEvent(new Event('input'));
    await settle();
    (root.querySelector('[data-testid="form-submit"]') as HTMLButtonElement).click();
    await settle();
    number.focus();
    number.value = '';
    number.dispatchEvent(new Event('input'));
    await settle();
    expect(root.querySelector('[data-field="number"]')?.textContent).toContain(
      translateTest('ui.entity_form.required'),
    );
  });

  it('keeps an unknown 422 field in the summary without exposing it as a field label', async () => {
    const { root, settle, toast } = await renderEntityScreen(`/e/${CODE}/4/edit`, {
      meta: META,
      records: [ORDER],
      changes: {
        [`PATCH /entities/${CODE}/4`]: () =>
          throwError(() =>
            problem(422, {
              errors: [{ field: 'internalField', code: 'required', message: 'Server constraint failed' }],
            }),
          ),
      },
    });
    (root.querySelector('[data-testid="form-submit"]') as HTMLButtonElement).click();
    await settle();
    const summary = root.querySelector('[data-testid="form-error-summary"]');
    expect(summary?.textContent).toContain('Server constraint failed');
    expect(summary?.textContent).not.toContain('internalField');
    expect(toast.error).not.toHaveBeenCalled();
  });

  it('keeps the summary synchronized with document row feedback before and after correction', async () => {
    const { root, settle } = await renderEntityScreen(`/e/${CODE}/new`, {
      meta: {
        ...META,
        collections: [
          {
            key: 'lines',
            labelKey: 'ui.entity_lines.number',
            maxRows: 10,
            fields: [
              formField('qty', 'number', { labelKey: '', label: 'Quantity', required: true, min: 1 }),
              formField('unit', 'text', { required: true }),
            ],
          },
        ],
      },
    });
    (root.querySelector('[data-testid="entity-line-add"]') as HTMLButtonElement).click();
    await settle();
    const qty = root.querySelector('[data-field="qty"] input') as HTMLInputElement;
    qty.focus();
    qty.blur();
    await settle();
    expect(root.querySelector('[data-testid="form-error-summary"]')).toBeNull();

    (root.querySelector('[data-testid="form-submit"]') as HTMLButtonElement).click();
    await settle();
    const summary = root.querySelector('[data-testid="form-error-summary"]') as HTMLElement;
    expect(summary.textContent).toContain(translateTest('ui.entity_form.required'));
    expect(summary.textContent).toContain(translateTest('ui.entity_lines.line', { n: 1 }));
    const link = [...summary.querySelectorAll('a')].find((item) => item.textContent?.includes('Quantity'));
    expect(link?.getAttribute('href')).toBe(`#${qty.id}`);
    link?.click();
    expect(document.activeElement).toBe(qty);

    qty.value = '2';
    qty.dispatchEvent(new Event('input'));
    await settle();
    expect(root.querySelector('[data-testid="form-error-summary"]')).toBeNull();
  });

  it('titles a new record and a change by the record name', async () => {
    const created = await renderEntityScreen(`/e/${CODE}/new`, { meta: META });
    expect(created.root.querySelector('h1')?.textContent?.trim()).toBe(translateTest('ui.entity_page.create_title'));
    expect(created.root.querySelector('[data-testid="form-submit"]')?.textContent?.trim()).toBe('Создать');
    (created.root.querySelector('[data-testid="form-cancel"]') as HTMLButtonElement).click();
    await created.settle();
    expect(created.router.url).toBe('/e/test.orders');
    expect(created.confirm).not.toHaveBeenCalled();
  });

  it('titles a change by the record name and leads back to the record', async () => {
    const { root, router, settle } = await renderEntityScreen(`/e/${CODE}/4/edit`, { meta: META, records: [ORDER] });
    expect(root.querySelector('h1')?.textContent?.trim()).toBe(
      translateTest('ui.entity_page.edit_title', { name: 'ЗК-4' }),
    );
    (root.querySelector('[data-testid="form-cancel"]') as HTMLButtonElement).click();
    await settle();
    expect(router.url).toBe(`/e/${CODE}/4`);
  });

  it('refuses a new record to a viewer who may not create', async () => {
    const { root, api } = await renderEntityScreen(`/e/${CODE}/new`, { meta: { ...META, actions: ['update'] } });
    expect(stateTitle(root)).toBe(translateTest('ui.entity_page.denied'));
    expect(api.get.mock.calls.some(([path]) => String(path).startsWith(`/entities/${CODE}/`))).toBe(false);
  });

  it('says "not found" for an id that is not a number, without asking the server', async () => {
    const { root, api } = await renderEntityScreen(`/e/${CODE}/abc/edit`, { meta: META });
    expect(stateTitle(root)).toBe(translateTest('ui.entity_page.record_missing'));
    expect(api.get.mock.calls.some(([path]) => String(path).startsWith(`/entities/${CODE}/`))).toBe(false);
  });

  it('offers to retry a failed read of the record, then shows the form', async () => {
    let reads = 0;
    const { root, settle } = await renderEntityScreen(`/e/${CODE}/4/edit`, {
      meta: META,
      answers: { [`/entities/${CODE}/4`]: () => (reads++ === 0 ? throwError(() => problem(500)) : of(ORDER)) },
    });
    expect(stateTitle(root)).toBe(translateTest('ui.entity_page.load_failed'));

    (root.querySelector('[data-testid="entity-page-state"] button') as HTMLButtonElement).click();
    await settle();
    expect(reads).toBe(2);
    expect(root.querySelector('smt-entity-form')).not.toBeNull();
  });
});
