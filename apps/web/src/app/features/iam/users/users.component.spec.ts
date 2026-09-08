import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { of, Subject } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../../core/services/api.service';
import { PermissionService } from '../../../core/services/permission.service';
import { ToastService } from '../../../core/services/toast.service';
import { User } from '../../../core/models/auth.models';
import { I18nService } from '../../../core/services/i18n.service';
import { signal } from '@angular/core';
import { UsersComponent } from './users.component';
import { translateTest } from '../../../../testing/i18n-test.stub';
import { UserOrgUnitsPanelComponent } from '../org-units/public-api';

describe('UsersComponent UI contracts', () => {
  async function createFixture() {
    await TestBed.configureTestingModule({
      imports: [UsersComponent],
      providers: [
        {
          provide: ApiService,
          useValue: {
            get: vi.fn((path: string) => of(path === '/iam/users'
              ? { items: [], nextCursor: null, hasMore: false }
              : [])),
            post: vi.fn(() => of({})),
            patch: vi.fn(() => of({})),
            put: vi.fn(() => of({})),
            delete: vi.fn(() => of({}))
          }
        },
        {
          provide: I18nService,
          useValue: {
            languages: signal([
              { code: 'ru', name: 'Русский', active: true },
              { code: 'de', name: 'Deutsch', active: true },
              { code: 'tr', name: 'Türkçe', active: true }
            ]),
            translate: translateTest
          }
        },
        {
          provide: PermissionService,
          useValue: {
            canCreate: () => true,
            canUpdate: () => true,
            canDelete: () => true,
            hasPermission: () => true
          }
        },
        { provide: ToastService, useValue: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } }
      ]
    }).compileComponents();
    const fixture = TestBed.createComponent(UsersComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('labels filters and exposes explicit table interactions', async () => {
    const fixture = await createFixture();
    const user: User = {
      id: 7,
      name: 'Анна Иванова',
      login: 'anna',
      email: 'anna@example.test',
      state: 'A',
      language: 'ru',
      timezone: 'Asia/Tashkent',
      attributes: {},
      is2faEnabled: false,
      forcePasswordChange: false,
      createdAt: '2026-08-30T00:00:00Z',
      modifiedAt: '2026-08-30T00:00:00Z'
    };
    fixture.componentInstance.users.set([user]);
    fixture.detectChanges();

    const search = fixture.nativeElement.querySelector('#user-search') as HTMLInputElement;
    const region = fixture.nativeElement.querySelector('.table-container[role="region"]') as HTMLElement;
    const identity = fixture.nativeElement.querySelector('.user-identity') as HTMLElement;

    expect(fixture.nativeElement.querySelector(`label[for="${search.id}"]`)).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[role="group"][aria-label="Фильтр пользователей по статусу"]')).not.toBeNull();
    expect(region.tabIndex).toBe(0);
    expect(region.querySelector('table')?.getAttribute('aria-label')).toBe('Список пользователей');
    expect(identity.tagName).toBe('BUTTON');
    expect(fixture.nativeElement.querySelector('button[aria-label="Редактировать пользователя Анна Иванова"]')).not.toBeNull();
  });

  it('connects required create-user fields to inline validation', async () => {
    const fixture = await createFixture();
    fixture.componentInstance.openCreateModal();
    (fixture.componentInstance as any).isCreateSubmitted = true;
    fixture.detectChanges();

    const name = fixture.nativeElement.querySelector('#user-create-name') as HTMLInputElement;
    const password = fixture.nativeElement.querySelector('#user-create-password') as HTMLInputElement;

    expect(fixture.nativeElement.querySelector(`label[for="${name.id}"]`)).not.toBeNull();
    expect(name.required).toBe(true);
    expect(name.getAttribute('aria-invalid')).toBe('true');
    expect(name.getAttribute('aria-describedby')).toBe('user-create-name-error');
    expect(password.required).toBe(true);
    expect(fixture.nativeElement.querySelector('button[aria-label="Показать пароль"]')).not.toBeNull();
    const language = fixture.nativeElement.querySelector('#user-create-language') as HTMLSelectElement;
    expect(Array.from(language.options).map(option => option.value)).toEqual(['ru', 'de', 'tr']);
  });

  it('keeps a dirty organization draft mounted until Escape or record selection is decided', async () => {
    const fixture = await createFixture();
    const api = TestBed.inject(ApiService) as unknown as { get: ReturnType<typeof vi.fn> };
    const first = user(7, 'Анна');
    const second = user(8, 'Борис');
    api.get.mockImplementation((path: string) => of(orgResponse(path, first)));

    fixture.componentInstance.openViewModal(first);
    fixture.detectChanges();
    const panel = fixture.debugElement.query(By.directive(UserOrgUnitsPanelComponent)).componentInstance as UserOrgUnitsPanelComponent;
    (fixture.nativeElement.querySelector('[data-check="2"]') as HTMLInputElement).click();
    fixture.detectChanges();
    expect(panel.hasUnsavedWork()).toBe(true);
    expect(fixture.nativeElement.querySelector('.expand[aria-expanded="true"]')).not.toBeNull();

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    fixture.detectChanges();
    expect(fixture.componentInstance.viewingUser?.id).toBe(first.id);
    expect(fixture.componentInstance.isViewModalOpen()).toBe(true);
    expect(panel.discard.open()).toBe(true);
    expect(fixture.nativeElement.querySelectorAll('[role="dialog"]')).toHaveLength(2);

    panel.discard.cancel();
    fixture.componentInstance.openViewModal(second);
    fixture.detectChanges();
    expect(fixture.componentInstance.viewingUser?.id).toBe(first.id);
    expect(panel.discard.open()).toBe(true);

    panel.discard.confirm();
    fixture.detectChanges();
    expect(fixture.componentInstance.viewingUser?.id).toBe(second.id);
    expect(fixture.debugElement.query(By.directive(UserOrgUnitsPanelComponent)).componentInstance.userId).toBe(second.id);
  });

  it('rejects record replacement while assignment save is pending and ignores a leave decision after destruction', async () => {
    const fixture = await createFixture();
    const api = TestBed.inject(ApiService) as unknown as {
      get: ReturnType<typeof vi.fn>;
      put: ReturnType<typeof vi.fn>;
    };
    const write = new Subject<void>();
    const first = user(7, 'Анна');
    const second = user(8, 'Борис');
    api.get.mockImplementation((path: string) => of(orgResponse(path, first)));
    api.put.mockReturnValue(write.asObservable());

    fixture.componentInstance.openViewModal(first);
    fixture.detectChanges();
    const panel = fixture.debugElement.query(By.directive(UserOrgUnitsPanelComponent)).componentInstance as UserOrgUnitsPanelComponent;
    (fixture.nativeElement.querySelector('[data-check="2"]') as HTMLInputElement).click();
    panel.save();
    fixture.componentInstance.openViewModal(second);
    fixture.detectChanges();
    expect(panel.pending).toBe(true);
    expect(fixture.componentInstance.viewingUser?.id).toBe(first.id);
    expect(panel.discard.open()).toBe(false);

    write.error({ status: 409, detail: 'retry' });
    fixture.componentInstance.openViewModal(second);
    expect(panel.discard.open()).toBe(true);
    fixture.destroy();
    panel.discard.confirm();
    expect(fixture.componentInstance.viewingUser?.id).toBe(first.id);
  });

  it('mounts the organization panel for a safe deep-linked user record', async () => {
    const fixture = await createFixture();
    const api = TestBed.inject(ApiService) as unknown as { get: ReturnType<typeof vi.fn> };
    const first = user(7, 'Анна');
    api.get.mockImplementation((path: string) => of(orgResponse(path, first)));

    fixture.componentInstance.loadRecordView('7');
    fixture.detectChanges();

    const panel = fixture.debugElement.query(By.directive(UserOrgUnitsPanelComponent)).componentInstance as UserOrgUnitsPanelComponent;
    expect(fixture.componentInstance.routeRecordId()).toBe('7');
    expect(panel.userId).toBe(7);
  });

  it('guards a same-ID deep-link reload before clearing its dirty panel', async () => {
    const fixture = await createFixture();
    const api = TestBed.inject(ApiService) as unknown as { get: ReturnType<typeof vi.fn> };
    const first = user(7, 'Анна');
    api.get.mockImplementation((path: string) => of(orgResponse(path, first)));
    fixture.componentInstance.loadRecordView('7');
    fixture.detectChanges();
    const panel = fixture.debugElement.query(By.directive(UserOrgUnitsPanelComponent)).componentInstance as UserOrgUnitsPanelComponent;
    (fixture.nativeElement.querySelector('[data-check="2"]') as HTMLInputElement).click();
    const readsBeforeReload = api.get.mock.calls.filter(([path]) => path === '/iam/users/7').length;

    fixture.componentInstance.openViewModal(first);

    expect(fixture.componentInstance.viewingUser?.id).toBe(first.id);
    expect(panel.discard.open()).toBe(true);
    expect(api.get.mock.calls.filter(([path]) => path === '/iam/users/7')).toHaveLength(readsBeforeReload);
    panel.discard.cancel();
    expect(panel.hasUnsavedWork()).toBe(true);
  });

  it('guards a direct deep-link reload requested while the mounted organization panel is dirty', async () => {
    const fixture = await createFixture();
    const api = TestBed.inject(ApiService) as unknown as { get: ReturnType<typeof vi.fn> };
    const first = user(7, 'Анна');
    api.get.mockImplementation((path: string) => of(orgResponse(path, first)));
    fixture.componentInstance.loadRecordView('7');
    fixture.detectChanges();
    const panel = fixture.debugElement.query(By.directive(UserOrgUnitsPanelComponent)).componentInstance as UserOrgUnitsPanelComponent;
    (fixture.nativeElement.querySelector('[data-check="2"]') as HTMLInputElement).click();
    const readsBeforeReload = api.get.mock.calls.filter(([path]) => path === '/iam/users/7').length;

    fixture.componentInstance.closeEditModal();

    expect(panel.discard.open()).toBe(true);
    expect(panel.hasUnsavedWork()).toBe(true);
    expect(fixture.componentInstance.viewingUser?.id).toBe(first.id);
    expect(api.get.mock.calls.filter(([path]) => path === '/iam/users/7')).toHaveLength(readsBeforeReload);
  });

  it.each(['dirty', 'pending'] as const)(
    'does not let a delayed profile save replace a newer %s organization panel after edit cancellation',
    async panelState => {
      const fixture = await createFixture();
      const api = TestBed.inject(ApiService) as unknown as {
        get: ReturnType<typeof vi.fn>;
        patch: ReturnType<typeof vi.fn>;
        put: ReturnType<typeof vi.fn>;
      };
      const profileSave = new Subject<void>();
      const assignmentSave = new Subject<void>();
      const first = user(7, 'Анна');
      api.get.mockImplementation((path: string) => of(orgResponse(path, first)));
      api.patch.mockReturnValue(profileSave.asObservable());
      api.put.mockReturnValue(assignmentSave.asObservable());
      fixture.componentInstance.loadRecordView('7');
      fixture.detectChanges();

      fixture.componentInstance.openEditFromView();
      fixture.componentInstance.submitEditUser();
      fixture.componentInstance.closeEditModal();
      fixture.detectChanges();
      const newerPanel = fixture.debugElement.query(By.directive(UserOrgUnitsPanelComponent)).componentInstance as UserOrgUnitsPanelComponent;
      (fixture.nativeElement.querySelector('[data-check="2"]') as HTMLInputElement).click();
      if (panelState === 'pending') newerPanel.save();
      const readsBeforeProfileSettlement = api.get.mock.calls.filter(([path]) => path === '/iam/users/7').length;

      profileSave.next();
      profileSave.complete();
      fixture.detectChanges();

      expect(fixture.debugElement.query(By.directive(UserOrgUnitsPanelComponent)).componentInstance).toBe(newerPanel);
      expect(fixture.componentInstance.viewingUser?.id).toBe(first.id);
      expect(api.get.mock.calls.filter(([path]) => path === '/iam/users/7')).toHaveLength(readsBeforeProfileSettlement);
      expect(newerPanel.pending).toBe(panelState === 'pending');
      expect(newerPanel.hasUnsavedWork()).toBe(true);
    }
  );

  it('keeps the shared submitting state owned by the newest edit save', async () => {
    const fixture = await createFixture();
    const api = TestBed.inject(ApiService) as unknown as {
      get: ReturnType<typeof vi.fn>;
      patch: ReturnType<typeof vi.fn>;
    };
    const firstSave = new Subject<void>();
    const newerSave = new Subject<void>();
    const first = user(7, 'Анна');
    api.get.mockImplementation((path: string) => of(orgResponse(path, first)));
    api.patch.mockReturnValueOnce(firstSave.asObservable()).mockReturnValueOnce(newerSave.asObservable());
    fixture.componentInstance.loadRecordView('7');
    fixture.detectChanges();

    fixture.componentInstance.openEditFromView();
    fixture.componentInstance.submitEditUser();
    fixture.componentInstance.closeEditModal();
    fixture.detectChanges();
    fixture.componentInstance.openEditFromView();
    fixture.componentInstance.submitEditUser();

    firstSave.next();

    expect(fixture.componentInstance.isSubmitting()).toBe(true);
    expect(fixture.componentInstance.isEditModalOpen()).toBe(true);
    expect(fixture.componentInstance.editingUser?.id).toBe(first.id);

    newerSave.next();
    expect(fixture.componentInstance.isSubmitting()).toBe(false);
    expect(fixture.componentInstance.isEditModalOpen()).toBe(false);
  });

  function user(id: number, name: string): User {
    return {
      id, name, login: name.toLowerCase(), email: `${name.toLowerCase()}@example.test`, state: 'A',
      language: 'ru', timezone: 'Asia/Tashkent', attributes: {}, roleIds: [], is2faEnabled: false,
      forcePasswordChange: false, createdAt: '2026-08-30T00:00:00Z', modifiedAt: '2026-08-30T00:00:00Z'
    };
  }

  function orgResponse(path: string, selected: User): unknown {
    if (path === `/iam/users/${selected.id}`) return selected;
    if (path === '/iam/org-units') return [
      { id: 1, parentId: null, code: 'ROOT', name: 'Компания', kind: 'company', state: 'A', orderNo: 0, createdAt: '', modifiedAt: '' },
      { id: 2, parentId: 1, code: 'OPS', name: 'Операции', kind: 'department', state: 'A', orderNo: 0, createdAt: '', modifiedAt: '' }
    ];
    const assignments = path.match(/^\/iam\/org-units\/users\/(\d+)$/);
    if (assignments) return { userId: Number(assignments[1]), orgUnitIds: [], legacyOrgUnitId: null };
    if (/^\/iam\/org-units\/users\/\d+\/scope$/.test(path)) return { rule: 'ALL', visibleOrgUnitIds: [] };
    return [];
  }
});
