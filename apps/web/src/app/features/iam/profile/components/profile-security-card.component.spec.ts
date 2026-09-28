import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { User } from '../profile.models';
import { ProfileSecurityCardComponent } from './profile-security-card.component';

describe('ProfileSecurityCardComponent', () => {
  const user = (is2faEnabled: boolean): User => ({
    id: 7,
    name: 'Анна Иванова',
    login: 'anna',
    email: 'anna@example.test',
    state: 'A',
    language: 'ru',
    timezone: 'Asia/Tashkent',
    attributes: {},
    is2faEnabled,
    forcePasswordChange: false,
    createdAt: '2026-08-30T00:00:00Z',
    modifiedAt: '2026-08-30T00:00:00Z',
  });

  function render(value: User | null) {
    const fixture = TestBed.createComponent(ProfileSecurityCardComponent);
    fixture.componentRef.setInput('user', value);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    return {
      title: host.querySelector('.twofa-status-title')?.textContent?.trim(),
      description: host.querySelector('.twofa-status-desc')?.textContent?.trim(),
    };
  }

  it('tells a person with two-factor sign-in that it is on and what it asks for', () => {
    const { title, description } = render(user(true));

    expect(title).toBe('Двухфакторная защита активна');
    expect(description).toContain('OTP-код');
  });

  it('tells a person without two-factor sign-in, or before the profile arrives, that it is off', () => {
    expect(render(user(false)).title).toBe('2FA-защита не включена');
    expect(render(null).description).toContain('Обратитесь к администратору');
  });
});
