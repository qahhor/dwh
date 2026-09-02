import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../../core/services/api.service';
import { AuthService } from '../../../core/services/auth.service';
import { ToastService } from '../../../core/services/toast.service';
import { User } from '../../../core/models/auth.models';
import { ProfileComponent } from './profile.component';

describe('ProfileComponent UI contracts', () => {
  async function createFixture() {
    const user: User = {
      id: 1,
      name: 'Иван Иванов',
      login: 'ivan',
      email: 'ivan@example.test',
      state: 'A',
      language: 'ru',
      timezone: 'Asia/Tashkent',
      attributes: {},
      is2faEnabled: false,
      forcePasswordChange: false,
      createdAt: '2026-08-30T00:00:00Z',
      modifiedAt: '2026-08-30T00:00:00Z'
    };
    await TestBed.configureTestingModule({
      imports: [ProfileComponent],
      providers: [
        {
          provide: ApiService,
          useValue: {
            get: vi.fn(() => of([])),
            post: vi.fn(() => of({})),
            delete: vi.fn(() => of({}))
          }
        },
        { provide: AuthService, useValue: { currentUser: signal(user) } },
        { provide: ToastService, useValue: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } }
      ]
    }).compileComponents();
    const fixture = TestBed.createComponent(ProfileComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('connects password fields to inline validation and password visibility', async () => {
    const fixture = await createFixture();
    fixture.componentInstance.submitChangePassword(new Event('submit'));
    fixture.detectChanges();

    const current = fixture.nativeElement.querySelector('#profile-current-password') as HTMLInputElement;
    expect(fixture.nativeElement.querySelector(`label[for="${current.id}"]`)).not.toBeNull();
    expect(current.required).toBe(true);
    expect(current.getAttribute('aria-invalid')).toBe('true');
    expect(current.getAttribute('aria-describedby')).toBe('profile-current-password-error');
    expect(fixture.nativeElement.querySelector('button[aria-label="Показать новый пароль"]')).not.toBeNull();
  });

  it('names session and token table regions', async () => {
    const fixture = await createFixture();
    const regions = fixture.nativeElement.querySelectorAll('.table-wrapper[role="region"]');

    expect(regions.length).toBe(2);
    expect(regions[0].tabIndex).toBe(0);
    expect(regions[0].querySelector('table')?.getAttribute('aria-label')).toBe('Активные сессии');
    expect(regions[1].querySelector('table')?.getAttribute('aria-label')).toBe('API-токены');
  });

  it('validates token name inline before creation', async () => {
    const fixture = await createFixture();
    fixture.componentInstance.openCreateTokenModal();
    fixture.componentInstance.createTokenSubmit();
    fixture.detectChanges();

    const name = fixture.nativeElement.querySelector('#profile-token-name') as HTMLInputElement;
    expect(fixture.nativeElement.querySelector(`label[for="${name.id}"]`)).not.toBeNull();
    expect(name.required).toBe(true);
    expect(name.getAttribute('aria-invalid')).toBe('true');
    expect(name.getAttribute('aria-describedby')).toBe('profile-token-name-error');
  });
});
