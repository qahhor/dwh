import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { of, Subject } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { User } from '@core/models/auth.models';
import { QueryListMeta } from '@core/models/query-meta.models';
import { ApiService } from '@core/services/api.service';
import { I18nService } from '@core/services/i18n.service';
import { PermissionService } from '@core/services/permission.service';
import { QueryMetaService } from '@core/services/query-meta.service';
import { ToastService } from '@core/services/toast.service';
import { ListViewsApi } from '@shared/list-views/list-views';
import { translateTest } from '@testing/i18n-test.stub';
import { inScreen, redraw } from '@testing/in-screen';
import { UserOrgUnitsPanelComponent } from '../org-units/public-api';
import { UsersComponent } from './users.component';

const name = { key: 'name', labelKey: 'iam.users.col.name', type: 'text', ops: ['eq'], sortable: true };
/** What `query-meta/iam.users` answers: the name column is enough for the page. */
const USERS_META = { code: 'iam.users', defaultSort: 'name', defaultLimit: 20, fields: [name] } as QueryListMeta;

type Spy = ReturnType<typeof vi.fn>;
type ApiMock = Record<'get' | 'post' | 'patch' | 'put' | 'delete', Spy>;

/* The list, its pager and filters are pinned by the facade spec, the dialogs and panels by
   their own specs; this spec keeps the page's wiring and the guarded record flows. */
