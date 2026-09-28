import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { User, UserSecuritySummary } from '@core/models/auth.models';
import { ApiService } from '@core/services/api.service';
import { buttonText } from '@testing/button-text';
import { inScreen } from '@testing/in-screen';
import { UserDetailModalComponent } from './user-detail-modal.component';

describe('UserDetailModalComponent', () => {
  const anna: User = {
    id: 7,
    name: 'Анна Иванова',
    login: 'anna',
    email: 'anna@example.test',
    state: 'A',
    managerId: 3,
    language: 'ru',
    timezone: 'Asia/Tashkent',
    attributes: {},
    is2faEnabled: true,
    forcePasswordChange: false,
    roleIds: [1, 2],
    createdAt: '2026-08-30T00:00:00Z',
    modifiedAt: '2026-08-30T00:00:00Z',
  };
  const security: UserSecuritySummary = {
    userId: 7,
    login: 'anna',
    is2faEnabled: false,
    forcePasswordChange: true,
    createdAt: '2026-08-30T00:00:00Z',
    authVersion: 4,
    activeSessionsCount: 1,
    activeSessions: [
      {
        id: 51,
        userId: 7,
        ip: '10.0.0.5',
        userAgent: 'Firefox',
        deviceInfo: '',
        createdAt: '2026-09-27T10:00:00Z',
        lastSeenAt: '2026-09-28T10:00:00Z',
      },
    ],
    recentLoginAttempts: [
      { id: 1, login: 'anna', ip: '10.0.0.5', isSuccess: true, attemptAt: '2026-09-28T09:00:00Z' },
      {
        id: 2,
        login: 'anna',
        ip: '10.0.0.9',
        isSuccess: false,
        failureReason: 'bad password',
        attemptAt: '2026-09-28T08:00:00Z',
      },
    ],
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [{ provide: ApiService, useValue: { get: vi.fn(() => of({ items: [], nextCursor: null })) } }],
    });
  });

  function setup(inputs: Record<string, unknown> = {}) {
    const fixture = TestBed.createComponent(UserDetailModalComponent);
    const set = (name: string, value: unknown) => fixture.componentRef.setInput(name, value);
    set('isOpen', true);
    set('safeRecordId', () => true);
    set('getUserRoleNames', () => ['Администратор', 'Аналитик']);
    set('getManagerName', () => 'Бахтиёр Каримов');
    set('viewingUser', anna);
    for (const [name, value] of Object.entries(inputs)) set(name, value);
    const component = fixture.componentInstance;
    const asked = {
      close: vi.fn(),
      retry: vi.fn(),
      tab: vi.fn(),
      edit: vi.fn(),
      forcePassword: vi.fn(),
      reset2fa: vi.fn(),
      terminateAll: vi.fn(),
      terminateOne: vi.fn(),
    };
    component.closeRecordView.subscribe(asked.close);
    component.retryRecordView.subscribe(asked.retry);
    component.switchTab.subscribe(asked.tab);
    component.openEdit.subscribe(asked.edit);
    component.forcePasswordChange.subscribe(asked.forcePassword);
    component.reset2fa.subscribe(asked.reset2fa);
    component.terminateAllSessions.subscribe(asked.terminateAll);
    component.terminateSingleSession.subscribe(asked.terminateOne);
    fixture.detectChanges();
    const screen = inScreen(fixture.nativeElement);
    const byText = (text: string) =>
      (Array.from(screen.querySelectorAll('button')) as HTMLButtonElement[]).find(
        (item) => buttonText(item) === text,
      ) ?? null;
    const tabs = () => (Array.from(screen.querySelectorAll('[role="tab"]')) as HTMLElement[]).map(buttonText);
    return { fixture, screen, byText, tabs, asked };
  }

  it('says the record is loading, missing or failed, and offers a retry only for a failure', () => {
    const loading = setup({ viewingUser: null, recordLoading: true });
    expect(loading.screen.querySelector('[role="status"]').textContent).toBe('Загрузка записи…');
    loading.fixture.destroy();

    const missing = setup({ viewingUser: null, recordError: true, recordNotFound: true });
    expect(missing.screen.querySelector('[role="alert"]').textContent).toContain('Запись не найдена');
    expect(missing.byText('Повторить')).toBeNull();
    missing.fixture.destroy();

    const failed = setup({ viewingUser: null, recordError: true, routeRecordId: '7' });
    expect(failed.screen.querySelector('[role="alert"]').textContent).toContain('Не удалось загрузить запись.');
    failed.byText('Повторить')!.click();
    expect(failed.asked.retry).toHaveBeenCalledWith('7');
  });

  it('shows the main facts of the person on the first tab', () => {
    const { screen } = setup();

    expect(screen.querySelector('.view-header-card .name').textContent).toBe('Анна Иванова');
    expect(screen.querySelector('.view-header-card .handle').textContent).toBe('@anna');
    const info = screen.querySelector('.info-list').textContent;
    expect(info).toContain('anna@example.test');
    expect(info).toContain('Бахтиёр Каримов');
    expect(info).toContain('Администратор, Аналитик');
    expect(info).toContain('Включена');
    expect(info).toContain('Активен');
  });

  it('offers the org structure and effective rights tabs only with the right to see them', () => {
    const plain = setup();
    expect(plain.tabs()).toEqual(['Основное', 'Безопасность и сессии']);
    plain.fixture.destroy();

    const full = setup({ canViewOrgUnits: true, canViewAssignments: true });
    expect(full.tabs()).toEqual(['Основное', 'Безопасность и сессии', 'Оргструктура', 'Эффективные права']);
    (full.screen.querySelectorAll('[role="tab"]')[1] as HTMLElement).click();
    expect(full.asked.tab).toHaveBeenCalledWith({ tab: 'security', userId: 7 });
  });

  it('offers only the security actions that apply, to a viewer who may change the user', () => {
    const viewer = setup({ activeViewTab: 'security', userSecurity: security });
    expect(viewer.screen.querySelector('.sec-actions-bar')).toBeNull();
    viewer.fixture.destroy();

    const { byText, asked } = setup({ activeViewTab: 'security', userSecurity: security, canUpdateUser: true });
    // A change of password is already required and two-factor sign-in is off.
    expect(byText('Потребовать смену пароля')!.disabled).toBe(true);
    expect(byText('Сбросить 2FA')!.disabled).toBe(true);
    byText('Завершить все сессии')!.click();
    expect(asked.terminateAll).toHaveBeenCalledWith(7);
  });

  it('lists the open sessions and recent sign-ins, and ends one session by its named button', () => {
    const { screen, asked } = setup({ activeViewTab: 'security', userSecurity: security });

    const attempts = screen.querySelector('[data-testid="user-login-attempts-table"]').textContent;
    expect(attempts).toContain('Успешно');
    expect(attempts).toContain('Ошибка');
    expect(attempts).toContain('bad password');
    (screen.querySelector('button[aria-label="Завершить сессию с IP 10.0.0.5"]') as HTMLButtonElement).click();

    expect(asked.terminateOne).toHaveBeenCalledWith({ sessionId: 51, userId: 7 });
  });

  it('offers editing only for a record it can edit, and closes back to the list when opened by link', () => {
    const editor = setup({ canUpdateUser: true });
    editor.byText('Редактировать')!.click();
    expect(editor.asked.edit).toHaveBeenCalledTimes(1);
    expect(editor.byText('Закрыть')).not.toBeNull();
    editor.fixture.destroy();

    const readOnly = setup({ canUpdateUser: true, safeRecordId: () => false, routeRecordId: 'ext-7' });
    expect(readOnly.byText('Редактировать')).toBeNull();
    expect(readOnly.screen.textContent).toContain('Эта запись доступна только для просмотра');
    readOnly.byText('Вернуться к списку')!.click();
    expect(readOnly.asked.close).toHaveBeenCalledTimes(1);
  });
});
