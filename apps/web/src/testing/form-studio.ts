import type { FormStudioPreview } from '@features/settings/form-studio/form-studio.models';
import type {
  StudioDraftResponse,
  StudioVersionPackage,
} from '@features/settings/form-studio/form-studio-lifecycle.models';

export const STUDIO_PREVIEW: FormStudioPreview = {
  declaration: {
    formatVersion: 1,
    kind: 'form-layout',
    entityCode: 'example.products',
    sections: [{ id: 'main', fields: [{ fieldId: 'code' }, { fieldId: 'name' }] }],
  },
  meta: {
    code: 'example.products',
    fields: [
      { key: 'code', labelKey: 'example.products.col.code', type: 'text', required: true, readonlyOnUpdate: true },
      { key: 'name', labelKey: 'example.products.col.name', type: 'text', required: true },
    ],
    layout: [{ key: 'main', labelKey: 'entity.section.main', fields: ['code', 'name'] }],
    actions: ['create'],
    capabilities: [],
  },
};
export const STUDIO_DRAFT: StudioDraftResponse = {
  draft: { entityCode: 'example.products', revision: 1, declaration: STUDIO_PREVIEW.declaration },
  meta: STUDIO_PREVIEW.meta,
};
export const STUDIO_VERSION: StudioVersionPackage = {
  version: 1,
  sourceRevision: 1,
  checksum: 'a'.repeat(64),
  package: {
    formatVersion: 1,
    kind: 'form-layout-package',
    entityCode: 'example.products',
    version: 1,
    sourceRevision: 1,
    dependencies: { contractVersion: 1, metadataChecksum: 'b'.repeat(64) },
    declaration: STUDIO_PREVIEW.declaration,
  },
};
