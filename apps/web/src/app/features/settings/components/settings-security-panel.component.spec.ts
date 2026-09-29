import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { PASSWORD_POLICY } from '@core/security/password-policy';
import { SettingChange } from '../settings.models';
import { SettingsSecurityPanelComponent } from './settings-security-panel.component';

function render(options: { canUpdate?: boolean; saving?: boolean; settings?: Record<string, string> } = {}) {
  const fixture = TestBed.createComponent(SettingsSecurityPanelComponent);
  fixture.componentRef.setInput('canUpdateSystemSettings', options.canUpdate ?? true);
  fixture.componentRef.setInput('isSaving', options.saving ?? false);
  fixture.componentRef.setInput(
    'systemSettings',
    options.settings ?? {
      'security.session_lifetime_hours': '720',
      'security.idle_lock_minutes': '15',
      'security.require_2fa': 'true',
    },
  );
  const changes: SettingChange[] = [];
  const asked: string[] = [];
  const component = fixture.componentInstance;
  component.settingChange.subscribe((change) => changes.push(change));
  component.toggleRequire2fa.subscribe((on) => asked.push(`2fa:${on}`));
  component.save.subscribe(() => asked.push('save'));
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const field = (id: string) => host.querySelector(`#${id}`) as HTMLInputElement;
  const twoFactor = () => host.querySelector('#settings-require-2fa') as HTMLButtonElement;
  return { fixture, host, changes, asked, field, twoFactor };
}

describe('SettingsSecurityPanelComponent', () => {
  it('states the password policy and reads the session lifetime in days as well as hours', () => {
    const { host, field } = render();

    expect(host.textContent).toContain(`От ${PASSWORD_POLICY.min} до ${PASSWORD_POLICY.max} символов`);
    // The length is the password policy: shown, not edited.
    expect(host.querySelector('#settings-password-length')?.tagName).toBe('SPAN');
    expect(host.querySelector('input[name="settingsPasswordLength"]')).toBeNull();
    expect(host.querySelector('label[for="settings-session-lifetime"] .unit-badge')?.textContent?.trim()).toBe(
      '720 ч. (30 дн.)',
    );
    expect(field('settings-session-lifetime').value).toBe('720');
    expect(field('settings-idle-lock').value).toBe('15');
  });

  it('shows no lifetime badge while the stored value is not a positive number', () => {
    const { host } = render({ settings: { 'security.session_lifetime_hours': '0' } });

    expect(host.querySelector('label[for="settings-session-lifetime"] .unit-badge')).toBeNull();
  });

  it('sends typed numbers up as text, and an emptied field as an empty value', () => {
    const { changes, field } = render();

    field('settings-session-lifetime').value = '48';
    field('settings-session-lifetime').dispatchEvent(new Event('input'));
    field('settings-idle-lock').value = '';
    field('settings-idle-lock').dispatchEvent(new Event('input'));

    expect(changes).toEqual([
      { key: 'security.session_lifetime_hours', value: '48' },
      { key: 'security.idle_lock_minutes', value: '' },
    ]);
  });

  it('shows whether two-factor sign-in is required and reports a flip', () => {
    const { asked, twoFactor } = render();

    expect(twoFactor().getAttribute('role')).toBe('switch');
    expect(twoFactor().getAttribute('aria-labelledby')).toBe('settings-require-2fa-label');
    expect(twoFactor().getAttribute('aria-describedby')).toBe('settings-require-2fa-desc');
    expect(twoFactor().getAttribute('aria-checked')).toBe('true');
    twoFactor().click();

    expect(asked).toEqual(['2fa:false']);
  });

  it('is read only without the right to change system settings: a badge, locked controls and no save', () => {
    const { host, field, twoFactor } = render({ canUpdate: false });

    expect(host.textContent).toContain('Только чтение');
    expect(field('settings-session-lifetime').disabled).toBe(true);
    expect(field('settings-idle-lock').disabled).toBe(true);
    expect(twoFactor().disabled).toBe(true);
    expect(host.querySelector('button[smt-button]')).toBeNull();
  });

  it('asks to save with its button and locks the controls while saving', () => {
    const { fixture, host, asked, field } = render();

    (host.querySelector('button[smt-button]') as HTMLButtonElement).click();
    expect(asked).toEqual(['save']);

    fixture.componentRef.setInput('isSaving', true);
    fixture.detectChanges();
    expect(field('settings-session-lifetime').disabled).toBe(true);
  });
});
