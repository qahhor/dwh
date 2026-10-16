import { TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { FormStudioStore } from './form-studio.store';
import { FormStudioLifecycleStore } from './form-studio-lifecycle.store';
import { FormStudioVersionsStore } from './form-studio-versions.store';
import { studioDraftPath, type StudioDraftResponse } from './form-studio-lifecycle.models';
import { STUDIO_DRAFT, STUDIO_PREVIEW, STUDIO_VERSION } from '@testing/form-studio';

const STUDIO_DRAFT_PATH = studioDraftPath('example.products');

describe('FormStudioLifecycleStore', () => {
  let lifecycle: FormStudioLifecycleStore;
  let editor: FormStudioStore;
  let versions: FormStudioVersionsStore;
  const api = { get: vi.fn(), post: vi.fn(), put: vi.fn() };
  beforeEach(() => {
    api.get
      .mockReset()
      .mockImplementation((path) =>
        of(
          structuredClone(
            path.endsWith('/versions')
              ? { items: [], nextCursor: null, hasMore: false, totalReturned: 0 }
              : path.endsWith('/prototype')
                ? STUDIO_PREVIEW
                : STUDIO_DRAFT,
          ),
        ),
      );
    api.post.mockReset();
    api.put.mockReset();
    TestBed.configureTestingModule({
      providers: [
        FormStudioStore,
        FormStudioLifecycleStore,
        FormStudioVersionsStore,
        { provide: ApiService, useValue: api },
      ],
    });
    lifecycle = TestBed.inject(FormStudioLifecycleStore);
    editor = TestBed.inject(FormStudioStore);
    versions = TestBed.inject(FormStudioVersionsStore);
  });
  it('loads the authorized saved draft and revision for the existing renderer', () => {
    lifecycle.load();
    expect(lifecycle.revision()).toBe(1);
    expect(editor.validated()?.meta.fields[0].readonlyOnUpdate).toBe(true);
    expect(editor.draft()).toEqual(STUDIO_PREVIEW.declaration);
    expect(lifecycle.serverDirty()).toBe(false);
    expect(lifecycle.canSave()).toBe(false);
    expect(lifecycle.canSeal()).toBe(true);
  });
  it('offers creation for a missing draft without treating the initial prototype as lost input', () => {
    api.get.mockImplementation((path) =>
      of(
        structuredClone(
          path === STUDIO_DRAFT_PATH
            ? { draft: null, meta: STUDIO_PREVIEW.meta }
            : path.endsWith('/prototype')
              ? STUDIO_PREVIEW
              : { items: [], hasMore: false, nextCursor: null, totalReturned: 0 },
        ),
      ),
    );
    lifecycle.load();
    expect(lifecycle.revision()).toBeNull();
    expect(lifecycle.serverDirty()).toBe(false);
    expect(lifecycle.canSave()).toBe(true);
    expect(lifecycle.canSeal()).toBe(false);
    api.post.mockReturnValue(of(structuredClone(STUDIO_DRAFT)));
    lifecycle.save();
    expect(api.post).toHaveBeenCalledWith(STUDIO_DRAFT_PATH, STUDIO_PREVIEW.declaration, { notifyError: false });
    expect(lifecycle.revision()).toBe(1);
  });
  it('uses only the fields allowed by the latest authorized metadata when no saved draft exists', () => {
    const filtered = structuredClone(STUDIO_PREVIEW.meta);
    filtered.fields = filtered.fields.filter((field) => field.key === 'code');
    filtered.layout[0].fields = ['code'];
    api.get.mockImplementation((path) =>
      of(
        path.endsWith('/prototype')
          ? STUDIO_PREVIEW
          : path === STUDIO_DRAFT_PATH
            ? { draft: null, meta: filtered }
            : { items: [], hasMore: false, nextCursor: null, totalReturned: 0 },
      ),
    );
    lifecycle.load();
    expect(editor.baseMeta()?.fields.map((field) => field.key)).toEqual(['code']);
    expect(editor.draft()?.sections[0].fields.map((field) => field.fieldId)).toEqual(['code']);
  });

  it('saves with If-Match and keeps the server baseline separate from a local file export', () => {
    lifecycle.load();
    editor.edit((draft) => draft.sections[0].fields.reverse());
    api.post.mockReturnValue(of({ declaration: editor.draft(), meta: STUDIO_PREVIEW.meta }));
    editor.validate();
    editor.markExported();
    expect(editor.dirty()).toBe(false);
    expect(lifecycle.serverDirty()).toBe(true);
    const expected = structuredClone(editor.draft());
    api.put.mockReturnValue(
      of({ draft: { ...STUDIO_DRAFT.draft!, revision: 2, declaration: expected }, meta: STUDIO_PREVIEW.meta }),
    );
    lifecycle.save();
    expect(api.put).toHaveBeenCalledWith(STUDIO_DRAFT_PATH, expected, { notifyError: false, ifMatch: 1 });
    expect(lifecycle.revision()).toBe(2);
    expect(lifecycle.serverDirty()).toBe(false);
  });
  it('preserves input and its revision on 409 until an explicit reload', () => {
    lifecycle.load();
    editor.edit((draft) => draft.sections[0].fields.reverse());
    const local = structuredClone(editor.draft());
    api.put.mockReturnValue(
      throwError(() => ({ status: 409, code: 'revision_conflict', detail: 'Changed elsewhere' })),
    );
    const reads = api.get.mock.calls.length;
    lifecycle.save();
    expect(lifecycle.conflict()).toBe(true);
    expect(lifecycle.revision()).toBe(1);
    expect(editor.draft()).toEqual(local);
    expect(lifecycle.serverDirty()).toBe(true);
    expect(api.get.mock.calls).toHaveLength(reads);
    expect(lifecycle.canSave()).toBe(false);
    api.get.mockImplementation((path) =>
      of(
        path.endsWith('/prototype')
          ? STUDIO_PREVIEW
          : path === STUDIO_DRAFT_PATH
            ? { ...STUDIO_DRAFT, draft: { ...STUDIO_DRAFT.draft!, revision: 2 } }
            : { items: [], nextCursor: null, hasMore: false, totalReturned: 0 },
      ),
    );
    lifecycle.load();
    expect(lifecycle.conflict()).toBe(false);
    expect(lifecycle.revision()).toBe(2);
    expect(editor.draft()).toEqual(STUDIO_PREVIEW.declaration);
  });
  it('recovers changed dependencies by saving the unchanged layout in a new revision', () => {
    lifecycle.load();
    const declaration = structuredClone(editor.draft());
    api.post.mockReturnValue(
      throwError(() => ({
        status: 409,
        messageKey: 'error.md.form_studio_dependencies_changed',
        detail: 'Dependencies changed',
      })),
    );
    lifecycle.seal();
    expect(lifecycle.conflict()).toBe(false);
    expect(lifecycle.serverDirty()).toBe(false);
    expect(lifecycle.canSave()).toBe(true);
    expect(lifecycle.canSeal()).toBe(false);
    expect(editor.draft()).toEqual(declaration);
    lifecycle.load();
    expect(lifecycle.revision()).toBe(1);
    expect(lifecycle.canSave()).toBe(true);
    expect(lifecycle.canSeal()).toBe(false);
    api.put
      .mockReturnValueOnce(throwError(() => ({ status: 503, detail: 'Try again' })))
      .mockReturnValueOnce(of({ ...STUDIO_DRAFT, draft: { ...STUDIO_DRAFT.draft!, revision: 2 } }));
    lifecycle.save();
    expect(lifecycle.canSave()).toBe(true);
    expect(lifecycle.canSeal()).toBe(false);
    lifecycle.save();
    expect(api.put).toHaveBeenLastCalledWith(STUDIO_DRAFT_PATH, declaration, { notifyError: false, ifMatch: 1 });
    expect(lifecycle.revision()).toBe(2);
    expect(lifecycle.canSave()).toBe(false);
    expect(lifecycle.canSeal()).toBe(true);
    expect(editor.draft()).toEqual(declaration);
    api.post.mockReturnValue(of({ ...STUDIO_VERSION, version: 2, sourceRevision: 2 }));
    lifecycle.seal();
    expect(versions.selected()?.version).toBe(2);
  });
  it('still requires explicit reload if another editor wins the dependency recovery save', () => {
    lifecycle.load();
    api.post.mockReturnValue(
      throwError(() => ({ status: 409, messageKey: 'error.md.form_studio_dependencies_changed' })),
    );
    lifecycle.seal();
    api.put.mockReturnValue(throwError(() => ({ status: 409, messageKey: 'error.common.revision_conflict' })));
    lifecycle.save();
    expect(api.put).toHaveBeenCalledOnce();
    expect(lifecycle.conflict()).toBe(true);
    expect(lifecycle.canSave()).toBe(false);
    expect(lifecycle.canSeal()).toBe(false);
    api.get.mockImplementation((path) =>
      of(
        path.endsWith('/prototype')
          ? STUDIO_PREVIEW
          : path === STUDIO_DRAFT_PATH
            ? { ...STUDIO_DRAFT, draft: { ...STUDIO_DRAFT.draft!, revision: 2 } }
            : { items: [], nextCursor: null, hasMore: false, totalReturned: 0 },
      ),
    );
    lifecycle.load();
    expect(lifecycle.revision()).toBe(2);
    expect(lifecycle.conflict()).toBe(false);
    expect(lifecycle.canSeal()).toBe(true);
  });
  it('does not overwrite edits made after a save request began', () => {
    lifecycle.load();
    editor.edit((draft) => draft.sections[0].fields.reverse());
    const pending = new Subject<StudioDraftResponse>();
    api.put.mockReturnValue(pending);
    const submitted = structuredClone(editor.draft()!);
    lifecycle.save();
    editor.edit((draft) => {
      draft.sections[0].fields[0].labels = { ru: 'Новое', uz: 'Yangi', en: 'New' };
    });
    const local = structuredClone(editor.draft());
    pending.next({ ...STUDIO_DRAFT, draft: { ...STUDIO_DRAFT.draft!, revision: 2, declaration: submitted } });
    pending.complete();
    expect(editor.draft()).toEqual(local);
    expect(lifecycle.revision()).toBe(2);
    expect(lifecycle.serverDirty()).toBe(true);
  });
  it('seals only a saved revision and keeps the editor independent of the immutable version', () => {
    lifecycle.load();
    api.post.mockReturnValue(of(structuredClone(STUDIO_VERSION)));
    lifecycle.seal();
    expect(api.post).toHaveBeenCalledWith(STUDIO_DRAFT_PATH + '/versions', undefined, {
      notifyError: false,
      ifMatch: 1,
    });
    expect(versions.selected()).toEqual(STUDIO_VERSION);
    expect(editor.draft()).toEqual(STUDIO_PREVIEW.declaration);
    editor.edit((draft) => draft.sections[0].fields.reverse());
    expect(lifecycle.canSeal()).toBe(false);
    lifecycle.seal();
    expect(api.post).toHaveBeenCalledTimes(1);
  });
});
