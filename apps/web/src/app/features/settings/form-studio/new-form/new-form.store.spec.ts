import { TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { NewFormStore } from './new-form.store';
import type { SimpleFormDeclaration, SimpleFormPackage } from './new-form.models';
const draft: SimpleFormDeclaration = {
  formatVersion: 1,
  kind: 'simple-form',
  module: { code: 'inventory', title: { ru: 'Склад', uz: 'Ombor', en: 'Inventory' } },
  entity: { name: 'locations', title: { ru: 'Места', uz: 'Joylar', en: 'Locations' } },
  fields: [
    {
      id: 'capacity',
      type: 'number',
      labels: { ru: 'Объём', uz: 'Hajm', en: 'Capacity' },
      required: false,
      listed: true,
      scale: 2,
    },
  ],
};
const packageOf = (declaration = draft): SimpleFormPackage => ({
  formatVersion: 1,
  kind: 'simple-form-package',
  declaration: structuredClone(declaration),
  checksum: 'a'.repeat(64),
});
describe('NewFormStore', () => {
  const api = { post: vi.fn() };
  let store: NewFormStore;
  beforeEach(() => {
    api.post.mockReset().mockImplementation((_url, body) => of(packageOf(body)));
    TestBed.configureTestingModule({ providers: [NewFormStore, { provide: ApiService, useValue: api }] });
    store = TestBed.inject(NewFormStore);
  });
  const fill = () => store.edit((value) => Object.assign(value, structuredClone(draft)));
  it('restores the CLI module after choosing activation without losing field edits', () => {
    fill();
    store.prepare();
    store.chooseDelivery('live');
    expect(store.delivery()).toBe('live');
    expect(store.prepared()).toBeNull();
    expect(store.draft().module).toEqual({
      code: 'studio',
      title: { ru: 'Созданные формы', uz: 'Yaratilgan shakllar', en: 'Custom forms' },
    });
    store.edit((value) => {
      value.entity.name = 'warehouses';
    });
    store.chooseDelivery('cli');
    expect(store.draft().module).toEqual(draft.module);
    expect(store.draft().entity.name).toBe('warehouses');
    expect(store.draft().fields).toEqual(draft.fields);
  });
  it('starts without module requests and prepares only the current declaration', () => {
    expect(store.dirty()).toBe(false);
    fill();
    expect(store.dirty()).toBe(true);
    store.prepare();
    expect(api.post).toHaveBeenCalledWith('/form-studio/new-forms/prepare', draft, { notifyError: false });
    expect(store.prepared()).toEqual(packageOf());
    expect(store.dirty()).toBe(true);
    store.markDownloaded();
    expect(store.dirty()).toBe(false);
  });
  it('invalidates the package when input changes and keeps the downloaded source separate', () => {
    fill();
    store.prepare();
    store.markDownloaded();
    store.edit((value) => {
      value.fields[0].required = true;
    });
    expect(store.prepared()).toBeNull();
    expect(store.dirty()).toBe(true);
    expect(draft.fields[0].required).toBe(false);
  });
  it('retains input and field errors on server refusal, then allows retry', () => {
    fill();
    api.post.mockReturnValueOnce(
      throwError(() => ({ detail: 'Bad field', invalid_params: [{ name: 'fields[0].id', reason: 'Reserved' }] })),
    );
    store.prepare();
    expect(store.draft()).toEqual(draft);
    expect(store.error()).toBe('Bad field');
    expect(store.issues()).toEqual([{ path: 'fields[0].id', message: 'Reserved' }]);
    expect(store.busy()).toBe(false);
    store.prepare();
    expect(store.prepared()).toEqual(packageOf());
    expect(store.error()).toBe('');
  });
  it('cancels a pending response when the user edits the source', () => {
    fill();
    const pending = new Subject<SimpleFormPackage>();
    api.post.mockReturnValue(pending);
    store.prepare();
    expect(store.busy()).toBe(true);
    store.edit((value) => {
      value.entity.name = 'changed';
    });
    expect(pending.observed).toBe(false);
    expect(store.busy()).toBe(false);
    pending.next(packageOf());
    expect(store.draft().entity.name).toBe('changed');
    expect(store.prepared()).toBeNull();
  });
  it('imports raw declaration bytes unchanged and adopts only a successful response', async () => {
    fill();
    const original = store.draft();
    const pending = new Subject<SimpleFormPackage>();
    api.post.mockReturnValue(pending);
    const raw = '{"formatVersion":1,"formatVersion":1.0}';
    store.importText(raw);
    const body = api.post.mock.calls[0][1] as Blob;
    expect(await body.text()).toBe(raw);
    expect(store.draft()).toBe(original);
    pending.error({ detail: 'Duplicate key' });
    expect(store.draft()).toBe(original);
    expect(store.error()).toBe('Duplicate key');
  });
  it('restores an incomplete draft without preparing a release package', () => {
    const incomplete = { ...structuredClone(draft), module: { ...draft.module, code: '' } };
    api.post.mockReturnValue(of(incomplete));
    store.importText(JSON.stringify(incomplete));
    expect(api.post).toHaveBeenCalledWith('/form-studio/new-forms/check-draft', expect.any(Blob), {
      notifyError: false,
    });
    expect(store.draft()).toEqual(incomplete);
    expect(store.prepared()).toBeNull();
    expect(store.dirty()).toBe(true);
  });
  it('imports the declaration without persisting its delivery choice', () => {
    fill();
    store.chooseDelivery('live');
    api.post.mockReturnValue(of(structuredClone(draft)));
    store.importText(JSON.stringify(draft));
    expect(store.delivery()).toBe('cli');
    expect(store.draft()).toEqual(draft);
    expect(store.prepared()).toBeNull();
  });
  it('rejects malformed and oversized imports without replacing the draft', () => {
    fill();
    store.importText('{');
    expect(store.error()).not.toBe('');
    store.importText('я'.repeat(17000));
    expect(api.post).not.toHaveBeenCalled();
    expect(store.draft()).toEqual(draft);
  });
  it('cancels previous validation when a replacement import starts', () => {
    fill();
    const old = new Subject<SimpleFormPackage>();
    api.post
      .mockReturnValueOnce(old)
      .mockReturnValueOnce(of({ ...draft, entity: { ...draft.entity, name: 'newitems' } }));
    store.prepare();
    store.importText(JSON.stringify(draft));
    expect(old.observed).toBe(false);
    old.next(packageOf());
    expect(store.draft().entity.name).toBe('newitems');
  });
  it('clears a failed download only after retry and retains dirty input', () => {
    fill();
    store.prepare();
    store.downloadFailed();
    expect(store.error()).not.toBe('');
    expect(store.prepared()).not.toBeNull();
    expect(store.dirty()).toBe(true);
    store.markDownloaded();
    expect(store.error()).toBe('');
    expect(store.dirty()).toBe(false);
  });
  it('cancels validation when the injector is destroyed', () => {
    const pending = new Subject<SimpleFormPackage>();
    api.post.mockReturnValue(pending);
    store.prepare();
    TestBed.resetTestingModule();
    expect(pending.observed).toBe(false);
  });
});
