import { describe, expect, it } from 'vitest';
import { studioPreviewMeta } from './form-studio-layout';
import type { FormStudioPreview } from './form-studio.models';

describe('studioPreviewMeta', () => {
  it('changes only literal labels and layout while retaining IDs, rules and the source metadata', () => {
    const source: FormStudioPreview = {
      declaration: {
        formatVersion: 1,
        kind: 'form-layout',
        entityCode: 'example.products',
        sections: [
          {
            id: 'details',
            labels: { ru: 'Данные', uz: 'Ma’lumotlar', en: 'Details' },
            fields: [{ fieldId: 'name', labels: { ru: 'Название', uz: 'Nomi', en: 'Name' } }, { fieldId: 'code' }],
          },
        ],
      },
      meta: {
        code: 'example.products',
        fields: [
          { key: 'code', labelKey: 'code', type: 'text', required: true, readonlyOnUpdate: true, pattern: '[a-z]+' },
          { key: 'name', labelKey: 'name', type: 'text', required: true, maxLength: 255 },
        ],
        layout: [{ key: 'main', labelKey: 'entity.section.main', fields: ['code', 'name'] }],
        actions: ['create'],
        capabilities: [],
      },
    };
    const original = structuredClone(source);
    const result = studioPreviewMeta(source, 'en');
    expect(result.layout).toEqual([{ key: 'details', labelKey: '', label: 'Details', fields: ['name', 'code'] }]);
    expect(result.fields[1]).toEqual({ ...source.meta.fields[1], labelKey: '', label: 'Name' });
    expect(result.fields[0]).toEqual(source.meta.fields[0]);
    expect(result.actions).toEqual(source.meta.actions);
    expect(source).toEqual(original);
    result.fields[0].required = false;
    expect(source.meta.fields[0].required).toBe(true);
    expect(studioPreviewMeta(source, 'ru').fields[1].label).toBe('Название');
  });
});
