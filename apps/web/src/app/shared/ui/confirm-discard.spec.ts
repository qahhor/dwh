import { signal } from '@angular/core';
import { firstValueFrom, of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { I18nService } from '@core/services/i18n.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { translateTest } from '@testing/i18n-test.stub';
import { confirmDiscard, formChanged } from './confirm-discard';

describe('confirmDiscard', () => {
  const i18n = { translate: translateTest, currentLang: signal('ru') } as unknown as I18nService;

  it('lets an untouched form close without a question', async () => {
    const modal = { confirm: vi.fn() } as unknown as SMTModalService;

    expect(await firstValueFrom(confirmDiscard(modal, i18n, false))).toBe(true);
    expect(modal.confirm).not.toHaveBeenCalled();
  });

  it('asks a destructive question with the common texts and passes the answer on', async () => {
    const confirm = vi.fn(() => of(false));
    const modal = { confirm } as unknown as SMTModalService;

    expect(await firstValueFrom(confirmDiscard(modal, i18n, true))).toBe(false);
    expect(confirm).toHaveBeenCalledWith({
      title: 'Отменить изменения?',
      message: 'Изменения будут потеряны.',
      yesLabel: 'Отменить изменения',
      noLabel: 'Продолжить редактирование',
      destructive: true,
    });
  });
});

describe('formChanged', () => {
  it('compares text without outer spaces and other values exactly', () => {
    expect(formChanged({ name: 'Role', order: 1 }, { name: ' Role ', order: 1 })).toBe(false);
    expect(formChanged({ name: 'Role', order: 1 }, { name: 'Roles', order: 1 })).toBe(true);
    expect(formChanged({ name: 'Role', order: 1 }, { name: 'Role', order: 2 })).toBe(true);
    expect(formChanged({ flag: false }, { flag: true })).toBe(true);
  });
});
