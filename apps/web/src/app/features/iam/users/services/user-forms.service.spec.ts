import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Role } from '@core/models/rbac.models';
import { ApiService } from '@core/services/api.service';
import { I18nService } from '@core/services/i18n.service';
import { ToastService } from '@core/services/toast.service';
import { translateTest } from '@testing/i18n-test.stub';
import { UserFormsService } from './user-forms.service';

describe('UserFormsService', () => {
  const originalClipboard = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
  afterEach(() => {
    if (originalClipboard) Object.defineProperty(navigator, 'clipboard', originalClipboard);
    else Reflect.deleteProperty(navigator, 'clipboard');
  });

  function setup() {
    const toast = { success: vi.fn(), warning: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        { provide: ApiService, useValue: { post: vi.fn(() => of({})), patch: vi.fn(() => of({})) } },
        { provide: ToastService, useValue: toast },
        { provide: I18nService, useValue: { translate: translateTest, currentLang: signal('ru') } },
      ],
    });
    return { forms: TestBed.inject(UserFormsService), toast };
  }

  it('opens the create form with the default user role chosen', () => {
    const { forms } = setup();
    const roles = [
      { id: 1, pcode: 'admin', name: 'Администратор' },
      { id: 5, pcode: 'user', name: 'Пользователь' },
    ] as Role[];

    forms.openCreateModal(roles);

    expect(forms.isCreateModalOpen()).toBe(true);
    expect(forms.isCreateSubmitted).toBe(false);
    expect(forms.createForm.roleIds).toEqual([5]);
  });

  it('evaluates password strength and the requirements checklist as the password changes', () => {
    const { forms } = setup();
    forms.openCreateModal([]);
    forms.createForm.login = 'john';

    forms.createForm.password = 'short';
    expect(forms.hasMinLength()).toBe(false);
    expect(forms.passwordStrength().score).toBe(1);

    forms.createForm.password = 'johnStrong123!';
    expect(forms.doesNotContainLogin()).toBe(false);

    forms.createForm.password = 'SafePass123!#';
    expect(forms.hasMinLength()).toBe(true);
    expect(forms.hasUpperAndLower()).toBe(true);
    expect(forms.hasDigitsOrSymbols()).toBe(true);
    expect(forms.doesNotContainLogin()).toBe(true);
    expect(forms.passwordStrength().score).toBe(4);
  });

  it('generates a 14-character password that meets every rule and avoids the login', () => {
    const { forms } = setup();
    forms.createForm.login = 'testuser';

    const generated = forms.generateSecurePassword();

    expect(generated.length).toBe(14);
    expect(forms.createForm.password).toBe(generated);
    expect(forms.hasMinLength()).toBe(true);
    expect(forms.hasUpperAndLower()).toBe(true);
    expect(forms.hasDigitsOrSymbols()).toBe(true);
    expect(forms.doesNotContainLogin()).toBe(true);
    expect(forms.passwordStrength().score).toBe(4);
  });

  it('copies the generated password to the clipboard and says so', async () => {
    const { forms, toast } = setup();
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });

    forms.createForm.password = 'ComplexPass123!';
    await forms.copyGeneratedPassword();

    expect(writeText).toHaveBeenCalledWith('ComplexPass123!');
    expect(toast.success).toHaveBeenCalled();
  });
});
