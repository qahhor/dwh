import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../../core/services/api.service';
import { PermissionService } from '../../../core/services/permission.service';
import { ToastService } from '../../../core/services/toast.service';
import { User } from '../../../core/models/auth.models';
import { UsersComponent } from './users.component';

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
    expect(fixture.nativeElement.querySelector('.segmented-control[role="group"]')).not.toBeNull();
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
  });
});
