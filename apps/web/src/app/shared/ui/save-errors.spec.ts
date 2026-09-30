import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { I18nService } from '@core/services/i18n.service';
import { ToastService } from '@core/services/toast.service';
import { translateTest } from '@testing/i18n-test.stub';
import { SaveErrorNotifier, isRevisionConflict } from './save-errors';

describe('SaveErrorNotifier', () => {
  function setup() {
    const toast = { error: vi.fn(), show: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        { provide: ToastService, useValue: toast },
        { provide: I18nService, useValue: { translate: translateTest, currentLang: signal('ru') } },
      ],
    });
    return { notifier: TestBed.inject(SaveErrorNotifier), toast };
  }

  it('knows a refusal over a newer revision (409 revision_conflict) or without one (428), and nothing else', () => {
    expect(isRevisionConflict({ status: 409, code: 'revision_conflict' })).toBe(true);
    expect(isRevisionConflict({ status: 428, code: 'precondition_required' })).toBe(true);
    expect(isRevisionConflict({ status: 409, code: 'conflict' })).toBe(false);
    expect(isRevisionConflict({ status: 422 })).toBe(false);
    expect(isRevisionConflict(null)).toBe(false);
    expect(isRevisionConflict('409')).toBe(false);
  });

  it('shows the server text of a conflict with a Refresh button that runs the reload', () => {
    const { notifier, toast } = setup();
    const reload = vi.fn();

    notifier.show(
      { status: 409, code: 'revision_conflict', detail: 'Запись уже изменил другой пользователь' },
      {
        fallbackKey: 'common.error',
        reload,
      },
    );

    expect(toast.error).not.toHaveBeenCalled();
    const [type, message, title, duration, action] = toast.show.mock.calls[0];
    expect(type).toBe('error');
    expect(message).toBe('Запись уже изменил другой пользователь');
    expect(title).toBe('Данные устарели');
    expect(duration).toBe(SaveErrorNotifier.CONFLICT_TOAST_MS);
    expect(action.label).toBe('Обновить');
    action.run();
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('shows a conflict without a button when the screen reads again by itself, in the catalog text if none came', () => {
    const { notifier, toast } = setup();

    notifier.show({ status: 428 }, { fallbackKey: 'common.error' });

    const [, message, , , action] = toast.show.mock.calls[0];
    expect(message).toBe('Запись уже изменил другой пользователь. Обновите её и повторите сохранение');
    expect(action).toBeUndefined();
  });

  it('shows any other failure once, in the server text or the screen text', () => {
    const { notifier, toast } = setup();

    notifier.show({ status: 422, detail: 'Проверьте поля' }, { fallbackKey: 'common.error' });
    notifier.show({ status: 0 }, { fallbackKey: 'common.error' });

    expect(toast.error.mock.calls).toEqual([['Проверьте поля'], ['Ошибка']]);
    expect(toast.show).not.toHaveBeenCalled();
  });
});
