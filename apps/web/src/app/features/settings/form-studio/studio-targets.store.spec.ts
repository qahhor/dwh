import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { PermissionService } from '@core/services/permission.service';
import { StudioTargetsStore } from './studio-targets.store';

const title = { ru: 'Клиенты', uz: 'Mijozlar', en: 'Clients' };

describe('StudioTargetsStore', () => {
  function setup(permissions: string[], get: ReturnType<typeof vi.fn>) {
    TestBed.configureTestingModule({ providers: [StudioTargetsStore, { provide: ApiService, useValue: { get } }] });
    TestBed.inject(PermissionService).setPermissions(permissions);
    return TestBed.inject(StudioTargetsStore);
  }

  it('offers the readable lists and the served active forms, once', () => {
    const get = vi.fn((path: string) =>
      path === '/studio/lists'
        ? of({ items: [{ code: 'kinds', name: title }] })
        : of({
            items: [
              { entityCode: 'studio.clients', title, status: 'active', quarantined: false },
              { entityCode: 'studio.old', title, status: 'archived', quarantined: false },
              { entityCode: 'studio.held', title, status: 'active', quarantined: true },
            ],
          }),
    );
    const store = setup(['studio.lists.view', 'studio.forms.publish'], get);
    store.load();
    store.load();
    expect(get).toHaveBeenCalledTimes(2);
    expect(store.lists()).toEqual([{ code: 'kinds', labels: title }]);
    expect(store.forms()).toEqual([{ code: 'studio.clients', labels: title }]);
  });

  it('asks nothing without the rights and stays empty on a failure', () => {
    const none = vi.fn();
    setup([], none).load();
    expect(none).not.toHaveBeenCalled();
    TestBed.resetTestingModule();
    const failing = vi.fn(() => throwError(() => ({ status: 500 })));
    const store = setup(['studio.lists.view', 'studio.forms.publish'], failing);
    store.load();
    expect(store.lists()).toEqual([]);
    expect(store.forms()).toEqual([]);
  });
});
