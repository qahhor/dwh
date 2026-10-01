import { describe, expect, it } from 'vitest';
import { formField } from './form-meta';
import { formMetaFixture, renderEntityForm } from './entity-form';

// Plan 10/10, item 6.2: the form helpers fill and read a rendered entity form by field key.
describe('entity form helpers', () => {
  const meta = formMetaFixture('test.items', [
    formField('title', 'text', { required: true, maxLength: 20 }),
    formField('body', 'markdown'),
    formField('amount', 'number'),
    formField('active', 'boolean'),
    formField('code', 'text', { readonly: true }),
  ]);

  it('builds form-meta with one section holding every field', () => {
    expect(meta.layout).toEqual([
      { key: 'main', labelKey: 'entity.section.main', fields: ['title', 'body', 'amount', 'active', 'code'] },
    ]);
    expect(meta.actions).toEqual(['create', 'update', 'delete']);
  });

  it('fills each field by its type, and the values follow', async () => {
    const { host, form } = await renderEntityForm(meta, { title: '', active: false });

    form.fill('title', 'Shelf');
    form.fill('body', '**bold**');
    form.fill('amount', '12.5');
    form.fill('active', true);

    expect(form.keys()).toEqual(['title', 'body', 'amount', 'active', 'code']);
    expect(host.values()).toEqual(
      expect.objectContaining({ title: 'Shelf', body: '**bold**', amount: 12.5, active: true }),
    );
  });

  it('reads the problem under a field and tells a read-only field', async () => {
    const { form } = await renderEntityForm(meta, { code: 'A-1' }, { title: 'Too long' });

    expect(form.problem('title')).toBe('Too long');
    expect(form.problem('body')).toBe('');
    expect(form.readonly('code')).toBe(true);
    expect(form.readonly('title')).toBe(false);
  });

  it('refuses a field the form does not draw or does not type into', async () => {
    const { form } = await renderEntityForm(
      formMetaFixture('test.items', [formField('kind', 'select', { options: ['a', 'b'] })]),
    );

    expect(() => form.field('missing')).toThrow(/draws no field missing/);
    expect(() => form.fill('kind', 'a')).toThrow(/set kind through the host/);
  });
});
