import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';
import { inScreen } from '@testing/in-screen';
import { SettingsPreferencesPanelComponent } from './settings-preferences-panel.component';

const LANGUAGES = [
  { code: 'ru', name: 'Русский' },
  { code: 'en', name: 'English' },
];

async function render(options: { saving?: boolean; userSettings?: Record<string, string> } = {}) {
  const fixture = TestBed.createComponent(SettingsPreferencesPanelComponent);
  document.body.appendChild(fixture.nativeElement);
  fixture.componentRef.setInput('languages', LANGUAGES);
  fixture.componentRef.setInput('currentLang', 'ru');
  fixture.componentRef.setInput('userThemePreference', 'light');
  fixture.componentRef.setInput('isSaving', options.saving ?? false);
  fixture.componentRef.setInput('userSettings', options.userSettings ?? {});
  const asked: string[] = [];
  const component = fixture.componentInstance;
  component.changeLanguage.subscribe((code) => asked.push(`language:${code}`));
  component.themeChange.subscribe((theme) => asked.push(`theme:${theme}`));
  component.toggleSound.subscribe((on) => asked.push(`sound:${on}`));
  component.save.subscribe(() => asked.push('save'));
  const settle = async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };
  await settle();
  const screen = inScreen(fixture.nativeElement);
  const pick = async (triggerId: string, value: string) => {
    (screen.querySelector(`#${triggerId}`) as HTMLButtonElement).click();
    await settle();
    (document.querySelector(`[role="option"][data-value="${value}"]`) as HTMLElement).click();
    await settle();
  };
  const sound = () => screen.querySelector('#settings-notification-sound') as HTMLButtonElement;
  return { fixture, screen, asked, settle, pick, sound };
}

describe('SettingsPreferencesPanelComponent', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((node) => node.remove());
  });

  it('shows the current language and theme, the themes named in the interface language', async () => {
    const { screen, settle } = await render();

    expect(screen.querySelector('#settings-interface-language').textContent).toContain('Русский (RU)');
    expect(screen.querySelector('#settings-theme').textContent).toContain('Светлая (Light Clean)');

    (screen.querySelector('#settings-theme') as HTMLButtonElement).click();
    await settle();
    const themes = Array.from(document.querySelectorAll('[role="option"][data-value]')) as HTMLElement[];
    expect(themes.map((option) => option.getAttribute('data-value'))).toEqual(['dark', 'light', 'system']);
    expect(themes[2].textContent).toContain('Системная тема');
  });

  it('asks for another interface language and theme when they are picked', async () => {
    const { asked, pick } = await render();

    await pick('settings-interface-language', 'en');
    await pick('settings-theme', 'dark');

    expect(asked).toEqual(['language:en', 'theme:dark']);
  });

  it('has the notification sound on unless it was turned off, and reports a flip', async () => {
    const { fixture, asked, settle, sound } = await render();

    expect(sound().getAttribute('role')).toBe('switch');
    expect(sound().getAttribute('aria-checked')).toBe('true');
    sound().click();
    expect(asked).toEqual(['sound:false']);

    fixture.componentRef.setInput('userSettings', { 'user.notifications_sound': 'false' });
    await settle();
    expect(sound().getAttribute('aria-checked')).toBe('false');
  });

  it('asks to save with its button and locks every control while saving', async () => {
    const { fixture, screen, asked, settle, sound } = await render();

    (screen.querySelector('button[smt-button]') as HTMLButtonElement).click();
    expect(asked).toEqual(['save']);

    fixture.componentRef.setInput('isSaving', true);
    await settle();
    expect((screen.querySelector('#settings-interface-language') as HTMLButtonElement).disabled).toBe(true);
    expect((screen.querySelector('#settings-theme') as HTMLButtonElement).disabled).toBe(true);
    expect(sound().disabled).toBe(true);
  });
});
