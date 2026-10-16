import { TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { AuthService } from '@core/services/auth.service';
import { I18nService } from '@core/services/i18n.service';
import { ModuleService } from '@core/services/module.service';
import { NavigationService } from '@core/services/navigation.service';
import { PermissionService } from '@core/services/permission.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { NewFormActivationStore } from './new-form-activation.store';
import { NewFormStore } from './new-form.store';
import type { SimpleFormPackage } from './new-form.models';

describe('NewFormActivationStore', () => {
  const api = { get: vi.fn(), post: vi.fn() };
  const modal = { confirm: vi.fn() };
  const auth = { refreshMe: vi.fn() };
  const modules = { loadActiveModules: vi.fn() };
  const navigation = { loadEntityItems: vi.fn() };
  let source: NewFormStore;
  let live: NewFormActivationStore;
  let permissions: PermissionService;
  beforeEach(() => {
    api.get.mockReset().mockReturnValue(of({ enabled: true, namespace: 'studio', maxForms: 100 }));
    api.post.mockReset().mockImplementation((url, body) =>
      of(
        url === '/studio/forms'
          ? { entityCode: 'studio.locations', checksum: 'a'.repeat(64) }
          : {
              formatVersion: 1,
              kind: 'simple-form-package',
              declaration: structuredClone(body),
              checksum: 'a'.repeat(64),
            },
      ),
    );
    modal.confirm.mockReset().mockReturnValue(of(true));
    auth.refreshMe.mockReset().mockReturnValue(of(undefined));
    modules.loadActiveModules.mockReset().mockReturnValue(of([]));
    navigation.loadEntityItems.mockReset().mockReturnValue(of([]));
    TestBed.configureTestingModule({
      providers: [
        NewFormStore,
        NewFormActivationStore,
        { provide: ApiService, useValue: api },
        { provide: SMTModalService, useValue: modal },
        { provide: AuthService, useValue: auth },
        { provide: ModuleService, useValue: modules },
        { provide: NavigationService, useValue: navigation },
      ],
    });
    vi.spyOn(TestBed.inject(I18nService), 'refreshLanguage').mockReturnValue(of(undefined));
    permissions = TestBed.inject(PermissionService);
    permissions.setPermissions(['studio.forms.publish']);
    source = TestBed.inject(NewFormStore);
    live = TestBed.inject(NewFormActivationStore);
    source.edit((draft) => {
      draft.entity = { name: 'locations', title: { ru: 'Места', uz: 'Joylar', en: 'Locations' } };
    });
  });
  function ready(): SimpleFormPackage {
    source.chooseDelivery('live');
    source.prepare();
    live.loadAvailability();
    return source.prepared()!;
  }
  it('requires a current live package, permission and enabled installation before activation', () => {
    live.activate();
    expect(modal.confirm).not.toHaveBeenCalled();
    ready();
    expect(live.canActivate()).toBe(true);
    permissions.clear();
    expect(live.canActivate()).toBe(false);
    live.activate();
    expect(api.post.mock.calls.filter(([url]) => url === '/studio/forms')).toHaveLength(0);
    expect(live.mayPublish()).toBe(false);
  });
  it('does not read activation availability without the separate publish right', () => {
    source.chooseDelivery('live');
    permissions.clear();
    live.loadAvailability();
    expect(api.get).not.toHaveBeenCalled();
    expect(live.canActivate()).toBe(false);
  });
  it('does not activate a disabled installation or unexpected namespace', () => {
    ready();
    for (const result of [
      { enabled: false, namespace: 'studio' },
      { enabled: true, namespace: 'other' },
    ]) {
      api.get.mockReturnValue(of({ ...result, maxForms: 100 }));
      live.loadAvailability();
      expect(live.canActivate()).toBe(false);
    }
  });
  it('allows an availability retry after an error and preserves the source', () => {
    const prepared = ready();
    api.get.mockReturnValueOnce(throwError(() => ({ detail: 'Status unavailable' })));
    live.loadAvailability();
    expect(live.availabilityError()).toBe('Status unavailable');
    expect(live.canActivate()).toBe(false);
    expect(source.prepared()).toEqual(prepared);
    live.loadAvailability();
    expect(live.canActivate()).toBe(true);
    expect(live.availabilityError()).toBe('');
  });
  it('cancels the confirmation without sending a package', () => {
    ready();
    modal.confirm.mockReturnValue(of(false));
    live.activate();
    expect(live.busy()).toBe(false);
    expect(live.result()).toBeNull();
    expect(api.post.mock.calls.filter(([url]) => url === '/studio/forms')).toHaveLength(0);
  });
  it('refuses a stale confirmation after the source or permission changes', () => {
    ready();
    const decision = new Subject<boolean>();
    modal.confirm.mockReturnValue(decision);
    live.activate();
    source.edit((draft) => {
      draft.entity.name = 'changed';
    });
    decision.next(true);
    decision.complete();
    expect(api.post.mock.calls.filter(([url]) => url === '/studio/forms')).toHaveLength(0);
    expect(live.result()).toBeNull();
  });
  it('rechecks the publication right after confirmation was opened', () => {
    ready();
    const decision = new Subject<boolean>();
    modal.confirm.mockReturnValue(decision);
    live.activate();
    permissions.clear();
    decision.next(true);
    decision.complete();
    expect(api.post.mock.calls.filter(([url]) => url === '/studio/forms')).toHaveLength(0);
    expect(live.result()).toBeNull();
  });
  it('posts once, locks edits and opens only the exact successful form', async () => {
    const prepared = ready();
    const response = new Subject<{ entityCode: string; checksum: string }>();
    api.post.mockReturnValue(response);
    live.activate();
    live.activate();
    expect(live.busy()).toBe(true);
    source.edit((draft) => {
      draft.entity.name = 'changed';
    });
    source.chooseDelivery('cli');
    expect(source.draft()).toEqual(prepared.declaration);
    expect(source.delivery()).toBe('live');
    expect(api.post.mock.calls.filter(([url]) => url === '/studio/forms')).toHaveLength(1);
    const body = api.post.mock.calls.find(([url]) => url === '/studio/forms')![1] as Blob;
    expect(JSON.parse(await body.text())).toEqual(prepared);
    response.next({ entityCode: 'studio.locations', checksum: prepared.checksum });
    response.complete();
    expect(live.result()?.entityCode).toBe('studio.locations');
    expect(source.dirty()).toBe(false);
    expect(live.busy()).toBe(false);
    expect(source.locked()).toBe(false);
    expect(modules.loadActiveModules).toHaveBeenCalledTimes(1);
    expect(navigation.loadEntityItems).toHaveBeenCalledTimes(1);
    expect(TestBed.inject(I18nService).refreshLanguage).toHaveBeenCalledWith('ru');
    expect(permissions.hasPermission('studio.locations', 'view')).toBe(false);
  });
  it.each([403, 409, 0])('keeps the exact package after activation failure %s for safe retry', (status) => {
    const prepared = ready();
    api.post.mockReturnValueOnce(throwError(() => ({ status, detail: 'Activation refused' })));
    live.activate();
    expect(source.prepared()).toEqual(prepared);
    expect(source.draft()).toEqual(prepared.declaration);
    expect(source.dirty()).toBe(true);
    expect(live.error()).toBe('Activation refused');
    expect(live.busy()).toBe(false);
    expect(live.result()).toBeNull();
    live.activate();
    expect(live.result()?.entityCode).toBe('studio.locations');
    expect(live.error()).toBe('');
  });
  it('keeps activation success when navigation refresh fails and retries only refresh', () => {
    ready();
    navigation.loadEntityItems.mockReturnValueOnce(throwError(() => new Error('Network')));
    live.activate();
    expect(live.result()?.entityCode).toBe('studio.locations');
    expect(live.refreshError()).not.toBe('');
    expect(source.dirty()).toBe(false);
    live.refresh();
    expect(live.refreshError()).toBe('');
    expect(api.post.mock.calls.filter(([url]) => url === '/studio/forms')).toHaveLength(1);
  });
  it('ignores a successful response for another package and retains the source', () => {
    const prepared = ready();
    api.post.mockReturnValue(of({ entityCode: 'studio.other', checksum: prepared.checksum }));
    live.activate();
    expect(live.result()).toBeNull();
    expect(source.dirty()).toBe(true);
    expect(source.prepared()).toEqual(prepared);
    expect(live.error()).not.toBe('');
  });
});
