import { TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { I18nService } from '@core/services/i18n.service';
import { NavigationService } from '@core/services/navigation.service';
import { PermissionService } from '@core/services/permission.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import type { SimpleFormDeclaration } from '../new-form/new-form.models';
import { FormVersionStore } from './form-version.store';

const labels = { ru: 'Поле', uz: 'Maydon', en: 'Field' };

function declaration(): SimpleFormDeclaration {
  return {
    formatVersion: 1,
    kind: 'simple-form',
    module: { code: 'studio', title: labels },
    entity: { name: 'visits', title: { ru: 'Визиты', uz: 'Tashriflar', en: 'Visits' } },
    fields: [
      { id: 'address', type: 'text', labels, required: true, listed: true, maxLength: 100 },
      { id: 'note', type: 'textarea', labels, required: false, listed: false, maxLength: 500 },
    ],
  };
}

function detail(revision = 1, version = 1) {
  return {
    form: { entityCode: 'studio.visits', status: 'active', activeVersion: version, versions: version, revision },
    declaration: declaration(),
    checksum: 'a'.repeat(64),
    versions: [{ version: 1, checksum: 'a'.repeat(64) }],
  };
}

describe('FormVersionStore', () => {
  const api = { get: vi.fn(), post: vi.fn() };
  const modal = { confirm: vi.fn() };
  const navigation = { loadEntityItems: vi.fn() };
  let store: FormVersionStore;

  beforeEach(() => {
    api.get.mockReset().mockReturnValue(of(detail()));
    api.post.mockReset().mockImplementation((url: string, body: unknown) =>
      of(
        url.endsWith('/versions')
          ? detail(2, 2)
          : {
              formatVersion: 1,
              kind: 'simple-form-package',
              declaration: structuredClone(body),
              checksum: 'b'.repeat(64),
            },
      ),
    );
    modal.confirm.mockReset().mockReturnValue(of(true));
    navigation.loadEntityItems.mockReset().mockReturnValue(of([]));
    TestBed.configureTestingModule({
      providers: [
        FormVersionStore,
        { provide: ApiService, useValue: api },
        { provide: SMTModalService, useValue: modal },
        { provide: NavigationService, useValue: navigation },
      ],
    });
    vi.spyOn(TestBed.inject(I18nService), 'refreshLanguage').mockReturnValue(of(undefined));
    TestBed.inject(PermissionService).setPermissions(['studio.forms.publish']);
    store = TestBed.inject(FormVersionStore);
    store.load('studio.visits');
  });

  it('seeds a format 2 draft from the active version and knows the stored fields', () => {
    expect(api.get).toHaveBeenCalledWith('/studio/forms/studio.visits', undefined, { notifyError: false });
    expect(store.draft()?.formatVersion).toBe(2);
    expect(store.storedField('address')?.maxLength).toBe(100);
    expect(store.storedField('region')).toBeNull();
    expect(store.dirty()).toBe(false);
  });

  it('lists the incompatible changes and prepares nothing while they remain', () => {
    store.edit((draft) => {
      draft.fields = draft.fields.filter((field) => field.id !== 'note');
      draft.fields[0].maxLength = 50;
    });
    expect(store.changes()).toEqual([
      { field: 'address', change: 'max_length' },
      { field: 'note', change: 'removed' },
    ]);
    store.prepare();
    expect(api.post).not.toHaveBeenCalled();
  });

  it('prepares and activates a compatible version from the revision read, then refreshes labels and menu', () => {
    store.edit((draft) => {
      draft.fields[1].hidden = true;
      draft.fields.push({ id: 'region', type: 'text', labels, required: false, listed: true, maxLength: 50 });
    });
    expect(store.dirty()).toBe(true);
    store.prepare();
    expect(api.post.mock.calls[0][0]).toBe('/form-studio/new-forms/prepare');
    expect(store.canActivate()).toBe(true);
    store.activate();
    const [url, , options] = api.post.mock.calls[1];
    expect(url).toBe('/studio/forms/studio.visits/versions');
    expect(options).toEqual({ notifyError: false, ifMatch: 1 });
    expect(store.activated()?.form?.activeVersion).toBe(2);
    expect(store.canActivate()).toBe(false);
    expect(navigation.loadEntityItems).toHaveBeenCalled();
  });

  it('keeps the draft on a stale revision, names refused changes and drops a stale confirmation', () => {
    store.edit((draft) => {
      draft.entity.title.ru = 'Визиты 2';
    });
    store.prepare();
    api.post.mockReturnValueOnce(
      throwError(() => ({
        status: 422,
        detail: 'Incompatible',
        errors: [{ field: 'fields.note', code: 'change_type', message: 'type' }],
      })),
    );
    store.activate();
    expect(store.issues()).toEqual([{ path: 'fields.note', message: 'type', change: 'type' }]);
    expect(store.conflict()).toBe(false);
    api.post.mockReturnValueOnce(throwError(() => ({ status: 409, detail: 'Changed' })));
    store.activate();
    expect(store.conflict()).toBe(true);
    expect(store.draft()?.entity.title.ru).toBe('Визиты 2');

    const decision = new Subject<boolean>();
    modal.confirm.mockReturnValue(decision);
    store.activate();
    // The editor is locked while the question is open; a right revoked meanwhile stops the send.
    store.edit((draft) => {
      draft.entity.title.ru = 'Визиты 3';
    });
    expect(store.draft()?.entity.title.ru).toBe('Визиты 2');
    TestBed.inject(PermissionService).clear();
    decision.next(true);
    expect(api.post.mock.calls.filter(([url]) => String(url).endsWith('/versions'))).toHaveLength(2);
  });

  it('reports a load error and stays read-only for an archived form or without the right', () => {
    api.get.mockReturnValueOnce(throwError(() => ({ detail: 'Not found' })));
    store.load('studio.missing');
    expect(store.loadError()).toBe('Not found');
    api.get.mockReturnValueOnce(of({ ...detail(), form: { ...detail().form, status: 'archived' } }));
    store.load('studio.visits');
    store.edit((draft) => {
      draft.entity.title.ru = 'Другое';
    });
    store.prepare();
    expect(store.archived()).toBe(true);
    expect(store.canActivate()).toBe(false);
    TestBed.inject(PermissionService).clear();
    expect(store.mayPublish()).toBe(false);
  });
});
