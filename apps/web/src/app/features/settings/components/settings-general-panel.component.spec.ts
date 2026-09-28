import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';
import { inScreen } from '@testing/in-screen';
import { SettingChange } from '../settings.models';
import { SettingsGeneralPanelComponent } from './settings-general-panel.component';

const SETTINGS = {
  'system.company_name': 'Smartup',
  'system.default_language': 'ru',
  'system.default_timezone': 'Asia/Tashkent',
  'system.date_format': 'dd.MM.yyyy HH:mm',
};

const LANGUAGES = [
  { code: 'ru', name: 'Русский' },
  { code: 'uz', name: 'Oʻzbekcha' },
];

async function render(options: { canUpdate?: boolean; saving?: boolean; settings?: Record<string, string> } = {}) {
  const fixture = TestBed.createComponent(SettingsGeneralPanelComponent);
  document.body.appendChild(fixture.nativeElement);
  fixture.componentRef.setInput('canUpdateSystemSettings', options.canUpdate ?? true);
  fixture.componentRef.setInput('isSaving', options.saving ?? false);
  fixture.componentRef.setInput('languages', LANGUAGES);
  fixture.componentRef.setInput('systemSettings', options.settings ?? SETTINGS);
  const changes: SettingChange[] = [];
  let saves = 0;
  fixture.componentInstance.settingChange.subscribe((change) => changes.push(change));
  fixture.componentInstance.save.subscribe(() => saves++);
  const settle = async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };
  await settle();
  const screen = inScreen(fixture.nativeElement);
  /** Opens a select by its trigger id and returns the ids of the options it offers. */
  const open = async (id: string) => {
    (screen.querySelector(`#${id}`) as HTMLButtonElement).click();
    await settle();
    return (Array.from(document.querySelectorAll('[role="option"]')) as HTMLElement[]).filter((option) =>
      option.hasAttribute('data-value'),
    );
  };
  return { fixture, screen, changes, saves: () => saves, settle, open };
}

describe('SettingsGeneralPanelComponent', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((node) => node.remove());
  });

  it('shows the stored company settings in fields named by their labels', async () => {
    const { screen } = await render();

    expect(screen.querySelector('label[for="settings-company-name"]').textContent).toContain(
      'Название компании / экземпляра',
    );
    expect((screen.querySelector('#settings-company-name') as HTMLInputElement).value).toBe('Smartup');
    expect(screen.querySelector('#settings-default-language').textContent).toContain('Русский (RU)');
    expect(screen.querySelector('#settings-default-timezone').textContent).toContain('Asia/Tashkent (UTC+5)');
    expect(screen.querySelector('#settings-default-language').getAttribute('role')).toBe('combobox');
  });

  it('sends a typed company name up as a setting change', async () => {
    const { screen, changes } = await render();
    const field = screen.querySelector('#settings-company-name') as HTMLInputElement;

    field.value = 'Smartup Holding';
    field.dispatchEvent(new Event('input'));

    expect(changes).toEqual([{ key: 'system.company_name', value: 'Smartup Holding' }]);
  });

  it('sends a picked date format up as a setting change', async () => {
    const { changes, open, settle } = await render();

    const options = await open('settings-date-format');
    options.find((option) => option.getAttribute('data-value') === 'yyyy-MM-dd HH:mm')!.click();
    await settle();

    expect(changes).toEqual([{ key: 'system.date_format', value: 'yyyy-MM-dd HH:mm' }]);
  });

  it('keeps a stored time zone outside the known list as a choice of its own', async () => {
    const { open } = await render({ settings: { ...SETTINGS, 'system.default_timezone': 'America/Chicago' } });

    const options = await open('settings-default-timezone');

    expect(options.map((option) => option.getAttribute('data-value'))).toContain('America/Chicago');
    expect(options.map((option) => option.getAttribute('data-value'))).toContain('Asia/Tashkent');
  });

  it('is read only without the right to change system settings: a badge, locked fields and no save', async () => {
    const { screen } = await render({ canUpdate: false });

    expect(screen.textContent).toContain('Только чтение');
    expect((screen.querySelector('#settings-company-name') as HTMLInputElement).disabled).toBe(true);
    expect((screen.querySelector('#settings-date-format') as HTMLButtonElement).disabled).toBe(true);
    expect(screen.querySelector('button[smt-button]')).toBeNull();
  });

  it('asks to save with its button and locks the fields while saving', async () => {
    const { fixture, screen, saves, settle } = await render();

    expect(screen.textContent).not.toContain('Только чтение');
    (screen.querySelector('button[smt-button]') as HTMLButtonElement).click();
    expect(saves()).toBe(1);

    fixture.componentRef.setInput('isSaving', true);
    await settle();
    expect((screen.querySelector('#settings-company-name') as HTMLInputElement).disabled).toBe(true);
  });
});
