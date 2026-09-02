import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../../core/services/api.service';
import { PermissionService } from '../../../core/services/permission.service';
import { ToastService } from '../../../core/services/toast.service';
import { Role } from '../../../core/models/rbac.models';
import { RolesComponent } from './roles.component';

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
});
