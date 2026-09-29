import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { Router } from '@angular/router';
import { of, Subject } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { Role } from '@core/models/rbac.models';
import { RolesComponent } from './roles.component';
import { RoleScopePanelComponent } from '../org-units/public-api';
import { inScreen } from '@testing/in-screen';

type Spy = ReturnType<typeof vi.fn>;
type ApiMock = Record<'get' | 'post' | 'patch' | 'put' | 'delete', Spy>;

/* The cards, the matrix and the dialogs are pinned by their own specs, the matrix draft by the
   editor spec and its request lifecycle by roles-mechanics; this spec keeps how the page lets a
   role, its matrix and its scope panel go. */
describe('RolesComponent', () => {
  async function createFixture() {
    await TestBed.configureTestingModule({
      imports: [RolesComponent],
      providers: [
        {
          provide: ApiService,
          useValue: {
            get: vi.fn(() => of([])),
            post: vi.fn(() => of({})),
            patch: vi.fn(() => of({})),
            put: vi.fn(() => of({})),
            delete: vi.fn(() => of({})),
          },
        },
        { provide: ToastService, useValue: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } },
        { provide: Router, useValue: { navigate: vi.fn() } },
      ],
    }).compileComponents();
    TestBed.inject(PermissionService).setPermissions(['*.*']);
    const fixture = TestBed.createComponent(RolesComponent);
    fixture.detectChanges();
    return fixture;
  }

  const api = () => TestBed.inject(ApiService) as unknown as ApiMock;
  const panelOf = (fixture: ComponentFixture<RolesComponent>) =>
    fixture.debugElement.query(By.directive(RoleScopePanelComponent))?.componentInstance as RoleScopePanelComponent;
  const scopeReads = () =>
    api().get.mock.calls.filter(([path]) => String(path).includes('/iam/org-units/roles/')).length;

  /** The page with the first role chosen and its scope panel, the server knowing `roles`. */
  async function withRole(roles = [role(1, 'Первая'), role(2, 'Вторая')]) {
    const fixture = await createFixture();
    api().get.mockImplementation((path: string) => roleResponse(path, roles));
    fixture.componentInstance.selectRole(roles[0]);
    fixture.detectChanges();
    return { fixture, page: fixture.componentInstance, first: roles[0], panel: panelOf(fixture) };
  }

  it('waits for a dirty scope decision before selecting another role', async () => {
    const { fixture, page, first, panel } = await withRole();
    const second = role(2, 'Вторая');
    panel.selectRule('SELF');
    page.selectRole(second);
    fixture.detectChanges();
    expect(page.matrix.selectedRole()?.id).toBe(first.id);
    expect(panel.discard.open()).toBe(true);

    panel.discard.cancel();
    page.selectRole(second);
    panel.discard.confirm();
    fixture.detectChanges();
    expect(page.matrix.selectedRole()?.id).toBe(second.id);
    expect(panelOf(fixture).roleId()).toBe(second.id);
  });

  it('blocks destructive target changes during scope save without blocking the permission matrix save', async () => {
    const scopeWrite = new Subject<void>();
    const { fixture, page, first, panel } = await withRole();
    const second = role(2, 'Вторая');
    api().put.mockImplementation((path: string) => (path.endsWith('/rule') ? scopeWrite.asObservable() : of({})));
    panel.selectRule('SELF');
    panel.save();
    panel.confirmSave();

    page.selectRole(second);
    page.openDeleteRoleModal(second);
    fixture.detectChanges();
    expect(page.matrix.selectedRole()?.id).toBe(first.id);
    expect(page.roleForms.isDeleteModalOpen()).toBe(false);

    page.matrix.rolePermissions.set(new Set(['audit.log.view']));
    page.matrix.savePermissions();
    expect(api().put).toHaveBeenCalledWith(
      '/iam/roles/1/permissions',
      [{ formCode: 'audit.log', action: 'view' }],
      expect.any(Object),
    );

    scopeWrite.error({ status: 409, detail: 'retry' });
    expect(panel.pending()).toBe(false);
  });

  it.each(['success', 'error'] as const)(
    'retains the real role-scope panel through view revocation and ignores the old %s result',
    async (outcome) => {
      const write = new Subject<void>();
      const { fixture, page, first, panel } = await withRole();
      const permissions = TestBed.inject(PermissionService);
      const toast = TestBed.inject(ToastService) as unknown as { success: Spy };
      api().put.mockImplementation((path: string) => (path.endsWith('/rule') ? write.asObservable() : of({})));
      panel.selectRule('SELF');
      panel.save();
      expect(panel.confirmationOpen()).toBe(true);
      panel.confirmSave();
      const readsBeforeRevocation = scopeReads();

      permissions.setPermissions(['rbac.roles.view', 'rbac.roles.grant', 'iam.org_units.assign']);
      fixture.detectChanges();

      expect(panelOf(fixture)).toBe(panel);
      expect(panel.pending()).toBe(true);
      expect(write.observed).toBe(true);
      expect(page.scopePanelBusy()).toBe(true);
      expect(panel.confirmationOpen()).toBe(false);
      expect(inScreen(fixture.nativeElement).querySelector('app-role-scope-panel [role="radio"]')).toBeNull();
      expect(inScreen(fixture.nativeElement).querySelector('[role="dialog"]')).toBeNull();
      expect(page.canLeaveRecordPage()).toBe(false);

      page.selectRole(role(2, 'Вторая'));
      page.openDeleteRoleModal(first);
      expect(page.matrix.selectedRole()?.id).toBe(first.id);
      expect(page.roleForms.isDeleteModalOpen()).toBe(false);

      permissions.setPermissions(['*.*']);
      fixture.detectChanges();
      expect(panelOf(fixture)).toBe(panel);
      expect(scopeReads()).toBe(readsBeforeRevocation);
      panel.confirmSave();
      expect(api().put.mock.calls.filter(([path]) => String(path).endsWith('/rule'))).toHaveLength(1);

      if (outcome === 'success') {
        write.next();
        write.complete();
      } else {
        write.error({ status: 409, detail: 'Late revoked rule failure' });
      }
      fixture.detectChanges();

      expect(panel.pending()).toBe(false);
      expect(page.scopePanelBusy()).toBe(false);
      expect(panel.loaded()).toBe(false);
      expect(panel.saveError()).toBeNull();
      expect(toast.success).not.toHaveBeenCalled();
      expect(fixture.nativeElement.textContent).not.toContain('Late revoked rule failure');

      panel.reload();
      fixture.detectChanges();
      expect(scopeReads()).toBe(readsBeforeRevocation + 1);
      expect(panel.loaded()).toBe(true);
    },
  );

  it('retains a dirty selected role when another role is deleted', async () => {
    const { fixture, page, first, panel } = await withRole([role(1, 'Первая')]);
    panel.selectRule('SELF');

    page.openDeleteRoleModal(role(2, 'Вторая'));
    page.confirmDeleteRole();
    fixture.detectChanges();

    expect(api().delete).toHaveBeenCalledWith('/iam/roles/2');
    expect(page.matrix.selectedRole()?.id).toBe(first.id);
    expect(panel.hasUnsavedWork()).toBe(true);
  });

  it('freezes selected-role deletion and changes selection only after the guarded delete succeeds', async () => {
    const deletion = new Subject<void>();
    const { fixture, page, first, panel } = await withRole();
    const second = role(2, 'Вторая');
    api().delete.mockReturnValue(deletion.asObservable());
    panel.selectRule('SELF');

    page.openDeleteRoleModal(first);
    page.openDeleteRoleModal(second);
    page.confirmDeleteRole();
    expect(page.roleForms.deletingRole?.id).toBe(first.id);
    expect(api().delete).not.toHaveBeenCalled();
    expect(panel.discard.open()).toBe(true);

    panel.discard.confirm();
    expect(api().delete).toHaveBeenCalledWith('/iam/roles/1');
    expect(page.matrix.selectedRole()?.id).toBe(first.id);
    api().get.mockImplementation((path: string) => roleResponse(path, [second]));
    deletion.next();
    deletion.complete();
    fixture.detectChanges();
    expect(page.matrix.selectedRole()?.id).toBe(second.id);
    expect(page.roleForms.deletingRole).toBeNull();
  });

  it('does not apply a create-triggered leave callback after destruction', async () => {
    const created = new Subject<Role>();
    const third = role(3, 'Третья');
    const { fixture, page, first, panel } = await withRole([role(1, 'Первая'), third]);
    api().post.mockReturnValue(created.asObservable());
    panel.selectRule('SELF');
    page.roleForms.newRoleForm = { name: third.name, orderNo: 0 };
    page.submitCreateRole();

    created.next(third);
    expect(page.matrix.selectedRole()?.id).toBe(first.id);
    expect(panel.discard.open()).toBe(true);

    fixture.destroy();
    panel.discard.confirm();
    expect(page.matrix.selectedRole()?.id).toBe(first.id);
  });

  it('asks before leaving unsaved rights for another role, and switches only when they are discarded', async () => {
    const { fixture, page, first } = await withRole();
    const second = role(2, 'Вторая');
    page.matrix.rolePermissions.set(new Set(['audit.events.edit']));
    fixture.detectChanges();
    expect(page.matrix.isPermissionsDirty()).toBe(true);
    expect(page.canLeaveRecordPage()).toBe(false);

    page.selectRole(second);
    fixture.detectChanges();
    expect(page.matrix.selectedRole()?.id).toBe(first.id);
    expect(page.isDiscardPermissionsModalOpen()).toBe(true);

    page.closeDiscardModal();
    expect(page.isDiscardPermissionsModalOpen()).toBe(false);
    expect(page.matrix.selectedRole()?.id).toBe(first.id);

    page.selectRole(second);
    page.confirmDiscardAndSwitch();
    fixture.detectChanges();
    expect(page.matrix.selectedRole()?.id).toBe(second.id);
  });

  it('hands the modules it shows to the search count and to the matrix batch actions', async () => {
    const { fixture, page } = await withRole();
    await fixture.whenStable();
    page.moduleGroups = [
      {
        moduleCode: 'audit',
        moduleName: 'Аудит',
        isExpanded: false,
        forms: [
          {
            module: 'audit',
            formCode: 'audit.events',
            formName: 'События',
            actions: [
              { action: 'view', actionName: 'Просмотр' },
              { action: 'edit', actionName: 'Редактирование' },
            ],
          },
        ],
      },
    ];
    page.matrixSearchQuery = 'События';
    expect(page.matchingFormsCount()).toBe(1);
    expect(page.matrix.canEditPermissions()).toBe(true);

    page.toggleAllPermissions(true);
    expect([...page.matrix.rolePermissions()]).toEqual(['audit.events.view', 'audit.events.edit']);
    page.toggleReadOnlyAllPermissions();
    expect([...page.matrix.rolePermissions()]).toEqual(['audit.events.view']);
    page.setAllModulesExpanded(true);
    expect(page.moduleGroups[0].isExpanded).toBe(true);
    page.toggleModuleExpand(page.moduleGroups[0]);
    expect(page.moduleGroups[0].isExpanded).toBe(false);
  });

  it('saves unsaved rights once and only then switches to the asked role', async () => {
    const { fixture, page, first } = await withRole();
    await fixture.whenStable();
    page.matrix.togglePermission('audit.events', 'edit', true);
    page.selectRole(role(2, 'Вторая'));
    expect(page.isDiscardPermissionsModalOpen()).toBe(true);

    page.saveAndSwitch();

    expect(api().put).toHaveBeenCalledTimes(1);
    expect(api().put).toHaveBeenCalledWith(
      `/iam/roles/${first.id}/permissions`,
      [{ formCode: 'audit.events', action: 'edit' }],
      expect.any(Object),
    );
    expect(page.isDiscardPermissionsModalOpen()).toBe(false);
    expect(page.matrix.selectedRole()?.id).toBe(2);
  });

  it('navigates to users list with role filter when user count button is clicked', async () => {
    const fixture = await createFixture();
    const router = TestBed.inject(Router);
    const fakeEvent = { stopPropagation: vi.fn() } as unknown as Event;

    fixture.componentInstance.navigateToUsersWithRole(role(5, 'Инженер'), fakeEvent);
    expect(fakeEvent.stopPropagation).toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['/iam/users'], { queryParams: { roleId: 5 } });
  });

  function role(id: number, name: string): Role {
    return { id, name, state: 'A', orderNo: 0, createdAt: '', modifiedAt: '' };
  }

  function roleResponse(path: string, roles: Role[]) {
    if (path === '/iam/roles') return of(roles);
    const rule = path.match(/^\/iam\/org-units\/roles\/(\d+)\/rule$/);
    if (rule) return of({ roleId: Number(rule[1]), rule: 'ALL' });
    return of([]);
  }
});
