import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { User } from '../profile.models';
import { UserProfileCardComponent } from './user-profile-card.component';

describe('UserProfileCardComponent', () => {
  const anna: User = {
    id: 7,
    name: 'Анна Иванова',
    login: 'anna',
    email: 'anna@example.test',
    phone: '+998901234567',
    state: 'A',
    language: 'uz',
    timezone: 'Europe/Moscow',
    attributes: {},
    is2faEnabled: true,
    forcePasswordChange: false,
    createdAt: '2026-08-30T00:00:00Z',
    modifiedAt: '2026-08-30T00:00:00Z',
  };

  function render(user: User | null) {
    const fixture = TestBed.createComponent(UserProfileCardComponent);
    fixture.componentRef.setInput('user', user);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows nothing until the profile arrives', () => {
    expect(render(null).querySelector('.user-card')).toBeNull();
  });

  it('shows the person with their contacts, language, zone, status and two-factor badge', () => {
    const host = render(anna);

    expect(host.querySelector('.user-fullname')?.textContent?.trim()).toBe('Анна Иванова');
    const info = host.querySelector('.user-info-grid')?.textContent ?? '';
    expect(info).toContain('@anna');
    expect(info).toContain('anna@example.test');
    expect(info).toContain('+998901234567');
    expect(info).toContain('uz (Europe/Moscow)');
    const badges = Array.from(host.querySelectorAll('smt-badge')).map((badge) => badge.textContent?.trim());
    expect(badges[0]).toBe('Активен');
    expect(badges[1]).toContain('2FA Включена');
  });

  it('marks a blocked person, leaves out a missing phone and falls back to the default language and zone', () => {
    const host = render({ ...anna, state: 'P', is2faEnabled: false, phone: undefined, language: '', timezone: '' });

    const badges = Array.from(host.querySelectorAll('smt-badge')).map((badge) => badge.textContent?.trim());
    expect(badges).toEqual(['Заблокирован']);
    const info = host.querySelector('.user-info-grid')?.textContent ?? '';
    expect(info).not.toContain('Телефон');
    expect(info).toContain('ru (Asia/Tashkent)');
  });
});
