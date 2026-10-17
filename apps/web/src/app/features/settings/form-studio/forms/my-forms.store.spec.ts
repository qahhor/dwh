import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { NavigationService } from '@core/services/navigation.service';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { MyFormsStore, type StudioFormSummary } from './my-forms.store';

function summary(code: string, change: Partial<StudioFormSummary> = {}): StudioFormSummary {
  return {
    entityCode: code,
    title: { ru: 'Визиты', uz: 'Tashriflar', en: 'Visits' },
    status: 'active',
    activeVersion: 1,
    versions: 1,
    records: 3,
    quarantined: false,
    broken: null,
    revision: 1,
    createdAt: '2026-10-17T08:00:00Z',
    modifiedAt: '2026-10-17T08:00:00Z',
    ...change,
  };
}

describe('MyFormsStore', () => {
  const api = { get: vi.fn(), post: vi.fn() };
  const modal = { confirm: vi.fn() };
  const navigation = { loadEntityItems: vi.fn() };
  const toast = { success: vi.fn(), error: vi.fn() };
  let store: MyFormsStore;
  let permissions: PermissionService;

  beforeEach(() => {
    api.get
      .mockReset()
      .mockReturnValue(of({ items: [summary('studio.visits')], hasMore: true, nextCursor: 'next', totalEstimated: 1 }));
    api.post.mockReset().mockReturnValue(of(summary('studio.visits', { status: 'archived', revision: 2 })));
    modal.confirm.mockReset().mockReturnValue(of(true));
    navigation.loadEntityItems.mockReset().mockReturnValue(of([]));
    toast.success.mockReset();
    TestBed.configureTestingModule({
      providers: [
        MyFormsStore,
        { provide: ApiService, useValue: api },
        { provide: SMTModalService, useValue: modal },
        { provide: NavigationService, useValue: navigation },
        { provide: ToastService, useValue: toast },
      ],
    });
    permissions = TestBed.inject(PermissionService);
    permissions.setPermissions(['studio.forms.publish']);
    store = TestBed.inject(MyFormsStore);
  });

  it('loads a page of forms and the next page from its cursor', () => {
    store.load();
    expect(api.get).toHaveBeenCalledWith('/studio/forms', { limit: 50 }, { notifyError: false });
    expect(store.forms()).toHaveLength(1);
    expect(store.nextCursor()).toBe('next');
    api.get.mockReturnValueOnce(of({ items: [summary('studio.orders')], hasMore: false }));
    store.loadMore();
    expect(api.get).toHaveBeenLastCalledWith('/studio/forms', { limit: 50, cursor: 'next' }, { notifyError: false });
    expect(store.forms().map((form) => form.entityCode)).toEqual(['studio.visits', 'studio.orders']);
    expect(store.nextCursor()).toBeNull();
  });

  it('reads nothing without the publisher right and keeps a load error', () => {
    permissions.clear();
    store.load();
    expect(api.get).not.toHaveBeenCalled();
    permissions.setPermissions(['studio.forms.publish']);
    api.get.mockReturnValueOnce(throwError(() => ({ detail: 'Unavailable' })));
    store.load();
    expect(store.error()).toBe('Unavailable');
  });

  it('archives from the revision seen after a confirmation and refreshes the menu', () => {
    store.load();
    store.changeLifecycle(store.forms()[0], true);
    expect(modal.confirm).toHaveBeenCalledWith(expect.objectContaining({ destructive: true }));
    expect(api.post).toHaveBeenCalledWith('/studio/forms/studio.visits/archive', null, {
      notifyError: false,
      ifMatch: 1,
    });
    expect(store.forms()[0].status).toBe('archived');
    expect(toast.success).toHaveBeenCalled();
    expect(navigation.loadEntityItems).toHaveBeenCalled();
  });

  it('restores only after a confirmation and reloads the list when the revision is stale', () => {
    store.load();
    modal.confirm.mockReturnValueOnce(of(false));
    store.changeLifecycle(store.forms()[0], false);
    expect(api.post).not.toHaveBeenCalled();
    api.post.mockReturnValueOnce(throwError(() => ({ status: 409, detail: 'Changed meanwhile' })));
    store.changeLifecycle(store.forms()[0], false);
    expect(api.post).toHaveBeenCalledWith('/studio/forms/studio.visits/restore', null, {
      notifyError: false,
      ifMatch: 1,
    });
    expect(store.actionError()).toBe('Changed meanwhile');
    expect(api.get).toHaveBeenCalledTimes(2);
    expect(store.changing()).toBeNull();
  });

  it('names a form in the language of the interface', () => {
    expect(store.title(summary('studio.visits'))).toBe('Визиты');
  });
});
