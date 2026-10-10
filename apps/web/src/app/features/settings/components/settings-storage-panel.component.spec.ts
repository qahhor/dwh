import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { SettingChange } from '../settings.models';
import { SettingsStoragePanelComponent } from './settings-storage-panel.component';

function render(quota: string | undefined, canUpdate = true) {
  const fixture = TestBed.createComponent(SettingsStoragePanelComponent);
  fixture.componentRef.setInput('canUpdateSystemSettings', canUpdate);
  fixture.componentRef.setInput(
    'systemSettings',
    quota === undefined ? {} : { 'storage.default_user_quota_mb': quota },
  );
  const changes: SettingChange[] = [];
  let saves = 0;
  fixture.componentInstance.settingChange.subscribe((change) => changes.push(change));
  fixture.componentInstance.save.subscribe(() => saves++);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const field = () => host.querySelector('#settings-user-quota') as HTMLInputElement;
  const badge = () => host.querySelector('[data-testid="user-quota-unit"]')?.textContent?.trim();
  return { fixture, host, changes, saves: () => saves, field, badge };
}

describe('SettingsStoragePanelComponent', () => {
  it('shows the stored quota and reads a large one in gigabytes as well', () => {
    const { field, badge } = render('5120');

    expect(field().value).toBe('5120');
    expect(badge()).toBe('5120 МБ (~5 ГБ)');
  });

  it('reads a quota under a gigabyte in megabytes only, and shows no badge without a value', () => {
    expect(render('512').badge()).toBe('512 МБ');
    expect(render(undefined).badge()).toBeUndefined();
  });

  it('sends a typed quota up as text', () => {
    const { changes, field } = render('1024');

    field().value = '2048';
    field().dispatchEvent(new Event('input'));

    expect(changes).toEqual([{ key: 'storage.default_user_quota_mb', value: '2048' }]);
  });

  it('is read only without the right to change system settings: a badge, a locked field and no save', () => {
    const { host, field } = render('1024', false);

    expect(host.textContent).toContain('Только чтение');
    expect(field().disabled).toBe(true);
    expect(host.querySelector('button[smt-button]')).toBeNull();
  });

  it('asks to save with its button and locks the field while saving', () => {
    const { fixture, host, saves, field } = render('1024');

    (host.querySelector('button[smt-button]') as HTMLButtonElement).click();
    expect(saves()).toBe(1);

    fixture.componentRef.setInput('isSaving', true);
    fixture.detectChanges();
    expect(field().disabled).toBe(true);
  });
});
