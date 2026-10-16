import { signal } from '@angular/core';
import { AuthService } from '@core/services/auth.service';
import { TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { PermissionService } from '@core/services/permission.service';
import { NewFormAssistantStore } from './new-form-assistant.store';
import { NewFormStore } from './new-form.store';
import { initialSimpleForm, type SimpleFormDeclaration } from './new-form.models';

const suggestion = (): SimpleFormDeclaration => ({
  ...initialSimpleForm(),
  module: { code: 'inventory', title: { ru: 'Склад', uz: 'Ombor', en: 'Inventory' } },
  entity: { name: 'rooms', title: { ru: 'Помещения', uz: 'Xonalar', en: 'Rooms' } },
  fields: [
    {
      id: 'name',
      type: 'text',
      labels: { ru: 'Название', uz: 'Nomi', en: 'Name' },
      listed: true,
      required: true,
      maxLength: 100,
    },
  ],
});

describe('assistant drafts (ADR-0039)', () => {
  const api = { get: vi.fn(), post: vi.fn() };
  const auth = { currentUser: signal<{ id: number } | null>({ id: 1 }), isLoggingOut: signal(false) };
  let store: NewFormAssistantStore;
  let source: NewFormStore;
  let permissions: PermissionService;
  beforeEach(() => {
    auth.currentUser.set({ id: 1 });
    auth.isLoggingOut.set(false);
    api.get.mockReset().mockReturnValue(of({ available: true }));
    api.post.mockReset().mockReturnValue(of(suggestion()));
    TestBed.configureTestingModule({
      providers: [
        NewFormStore,
        NewFormAssistantStore,
        { provide: ApiService, useValue: api },
        { provide: AuthService, useValue: auth },
      ],
    });
    permissions = TestBed.inject(PermissionService);
    source = TestBed.inject(NewFormStore);
    store = TestBed.inject(NewFormAssistantStore);
  });
  function authorize() {
    permissions.setPermissions(['studio.forms.assist', 'md.settings.update']);
    TestBed.tick();
    store.setPrompt('Prepare a room form');
  }
  it('requires both permissions before fetching availability or sending a prompt', () => {
    permissions.setPermissions(['md.settings.update']);
    TestBed.tick();
    store.setPrompt('A form');
    store.load();
    store.generate();
    expect(api.get).not.toHaveBeenCalled();
    expect(api.post).not.toHaveBeenCalled();
    expect(store.mayAssist()).toBe(false);
  });
  it('does not call the provider while disabled', () => {
    api.get.mockReturnValue(of({ available: false }));
    authorize();
    store.generate();
    expect(store.available()).toBe(false);
    expect(api.post).not.toHaveBeenCalled();
  });
  it('counts Unicode code points, accepts 2000 astral characters and rejects 2001', () => {
    authorize();
    store.setPrompt('😀'.repeat(2000));
    expect(store.length()).toBe(2000);
    expect(store.canGenerate()).toBe(true);
    store.setPrompt('😀'.repeat(2001));
    store.generate();
    expect(api.post).not.toHaveBeenCalled();
    store.setPrompt('   ');
    expect(store.canGenerate()).toBe(false);
  });
  it('keeps a checked proposal separate and makes no prepare/save/activation calls', () => {
    authorize();
    const original = source.draft();
    store.generate();
    expect(source.draft()).toBe(original);
    expect(source.prepared()).toBeNull();
    expect(store.proposal()).toEqual(suggestion());
    expect(api.post).toHaveBeenCalledExactlyOnceWith(
      '/studio/assistant/drafts',
      { prompt: 'Prepare a room form' },
      { notifyError: false },
    );
  });
  it('explicit application replaces only the local draft and resets live delivery and package', () => {
    authorize();
    source.chooseDelivery('live');
    source.prepared.set({
      formatVersion: 1,
      kind: 'simple-form-package',
      declaration: source.draft(),
      checksum: 'a'.repeat(64),
    });
    TestBed.tick();
    store.generate();
    expect(store.apply(store.proposal()!)).toBe(true);
    expect(source.delivery()).toBe('cli');
    expect(source.prepared()).toBeNull();
    expect(source.draft()).toEqual(suggestion());
    expect(source.dirty()).toBe(true);
    expect(api.post).toHaveBeenCalledTimes(1);
  });
  it.each([403, 404, 422, 429, 503, 504, 0])(
    'preserves prompt and editor on status %s without auto retry',
    (status) => {
      authorize();
      const original = source.draft();
      api.post.mockReturnValue(throwError(() => ({ status, detail: 'Retry later' })));
      store.generate();
      expect(store.error()).toBe('Retry later');
      expect(store.prompt()).toBe('Prepare a room form');
      expect(source.draft()).toBe(original);
      expect(store.proposal()).toBeNull();
      expect(store.busy()).toBe(false);
      expect(api.post).toHaveBeenCalledTimes(1);
    },
  );
  it('deduplicates clicks and ignores a result after the source changes', () => {
    authorize();
    const pending = new Subject<SimpleFormDeclaration>();
    api.post.mockReturnValue(pending);
    store.generate();
    store.generate();
    expect(api.post).toHaveBeenCalledTimes(1);
    source.edit((draft) => {
      draft.entity.name = 'manual';
    });
    pending.next(suggestion());
    expect(store.proposal()).toBeNull();
    TestBed.tick();
    expect(store.busy()).toBe(false);
    expect(source.draft().entity.name).toBe('manual');
  });
  it('ignores cancelled prompts and permission revocation', () => {
    authorize();
    const pending = new Subject<SimpleFormDeclaration>();
    api.post.mockReturnValue(pending);
    store.generate();
    store.cancel();
    pending.next(suggestion());
    expect(store.proposal()).toBeNull();
    store.generate();
    permissions.clear();
    pending.next(suggestion());
    expect(store.proposal()).toBeNull();
    TestBed.tick();
    expect(store.available()).toBe(false);
    expect(store.busy()).toBe(false);
  });
  it('rejects user A response after user B takes over with identical grants and unchanged draft', () => {
    authorize();
    const pending = new Subject<SimpleFormDeclaration>();
    api.post.mockReturnValue(pending);
    store.generate();
    auth.currentUser.set({ id: 2 });
    pending.next(suggestion());
    expect(store.proposal()).toBeNull();
    TestBed.tick();
    expect(store.prompt()).toBe('');
    expect(store.busy()).toBe(false);
  });
  it('invalidates an outstanding proposal after permissionVersion changes despite identical grants', () => {
    authorize();
    const pending = new Subject<SimpleFormDeclaration>();
    api.post.mockReturnValue(pending);
    store.generate();
    permissions.setPermissions(['studio.forms.assist', 'md.settings.update'], 2);
    pending.next(suggestion());
    expect(store.proposal()).toBeNull();
    TestBed.tick();
    expect(store.prompt()).toBe('Prepare a room form');
  });
  it('keeps user B availability pending when user A response arrives late', () => {
    const a = new Subject<{ available: boolean }>();
    const b = new Subject<{ available: boolean }>();
    api.get.mockReturnValueOnce(a).mockReturnValueOnce(b);
    authorize();
    auth.currentUser.set({ id: 2 });
    TestBed.tick();
    expect(api.get).toHaveBeenCalledTimes(2);
    a.next({ available: true });
    a.complete();
    expect(store.available()).toBe(false);
    expect(store.checking()).toBe(true);
    b.next({ available: false });
    b.complete();
    expect(store.available()).toBe(false);
    expect(store.checking()).toBe(false);
  });

  it('invalidates a proposal when the author changes their request', () => {
    authorize();
    store.generate();
    const proposal = store.proposal()!;
    store.setPrompt('Different form');
    expect(store.proposal()).toBeNull();
    expect(store.apply(proposal)).toBe(false);
  });
});
