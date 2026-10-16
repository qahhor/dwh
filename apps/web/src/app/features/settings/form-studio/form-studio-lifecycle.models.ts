import type { FormMeta } from '@core/models/form-meta.models';
import type { FormLayoutDeclaration } from './form-studio.models';

export function studioDraftPath(code: FormMeta['code']): string {
  return `/form-studio/drafts/${encodeURIComponent(code)}`;
}
export const STUDIO_PACKAGE_MAX_BYTES = 64 * 1024;
export interface StoredStudioDraft {
  entityCode: string;
  revision: number;
  declaration: FormLayoutDeclaration;
}
export interface StudioDraftResponse {
  draft: StoredStudioDraft | null;
  meta: FormMeta;
}
export interface StudioVersionSummary {
  version: number;
  sourceRevision: number;
  checksum: string;
  createdAt: string;
}
export interface StudioPublicationPackage {
  formatVersion: 1;
  kind: 'form-layout-package';
  entityCode: string;
  version: number;
  sourceRevision: number;
  dependencies: { contractVersion: 1; metadataChecksum: string };
  declaration: FormLayoutDeclaration;
}
export interface StudioVersionPackage {
  version: number;
  sourceRevision: number;
  checksum: string;
  package: StudioPublicationPackage;
}
export interface StudioPackageVerification {
  valid: true;
  version: number;
  sourceRevision: number;
  checksum: string;
}
