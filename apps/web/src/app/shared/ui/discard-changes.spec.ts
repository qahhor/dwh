import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom, of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { I18nService } from '@core/services/i18n.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { translateTest } from '@testing/i18n-test.stub';
import { discardChangesQuestion, formChanged } from './discard-changes';

describe('discardChangesQuestion', () => {
  function question(confirm: ReturnType<typeof vi.fn>) {
    TestBed.configureTestingModule({
      providers: [
        { provide: SMTModalService, useValue: { confirm } },
        { provide: I18nService, useValue: { translate: translateTest, currentLang: signal('ru') } },
      ],
    });
    return TestBed.runInInjectionContext(() => discardChangesQuestion());
  }

  it('lets an untouched form close without a question', async () => {
    const confirm = vi.fn();

    expect(await firstValueFrom(question(confirm)(false))).toBe(true);
    expect(confirm).not.toHaveBeenCalled();
  });

  it('asks a destructive question with the common texts and passes the answer on', async () => {
    const confirm = vi.fn(() => of(false));

    expect(await firstValueFrom(question(confirm)(true))).toBe(false);
    expect(confirm).toHaveBeenCalledWith({
      title: 'Отменить изменения?',
      message: 'Изменения будут потеряны.',
      yesLabel: 'Не сохранять',
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

  it('sees a key present on one side only', () => {
    expect(formChanged<{ a?: number }>({}, { a: 1 })).toBe(true);
  });
});
