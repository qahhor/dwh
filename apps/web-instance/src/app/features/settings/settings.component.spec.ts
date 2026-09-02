import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/services/api.service';
import { I18nService } from '../../core/services/i18n.service';
import { PermissionService } from '../../core/services/permission.service';
import { ToastService } from '../../core/services/toast.service';
import { SettingsComponent } from './settings.component';

describe('SettingsComponent UI contracts', () => {
  async function createFixture() {
    await TestBed.configureTestingModule({
      imports: [SettingsComponent],
      providers: [
        { provide: ApiService, useValue: { get: vi.fn(() => of({})), patch: vi.fn(() => of({})) } },
        { provide: PermissionService, useValue: { hasPermission: () => true } },
        { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn() } },
        {
          provide: I18nService,
          useValue: {
            currentLang: signal('ru'),
            translate: (key: string) => key,
            setLanguage: vi.fn()
          }
        }
      ]
    }).compileComponents();
    const fixture = TestBed.createComponent(SettingsComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('connects settings tabs and general fields', async () => {
    const fixture = await createFixture();

    expect(fixture.nativeElement.querySelector('[role="tablist"][aria-label="Разделы настроек"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('#settings-general-tab[aria-selected="true"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('#settings-general-panel[role="tabpanel"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('label[for="settings-company-name"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('label[for="settings-default-language"]')).not.toBeNull();
  });

  it('names security values and switches', async () => {
    const fixture = await createFixture();
    fixture.componentInstance.activeTab = 'security';
    fixture.detectChanges();

    const passwordLength = fixture.nativeElement.querySelector('#settings-password-length') as HTMLInputElement;
    expect(fixture.nativeElement.querySelector(`label[for="${passwordLength.id}"]`)).not.toBeNull();
    expect(passwordLength.min).toBe('8');
    expect(passwordLength.getAttribute('aria-describedby')).toBe('settings-password-length-hint');
    expect(fixture.nativeElement.querySelector('#settings-require-2fa[aria-labelledby="settings-require-2fa-label"]')).not.toBeNull();
  });

  it('uses only local system information and exposes no custom-module controls', async () => {
    const fixture = await createFixture();
    const api = TestBed.inject(ApiService) as unknown as { get: ReturnType<typeof vi.fn> };

    expect(api.get).toHaveBeenCalledWith('/system/info');
    expect(api.get).not.toHaveBeenCalledWith('/system/license-info');
    expect(api.get).not.toHaveBeenCalledWith('/modules');
    expect(fixture.nativeElement.querySelector('#settings-modules-tab')).toBeNull();

    fixture.componentInstance.activeTab = 'system';
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('Control Plane');
    expect(fixture.nativeElement.textContent).not.toContain('Лиценз');
  });
});