describe('UsersComponent', () => {
  async function createFixture() {
    await TestBed.configureTestingModule({
      imports: [UsersComponent],
      providers: [
        {
          provide: ApiService,
          useValue: {
            get: vi.fn((path: string) =>
              of(path === '/iam/users' ? { items: [], nextCursor: null, hasMore: false } : []),
            ),
            post: vi.fn(() => of({})),
            patch: vi.fn(() => of({})),
            put: vi.fn(() => of({})),
            delete: vi.fn(() => of({})),
          },
        },
        {
          provide: I18nService,
          useValue: {
            languages: signal([
              { code: 'ru', name: 'Русский', active: true },
              { code: 'de', name: 'Deutsch', active: true },
            ]),
            translate: translateTest,
            currentLang: signal('ru'),
          },
        },
        { provide: ToastService, useValue: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } },
        { provide: QueryMetaService, useValue: { get: () => of(USERS_META) } },
        { provide: ListViewsApi, useValue: { list: () => of([]), create: vi.fn(), update: vi.fn(), remove: vi.fn() } },
      ],
    }).compileComponents();
    TestBed.inject(PermissionService).setPermissions(['*.*']);
    const fixture = TestBed.createComponent(UsersComponent);
    redraw(fixture);
    return fixture;
  }

  const api = () => TestBed.inject(ApiService) as unknown as ApiMock;
  const panelOf = (fixture: ComponentFixture<UsersComponent>) =>
    fixture.debugElement.query(By.directive(UserOrgUnitsPanelComponent))
      ?.componentInstance as UserOrgUnitsPanelComponent;
  /** Ticks the second unit, which makes the organization draft dirty. */
  const tickUnit = (fixture: ComponentFixture<UsersComponent>) =>
    (inScreen(fixture.nativeElement).querySelector('[data-smt-check="2"]') as HTMLInputElement).click();
  const reads = (path: string) => api().get.mock.calls.filter(([called]) => called === path).length;
  const orgReads = () => api().get.mock.calls.filter(([path]) => String(path).startsWith('/iam/org-units')).length;

  /** The page with Anna (id 7) open, from the list or from a deep link, and her organization panel. */
  async function withRecord(open: 'list' | 'link' = 'list') {
    const fixture = await createFixture();
    const first = user(7, 'Анна');
    api().get.mockImplementation((path: string) => of(orgResponse(path, first)));
    if (open === 'list') fixture.componentInstance.openViewModal(first);
    else fixture.componentInstance.loadRecordView('7');
    redraw(fixture);
    return { fixture, page: fixture.componentInstance, first, second: user(8, 'Борис'), panel: panelOf(fixture) };
  }

  it('keeps the filter menu open while a filter option is picked in the overlay', async () => {
    const fixture = await createFixture();
    const component = fixture.componentInstance;
    component.filterService.isFilterMenuOpen.set(true);
    const overlay = document.createElement('div');
    overlay.className = 'cdk-overlay-container';
    const option = document.createElement('div');
    overlay.appendChild(option);
    document.body.appendChild(overlay);
    component.onDocumentClick({ target: option } as unknown as MouseEvent);
    expect(component.filterService.isFilterMenuOpen()).toBe(true);
    component.onDocumentClick({ target: document.body } as unknown as MouseEvent);
    expect(component.filterService.isFilterMenuOpen()).toBe(false);
    overlay.remove();
  });

  it('returns focus to the filter button when Escape closes the filter menu', async () => {
    const fixture = await createFixture();
    const trigger = fixture.nativeElement.querySelector('.filter-trigger-btn') as HTMLButtonElement;
    fixture.componentInstance.filterService.isFilterMenuOpen.set(true);
    fixture.detectChanges();

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await Promise.resolve();

    expect(fixture.componentInstance.filterService.isFilterMenuOpen()).toBe(false);
    expect(document.activeElement).toBe(trigger);
  });

  it('hands the active languages to the create form and writes its 2FA choice into the form', async () => {
    const fixture = await createFixture();
    fixture.componentInstance.openCreateModal();
    redraw(fixture);
    TestBed.tick(); // smt-control wires label, error and aria state after render

    const twoFactor = inScreen(fixture.nativeElement).querySelector('[role="dialog"] [role="checkbox"]') as HTMLElement;
    twoFactor.click();
    await fixture.whenStable();
    expect(fixture.componentInstance.formsService.createForm.is2faEnabled).toBe(true);
    (inScreen(fixture.nativeElement).querySelector('#user-create-language') as HTMLButtonElement).click();
    redraw(fixture);
    expect(
      (Array.from(document.querySelectorAll('.smt-select__option-label')) as HTMLElement[]).map((option) =>
        option.textContent?.trim(),
      ),
    ).toEqual(['Русский (ru)', 'Deutsch (de)']);
  });

  it('keeps a dirty organization draft mounted until Escape or record selection is decided', async () => {
    const { fixture, page, first, second, panel } = await withRecord();
    tickUnit(fixture);
    redraw(fixture);
    expect(panel.hasUnsavedWork()).toBe(true);
    expect(
      inScreen(fixture.nativeElement).querySelector('[role="treegrid"] [role="row"][aria-expanded="true"]'),
    ).not.toBeNull();

    (document.activeElement ?? document.body).dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
    );
    redraw(fixture);
    expect(page.viewingUser()?.id).toBe(first.id);
    expect(page.isViewModalOpen()).toBe(true);
    expect(panel.discard.open()).toBe(true);
    expect(inScreen(fixture.nativeElement).querySelectorAll('[role="dialog"]')).toHaveLength(2);

    panel.discard.cancel();
    page.openViewModal(second);
    redraw(fixture);
    expect(page.viewingUser()?.id).toBe(first.id);
    expect(panel.discard.open()).toBe(true);

    panel.discard.confirm();
    redraw(fixture);
    expect(page.viewingUser()?.id).toBe(second.id);
    expect(panelOf(fixture).userId()).toBe(second.id);
  });

  it('rejects record replacement while assignment save is pending and ignores a leave decision after destruction', async () => {
    const write = new Subject<void>();
    const { fixture, page, first, second, panel } = await withRecord();
    api().put.mockReturnValue(write.asObservable());
    tickUnit(fixture);
    panel.save();
    page.openViewModal(second);
    redraw(fixture);
    expect(panel.pending()).toBe(true);
    expect(page.viewingUser()?.id).toBe(first.id);
    expect(panel.discard.open()).toBe(false);

    write.error({ status: 409, detail: 'retry' });
    page.openViewModal(second);
    expect(panel.discard.open()).toBe(true);
    fixture.destroy();
    panel.discard.confirm();
    expect(page.viewingUser()?.id).toBe(first.id);
  });

  it.each(['success', 'error'] as const)(
    'retains the real assignment panel through view revocation and ignores the old %s result',
    async (outcome) => {
      const write = new Subject<void>();
      const { fixture, page, first, second, panel } = await withRecord();
      const permissions = TestBed.inject(PermissionService);
      const toast = TestBed.inject(ToastService) as unknown as { success: Spy };
      api().put.mockReturnValue(write.asObservable());
      tickUnit(fixture);
      page.openViewModal(second);
      expect(panel.discard.open()).toBe(true);
      panel.discard.cancel();
      panel.save();
      const readsBeforeRevocation = orgReads();

      permissions.setPermissions(['iam.users.view', 'iam.users.update', 'iam.org_units.assign']);
      redraw(fixture);

      expect(panelOf(fixture)).toBe(panel);
      expect(panel.pending()).toBe(true);
      expect(write.observed).toBe(true);
      expect(page.orgPanelBusy()).toBe(true);
      expect(panel.discard.open()).toBe(false);
      expect(
        inScreen(fixture.nativeElement).querySelector('app-user-org-units-panel input[data-smt-check]'),
      ).toBeNull();
      expect(inScreen(fixture.nativeElement).querySelectorAll('[role="dialog"]')).toHaveLength(1);
      expect(inScreen(fixture.nativeElement).textContent).not.toContain('Компания');
      expect(page.canLeaveRecordPage()).toBe(false);

      page.closeRecordView();
      page.openViewModal(second);
      page.openEditFromView();
      expect(page.isViewModalOpen()).toBe(true);
      expect(page.isEditModalOpen()).toBe(false);
      expect(page.viewingUser()?.id).toBe(first.id);

      permissions.setPermissions(['*.*']);
      redraw(fixture);
      expect(panelOf(fixture)).toBe(panel);
      expect(orgReads()).toBe(readsBeforeRevocation);
      panel.save();
      expect(api().put).toHaveBeenCalledTimes(1);

      if (outcome === 'success') {
        write.next();
        write.complete();
      } else {
        write.error({ status: 409, detail: 'Late revoked assignment failure' });
      }
      redraw(fixture);

      expect(panel.pending()).toBe(false);
      expect(page.orgPanelBusy()).toBe(false);
      expect(panel.units()).toEqual([]);
      expect(panel.selectedOrgUnitIds()).toEqual([]);
      expect(panel.saveError()).toBeNull();
      expect(toast.success).not.toHaveBeenCalled();
      expect(inScreen(fixture.nativeElement).textContent).not.toContain('Late revoked assignment failure');

      panel.reloadAll();
      redraw(fixture);
      expect(orgReads()).toBe(readsBeforeRevocation + 3);
      expect(panel.units().map((unit) => unit.id)).toEqual([1, 2]);
    },
  );

  it('mounts the organization panel for a safe deep-linked user record', async () => {
    const { page, panel } = await withRecord('link');

    expect(page.routeRecordId()).toBe('7');
    expect(panel.userId()).toBe(7);
  });

  it('guards a same-ID deep-link reload before clearing its dirty panel', async () => {
    const { fixture, page, first, panel } = await withRecord('link');
    tickUnit(fixture);
    const readsBeforeReload = reads('/iam/users/7');

    page.openViewModal(first);

    expect(page.viewingUser()?.id).toBe(first.id);
    expect(panel.discard.open()).toBe(true);
    expect(reads('/iam/users/7')).toBe(readsBeforeReload);
    panel.discard.cancel();
    expect(panel.hasUnsavedWork()).toBe(true);
  });

  it('guards a direct deep-link reload requested while the mounted organization panel is dirty', async () => {
    const { fixture, page, first, panel } = await withRecord('link');
    tickUnit(fixture);
    const readsBeforeReload = reads('/iam/users/7');

    page.closeEditModal();

    expect(panel.discard.open()).toBe(true);
    expect(panel.hasUnsavedWork()).toBe(true);
    expect(page.viewingUser()?.id).toBe(first.id);
    expect(reads('/iam/users/7')).toBe(readsBeforeReload);
  });

  it.each(['dirty', 'pending'] as const)(
    'does not let a delayed profile save replace a newer %s organization panel after edit cancellation',
    async (panelState) => {
      const profileSave = new Subject<void>();
      const { fixture, page, first } = await withRecord('link');
      api().patch.mockReturnValue(profileSave.asObservable());
      api().put.mockReturnValue(new Subject<void>().asObservable());

      page.openEditFromView();
      page.submitEditUser();
      page.closeEditModal();
      redraw(fixture);
      const newerPanel = panelOf(fixture);
      tickUnit(fixture);
      if (panelState === 'pending') newerPanel.save();
      const readsBeforeProfileSettlement = reads('/iam/users/7');

      profileSave.next();
      profileSave.complete();
      redraw(fixture);

      expect(panelOf(fixture)).toBe(newerPanel);
      expect(page.viewingUser()?.id).toBe(first.id);
      expect(reads('/iam/users/7')).toBe(readsBeforeProfileSettlement);
      expect(newerPanel.pending()).toBe(panelState === 'pending');
      expect(newerPanel.hasUnsavedWork()).toBe(true);
    },
  );

  it('keeps the shared submitting state owned by the newest edit save', async () => {
    const firstSave = new Subject<void>();
    const newerSave = new Subject<void>();
    const { fixture, page, first } = await withRecord('link');
    api().patch.mockReturnValueOnce(firstSave.asObservable()).mockReturnValueOnce(newerSave.asObservable());

    page.openEditFromView();
    page.submitEditUser();
    page.closeEditModal();
    redraw(fixture);
    page.openEditFromView();
    page.submitEditUser();

    firstSave.next();

    expect(page.formsService.isSubmitting()).toBe(true);
    expect(page.isEditModalOpen()).toBe(true);
    expect(page.formsService.editingUser?.id).toBe(first.id);

    newerSave.next();
    expect(page.formsService.isSubmitting()).toBe(false);
    expect(page.isEditModalOpen()).toBe(false);
  });

  it('loads the security summary of the viewed user when its tab opens, and shows it', async () => {
    const { fixture, page } = await withRecord();
    const session = { id: 101, userId: 7, ip: '127.0.0.1', userAgent: 'Chrome', createdAt: '', lastSeenAt: '' };
    const security = { userId: 7, is2faEnabled: true, activeSessionsCount: 1, activeSessions: [session] };
    api().get.mockImplementation((path: string) =>
      of(
        path === '/iam/users/7/security'
          ? { ...security, recentLoginAttempts: [] }
          : orgResponse(path, user(7, 'Анна')),
      ),
    );
    expect(page.activeViewTab()).toBe('info');

    page.switchViewTab('security', 7);
    redraw(fixture);
    await fixture.whenStable();
    redraw(fixture);

    expect(page.activeViewTab()).toBe('security');
    expect(reads('/iam/users/7/security')).toBe(1);
    expect(page.secService.userSecurity()?.userId).toBe(7);
    const end = inScreen(fixture.nativeElement).querySelector('[data-testid="user-sessions-table"] button.smt-button');
    expect(end?.getAttribute('aria-label')).toBe('Завершить сессию с IP 127.0.0.1');
  });

  it('names a manager who is not on the loaded page, in the list and in the edit form', async () => {
    const fixture = await createFixture();
    const report = { ...user(5, 'Подчинённый'), managerId: 42 };
    api().get.mockImplementation((path: string) =>
      of(
        path === '/iam/users'
          ? { items: [report], nextCursor: null, hasMore: false }
          : path === '/iam/users/42'
            ? user(42, 'Дальний руководитель')
            : [],
      ),
    );
    fixture.componentInstance.list.loadUsers(true);
    redraw(fixture);

    expect(api().get).toHaveBeenCalledWith('/iam/users/42', undefined, { notifyError: false });
    expect(inScreen(fixture.nativeElement).textContent).toContain('Дальний руководитель');

    fixture.componentInstance.openEditModal(report);
    redraw(fixture);
    const picker = inScreen(fixture.nativeElement).querySelector(
      '[role="dialog"] smt-select button[aria-haspopup="listbox"]',
    );
    expect(picker.textContent).toContain('Дальний руководитель');
    expect(inScreen(fixture.nativeElement).querySelector('#user-edit-manager')).toBeNull();
  });

  it('evaluates assignment permissions and switches to permissions tab', async () => {
    const fixture = await createFixture();
    const component = fixture.componentInstance;

    expect(component.canViewAssignments()).toBe(true);
    expect(component.canAssignPermissions()).toBe(true);

    component.switchViewTab('permissions', 42);
    expect(component.activeViewTab()).toBe('permissions');
  });

  function user(id: number, name: string): User {
    return {
      id,
      name,
      login: name.toLowerCase(),
      email: `${name.toLowerCase()}@example.test`,
      state: 'A',
      language: 'ru',
      timezone: 'Asia/Tashkent',
      attributes: {},
      roleIds: [],
      is2faEnabled: false,
      forcePasswordChange: false,
      createdAt: '2026-08-30T00:00:00Z',
      modifiedAt: '2026-08-30T00:00:00Z',
    };
  }

  function orgResponse(path: string, selected: User): unknown {
    if (path === `/iam/users/${selected.id}`) return selected;
    const unit = (id: number, parentId: number | null, code: string, name: string, kind: string) => ({
      ...{ id, parentId, code, name, kind },
      ...{ state: 'A', orderNo: 0, createdAt: '', modifiedAt: '' },
    });
    if (path === '/iam/org-units')
      return [unit(1, null, 'ROOT', 'Компания', 'company'), unit(2, 1, 'OPS', 'Операции', 'department')];
    const assignments = path.match(/^\/iam\/org-units\/users\/(\d+)$/);
    if (assignments) return { userId: Number(assignments[1]), orgUnitIds: [], legacyOrgUnitId: null };
    if (/^\/iam\/org-units\/users\/\d+\/scope$/.test(path)) return { rule: 'ALL', visibleOrgUnitIds: [] };
    return [];
  }
});
