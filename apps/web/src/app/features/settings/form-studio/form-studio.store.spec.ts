import { TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import type { FormStudioPreview } from './form-studio.models';
import { FormStudioStore } from './form-studio.store';

const RESPONSE: FormStudioPreview = {
  declaration: {
    formatVersion: 1,
    kind: 'form-layout',
    entityCode: 'example.products',
    sections: [{ id: 'main', fields: [{ fieldId: 'code' }, { fieldId: 'name' }] }],
  },
  meta: {
    code: 'example.products',
    fields: [
      { key: 'code', labelKey: 'code', type: 'text', required: true, readonlyOnUpdate: true },
      { key: 'name', labelKey: 'name', type: 'text', required: true },
    ],
    layout: [{ key: 'main', labelKey: 'entity.section.main', fields: ['code', 'name'] }],
    actions: ['create'],
    capabilities: [],
  },
};

describe('FormStudioStore', () => {
  let store: FormStudioStore;
  const api = { get: vi.fn(), post: vi.fn() };
  beforeEach(() => {
    api.get.mockReset().mockReturnValue(of(structuredClone(RESPONSE)));
    api.post
      .mockReset()
      .mockImplementation((_path, declaration) =>
        of({ ...structuredClone(RESPONSE), declaration: structuredClone(declaration) }),
      );
    TestBed.configureTestingModule({ providers: [FormStudioStore, { provide: ApiService, useValue: api }] });
    store = TestBed.inject(FormStudioStore);
  });

  it('loads the existing metadata without a data request and keeps its constraints', () => {
    store.load();
    expect(api.get).toHaveBeenCalledWith('/form-studio/prototype', undefined, { notifyError: false });
    expect(store.draft()).toEqual(RESPONSE.declaration);
    expect(store.validated()?.meta.fields[0].readonlyOnUpdate).toBe(true);
    expect(store.dirty()).toBe(false);
  });

  it('edits a separate declaration and invalidates preview and export until the server validates it', () => {
    store.load();
    store.edit((draft) => {
      draft.sections[0].fields.reverse();
    });
    expect(store.draft()?.sections[0].fields.map((field) => field.fieldId)).toEqual(['name', 'code']);
    expect(store.validated()).toBeNull();
    expect(store.exportText()).toBeNull();
    expect(store.dirty()).toBe(true);
    expect(RESPONSE.declaration.sections[0].fields[0].fieldId).toBe('code');
    store.validate();
    expect(api.post).toHaveBeenCalledWith('/form-studio/preview', store.draft(), { notifyError: false });
    expect(store.dirty()).toBe(true);
    expect(store.exportText()).not.toBeNull();
    expect(store.dirty()).toBe(true);
    store.markExported();
    expect(store.dirty()).toBe(false);
  });

  it('clears the download error after a successful retry', () => {
    store.load();
    store.edit((draft) => {
      draft.sections[0].fields.reverse();
    });
    store.validate();
    store.exportFailed();
    expect(store.error()).not.toBe('');
    expect(store.dirty()).toBe(true);
    store.markExported();
    expect(store.dirty()).toBe(false);
    expect(store.error()).toBe('');
  });

  it('keeps the draft after a rejected imported declaration and clears stale export', () => {
    store.load();
    const original = structuredClone(store.draft());
    api.post.mockReturnValue(throwError(() => ({ detail: 'Unknown property' })));
    store.importText('{"sql":"select *"}');
    expect(store.draft()).toEqual(original);
    expect(store.error()).toBe('Unknown property');
    expect(store.validated()).toBeNull();
  });

  it('rejects invalid JSON and UTF-8 bytes above the limit before calling the server', () => {
    store.load();
    store.importText('{');
    expect(store.error()).not.toBe('');
    store.importText('я'.repeat(17000));
    expect(store.error()).not.toBe('');
    expect(api.post).not.toHaveBeenCalled();
    expect(store.draft()).toEqual(RESPONSE.declaration);
  });

  it('sends imported JSON bytes unchanged so the server can reject duplicate properties and decimal versions', async () => {
    store.load();
    api.post.mockReturnValue(
      throwError(() => ({
        detail: 'Invalid declaration',
        invalid_params: [{ name: 'formatVersion', reason: 'Must be an integer token' }],
      })),
    );
    const text = '{"formatVersion":1.0,"kind":"form-layout","kind":"sql"}';
    store.importText(text);
    const body = api.post.mock.calls[0][1] as Blob;
    expect(body).toBeInstanceOf(Blob);
    expect(body.type).toBe('application/json');
    expect(await body.text()).toBe(text);
    expect(store.issues()).toEqual([{ path: 'formatVersion', message: 'Must be an integer token' }]);
  });

  it('replaces an imported draft only after successful server validation', () => {
    store.load();
    const pending = new Subject<FormStudioPreview>();
    api.post.mockReturnValue(pending);
    const imported = structuredClone(RESPONSE);
    imported.declaration.sections[0].fields.reverse();
    store.importText(JSON.stringify(imported.declaration));
    expect(store.draft()).toEqual(RESPONSE.declaration);
    pending.next(imported);
    pending.complete();
    expect(store.draft()).toEqual(imported.declaration);
    expect(JSON.parse(store.exportText()!)).toEqual(imported.declaration);
  });

  it('does not accept a validation response for an older draft', () => {
    store.load();
    const pending = new Subject<FormStudioPreview>();
    api.post.mockReturnValue(pending);
    store.validate();
    store.edit((draft) => {
      draft.sections[0].fields.reverse();
    });
    pending.next(RESPONSE);
    pending.complete();
    expect(store.validated()).toBeNull();
    expect(store.draft()?.sections[0].fields[0].fieldId).toBe('name');
  });

  it('exposes load failure and supports a successful retry', () => {
    api.get.mockReturnValueOnce(throwError(() => ({ detail: 'Module is disabled' })));
    store.load();
    expect(store.error()).toBe('Module is disabled');
    expect(store.draft()).toBeNull();
    expect(store.loading()).toBe(false);
    store.load();
    expect(store.error()).toBe('');
    expect(store.draft()).toEqual(RESPONSE.declaration);
  });
});
