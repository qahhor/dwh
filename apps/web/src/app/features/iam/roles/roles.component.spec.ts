import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { of, Subject } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../../core/services/api.service';
import { PermissionService } from '../../../core/services/permission.service';
import { ToastService } from '../../../core/services/toast.service';
import { Role } from '../../../core/models/rbac.models';
import { RolesComponent } from './roles.component';
import { RoleScopePanelComponent } from '../org-units/public-api';

describe('RolesComponent UI contracts', () => {
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
            delete: vi.fn(() => of({}))
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
    const fixture = TestBed.createComponent(RolesComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('uses named searches, native role selection and accessible permission sections', async () => {
    const fixture = await createFixture();
    const role: Role = {
      id: 3,
      name: 'Аналитик',
      state: 'A',
      orderNo: 1,
      createdAt: '2026-08-30T00:00:00Z',
      modifiedAt: '2026-08-30T00:00:00Z'
    };
    fixture.componentInstance.roles.set([role]);
    fixture.componentInstance.selectedRole.set(role);
    fixture.componentInstance.moduleGroups = [{
      moduleCode: 'audit',
      moduleName: 'Аудит',
      isExpanded: true,
      forms: [{
        module: 'audit',
        formCode: 'audit.events',
        formName: 'События',
        actions: [{ action: 'view', actionName: 'Просмотр' }]
      }]
    }];
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('label[for="role-search"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('label[for="permission-search"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('button[aria-label="Выбрать роль Аналитик"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[role="progressbar"][aria-label="Доля разрешённых действий"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('button[aria-expanded="true"][aria-controls="role-module-audit"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('#role-module-audit[role="region"] table[aria-label="Права модуля Аудит"]')).not.toBeNull();
  });

  it('connects the required role name to inline validation', async () => {
    const fixture = await createFixture();
    fixture.componentInstance.openCreateModal();
    (fixture.componentInstance as any).isCreateSubmitted = true;
    fixture.detectChanges();

    const name = fixture.nativeElement.querySelector('#role-create-name') as HTMLInputElement;
    expect(fixture.nativeElement.querySelector(`label[for="${name.id}"]`)).not.toBeNull();
    expect(name.required).toBe(true);
    expect(name.getAttribute('aria-invalid')).toBe('true');
    expect(name.getAttribute('aria-describedby')).toBe('role-create-name-error');
  });

  it('waits for a dirty scope decision before selecting another role', async () => {
    const fixture = await createFixture();
    const api = TestBed.inject(ApiService) as unknown as { get: ReturnType<typeof vi.fn> };
    const first = role(1, 'Первая');
    const second = role(2, 'Вторая');
    api.get.mockImplementation((path: string) => roleResponse(path, [first, second]));

    fixture.componentInstance.selectRole(first);
    fixture.detectChanges();
    const panel = fixture.debugElement.query(By.directive(RoleScopePanelComponent)).componentInstance as RoleScopePanelComponent;
    panel.selectRule('SELF');
    fixture.componentInstance.selectRole(second);
    fixture.detectChanges();
    expect(fixture.componentInstance.selectedRole()?.id).toBe(first.id);
    expect(panel.discard.open()).toBe(true);

    panel.discard.cancel();
    fixture.componentInstance.selectRole(second);
    panel.discard.confirm();
    fixture.detectChanges();
    expect(fixture.componentInstance.selectedRole()?.id).toBe(second.id);
    expect(fixture.debugElement.query(By.directive(RoleScopePanelComponent)).componentInstance.roleId).toBe(second.id);
  });

  it('blocks destructive target changes during scope save without blocking the permission matrix save', async () => {
    const fixture = await createFixture();
    const api = TestBed.inject(ApiService) as unknown as {
      get: ReturnType<typeof vi.fn>;
      put: ReturnType<typeof vi.fn>;
      delete: ReturnType<typeof vi.fn>;
    };
    const scopeWrite = new Subject<void>();
    const first = role(1, 'Первая');
    const second = role(2, 'Вторая');
    api.get.mockImplementation((path: string) => roleResponse(path, [first, second]));
    api.put.mockImplementation((path: string) => path.endsWith('/rule') ? scopeWrite.asObservable() : of({}));
    api.delete.mockReturnValue(of({}));
    fixture.componentInstance.selectRole(first);
    fixture.detectChanges();
    const panel = fixture.debugElement.query(By.directive(RoleScopePanelComponent)).componentInstance as RoleScopePanelComponent;
    panel.selectRule('SELF');
    panel.save();
    panel.confirmSave();

    fixture.componentInstance.selectRole(second);
    fixture.componentInstance.openDeleteRoleModal(second);
    fixture.detectChanges();
    expect(fixture.componentInstance.selectedRole()?.id).toBe(first.id);
    expect(fixture.componentInstance.isDeleteModalOpen()).toBe(false);

    fixture.componentInstance.rolePermissions.set(new Set(['audit.log.view']));
    fixture.componentInstance.savePermissions();
    expect(api.put).toHaveBeenCalledWith('/rbac/roles/1/permissions', [{ formCode: 'audit.log', action: 'view' }]);

    scopeWrite.error({ status: 409, detail: 'retry' });
    expect(panel.pending).toBe(false);
  });

  it('retains a dirty selected role when another role is deleted', async () => {
    const fixture = await createFixture();
    const api = TestBed.inject(ApiService) as unknown as {
      get: ReturnType<typeof vi.fn>;
      delete: ReturnType<typeof vi.fn>;
    };
    const first = role(1, 'Первая');
    const second = role(2, 'Вторая');
    api.get.mockImplementation((path: string) => roleResponse(path, [first]));
    api.delete.mockReturnValue(of({}));
    fixture.componentInstance.selectRole(first);
    fixture.detectChanges();
    const panel = fixture.debugElement.query(By.directive(RoleScopePanelComponent)).componentInstance as RoleScopePanelComponent;
    panel.selectRule('SELF');

    fixture.componentInstance.openDeleteRoleModal(second);
    fixture.componentInstance.confirmDeleteRole();
    fixture.detectChanges();

    expect(api.delete).toHaveBeenCalledWith('/rbac/roles/2');
    expect(fixture.componentInstance.selectedRole()?.id).toBe(first.id);
    expect(panel.hasUnsavedWork()).toBe(true);
  });

  it('freezes selected-role deletion and changes selection only after the guarded delete succeeds', async () => {
    const fixture = await createFixture();
    const api = TestBed.inject(ApiService) as unknown as {
      get: ReturnType<typeof vi.fn>;
      delete: ReturnType<typeof vi.fn>;
    };
    const deletion = new Subject<void>();
    const first = role(1, 'Первая');
    const second = role(2, 'Вторая');
    api.get.mockImplementation((path: string) => roleResponse(path, [first, second]));
    api.delete.mockReturnValue(deletion.asObservable());
    fixture.componentInstance.selectRole(first);
    fixture.detectChanges();
    const panel = fixture.debugElement.query(By.directive(RoleScopePanelComponent)).componentInstance as RoleScopePanelComponent;
    panel.selectRule('SELF');

    fixture.componentInstance.openDeleteRoleModal(first);
    fixture.componentInstance.openDeleteRoleModal(second);
    fixture.componentInstance.confirmDeleteRole();
    expect(fixture.componentInstance.deletingRole?.id).toBe(first.id);
    expect(api.delete).not.toHaveBeenCalled();
    expect(panel.discard.open()).toBe(true);

    panel.discard.confirm();
    expect(api.delete).toHaveBeenCalledWith('/rbac/roles/1');
    expect(fixture.componentInstance.selectedRole()?.id).toBe(first.id);
    api.get.mockImplementation((path: string) => roleResponse(path, [second]));
    deletion.next();
    deletion.complete();
    fixture.detectChanges();
    expect(fixture.componentInstance.selectedRole()?.id).toBe(second.id);
    expect(fixture.componentInstance.deletingRole).toBeNull();
  });

  it('does not apply a create-triggered leave callback after destruction', async () => {
    const fixture = await createFixture();
    const api = TestBed.inject(ApiService) as unknown as {
      get: ReturnType<typeof vi.fn>;
      post: ReturnType<typeof vi.fn>;
    };
    const created = new Subject<Role>();
    const first = role(1, 'Первая');
    const third = role(3, 'Третья');
    api.get.mockImplementation((path: string) => roleResponse(path, [first, third]));
    api.post.mockReturnValue(created.asObservable());
    fixture.componentInstance.selectRole(first);
    fixture.detectChanges();
    const panel = fixture.debugElement.query(By.directive(RoleScopePanelComponent)).componentInstance as RoleScopePanelComponent;
    panel.selectRule('SELF');
    fixture.componentInstance.newRoleForm = { name: third.name, orderNo: 0 };
    fixture.componentInstance.submitCreateRole();

    created.next(third);
    expect(fixture.componentInstance.selectedRole()?.id).toBe(first.id);
    expect(panel.discard.open()).toBe(true);

    fixture.destroy();
    panel.discard.confirm();
    expect(fixture.componentInstance.selectedRole()?.id).toBe(first.id);
  });

  function role(id: number, name: string): Role {
    return { id, name, state: 'A', orderNo: 0, createdAt: '', modifiedAt: '' };
  }

  function roleResponse(path: string, roles: Role[]) {
    if (path === '/rbac/roles') return of(roles);
    if (path === '/rbac/forms') return of([]);
    const rule = path.match(/^\/iam\/org-units\/roles\/(\d+)\/rule$/);
    if (rule) return of({ roleId: Number(rule[1]), rule: 'ALL' });
    if (/^\/rbac\/roles\/\d+\/permissions$/.test(path)) return of([]);
    return of([]);
  }
});
